import Foundation
import Observation

@MainActor @Observable final class DictionaryViewModel {
    private static let suggestDebounce: Duration = .milliseconds(250)
    private static let defaultSuggestLimit = 10
    private static let defaultHistoryLimit = 20

    /// 绑定搜索框；外部编辑触发 250ms 防抖 suggest（程序赋值内部抑制防抖）。
    var query: String = "" {
        didSet {
            guard !isSuppressingQueryChange, query != oldValue else { return }
            scheduleSuggest(for: query)
        }
    }

    /// 级别过滤；变更且 query 非空时刷新联想列表。
    var levelFilter: DictionaryLevelFilter = .all {
        didSet {
            guard !isSuppressingFilterChange, levelFilter != oldValue else { return }
            if !TextMetrics.trimSpace(query).isEmpty {
                scheduleSuggest(for: query)
            }
        }
    }

    private(set) var suggestions: [DictionarySuggestion] = []
    private(set) var history: [DictionaryHistory] = []
    private(set) var current: DictionaryEntry? = nil
    private(set) var isFavorite: Bool = false
    private(set) var isLoading: Bool = false
    private(set) var errorMessage: String? = nil
    var confirmation: ConfirmationRequest? = nil

    /// query 与 current.word 忽略大小写相等且非加载中时返回 false，自动隐藏联想列表。
    var showsSuggestions: Bool {
        guard !suggestions.isEmpty else { return false }
        if let current, !isLoading {
            let trimmed = TextMetrics.trimSpace(query)
            if trimmed.caseInsensitiveCompare(current.word) == .orderedSame {
                return false
            }
        }
        return true
    }

    var isEmptyState: Bool {
        current == nil && history.isEmpty && !isLoading && errorMessage == nil
    }

    @ObservationIgnored var onAskTutor: ((TutorContext) -> Void)?
    @ObservationIgnored private let session: Session
    @ObservationIgnored private let environment: AppEnvironment
    @ObservationIgnored private let onUnauthorized: @MainActor () -> Void
    @ObservationIgnored private var suggestTask: Task<Void, Never>?
    @ObservationIgnored private var lookupTask: Task<Void, Never>?
    @ObservationIgnored private var suggestRevision = 0
    @ObservationIgnored private var lookupRevision = 0
    @ObservationIgnored private var isSuppressingQueryChange = false
    @ObservationIgnored private var isSuppressingFilterChange = false

    init(
        session: Session,
        environment: AppEnvironment,
        onUnauthorized: @escaping @MainActor () -> Void,
        onAskTutor: ((TutorContext) -> Void)? = nil
    ) {
        self.session = session
        self.environment = environment
        self.onUnauthorized = onUnauthorized
        self.onAskTutor = onAskTutor
        loadHistory()
    }

    func selectLevelFilter(_ filter: DictionaryLevelFilter) {
        levelFilter = filter
    }

    func clearQuery() {
        cancelSuggest()
        suggestions = []
        setQuerySilently("")
    }

    /// rawWord 为 nil 时使用当前 query。
    func lookup(_ rawWord: String? = nil) {
        let trimmed = TextMetrics.trimSpace(rawWord ?? query)
        guard !trimmed.isEmpty else { return }

        setQuerySilently(trimmed)
        cancelSuggest()
        suggestions = []

        lookupTask?.cancel()
        lookupRevision += 1
        let rev = lookupRevision

        isLoading = true
        errorMessage = nil

        let activeSession = session
        let api = environment.api
        lookupTask = Task { @MainActor [weak self] in
            guard let self else { return }
            do {
                let entry = try await api.dictionaryLookup(activeSession, word: trimmed)
                guard !Task.isCancelled, rev == self.lookupRevision else { return }
                let now = self.environment.nowMs()
                let db = self.environment.database
                try? db.upsertDictionaryHistory(
                    entry.toHistory(
                        serverUrl: activeSession.serverUrl,
                        studentId: activeSession.student.id,
                        now: now
                    )
                )
                let fav = (try? db.findDictionaryFavorite(
                    serverUrl: activeSession.serverUrl,
                    studentId: activeSession.student.id,
                    selection: entry.canonicalWord
                )) != nil
                let recent = (try? db.recentDictionaryHistory(
                    serverUrl: activeSession.serverUrl,
                    studentId: activeSession.student.id,
                    limit: Self.defaultHistoryLimit
                )) ?? self.history

                self.current = entry
                self.isFavorite = fav
                self.history = recent
                self.isLoading = false
                self.errorMessage = nil
            } catch is CancellationError {
                return
            } catch {
                guard !Task.isCancelled, rev == self.lookupRevision else { return }
                self.isLoading = false
                if let apiError = error as? ApiError {
                    if apiError == .cancelled {
                        return
                    }
                    if apiError.isUnauthorized {
                        self.onUnauthorized()
                        return
                    }
                }
                self.errorMessage = ApiErrorFormatter.generalMessage(error)
            }
        }
    }

    func selectSuggestion(_ suggestion: DictionarySuggestion) {
        lookup(suggestion.word)
    }

    func selectHistory(_ item: DictionaryHistory) {
        lookup(item.word)
    }

    func toggleFavorite() {
        guard let entry = current else { return }
        let db = environment.database
        let serverUrl = session.serverUrl
        let studentId = session.student.id
        let keyWord = entry.canonicalWord

        do {
            if try db.findDictionaryFavorite(serverUrl: serverUrl, studentId: studentId, selection: keyWord) != nil {
                try db.deleteDictionaryFavorite(serverUrl: serverUrl, studentId: studentId, selection: keyWord)
                isFavorite = false
            } else {
                let row = Favorite.fromDictionary(
                    serverUrl: serverUrl,
                    studentId: studentId,
                    entry: entry,
                    now: environment.nowMs()
                )
                try db.insertFavorite(row)
                isFavorite = true
            }
        } catch {
            // Keep UI responsive on non-fatal persistence errors.
        }
    }

    func loadHistory() {
        history = (try? environment.database.recentDictionaryHistory(
            serverUrl: session.serverUrl,
            studentId: session.student.id,
            limit: Self.defaultHistoryLimit
        )) ?? []
        refreshFavoriteState()
    }

    func requestClearHistory() {
        guard !history.isEmpty else { return }
        confirmation = ConfirmationRequest(
            title: "清空查词历史",
            message: "确定要清空全部查词历史记录吗？",
            confirmTitle: "清空",
            isDestructive: true,
            onConfirm: { [weak self] in
                self?.clearHistory()
            }
        )
    }

    func clearHistory() {
        try? environment.database.clearDictionaryHistory(
            serverUrl: session.serverUrl,
            studentId: session.student.id
        )
        history = []
    }

    func deleteHistory(_ item: DictionaryHistory) {
        try? environment.database.deleteDictionaryHistory(
            serverUrl: session.serverUrl,
            studentId: session.student.id,
            word: item.word
        )
        loadHistory()
    }

    func speak(_ text: String) {
        environment.speech.speak(text)
    }

    func buildTutorContext() -> TutorContext? {
        guard let entry = current else { return nil }
        let title = entry.lemma.isEmpty ? entry.word : entry.lemma
        let focus = entry.word.isEmpty ? entry.lemma : entry.word
        let extra = "释义：\(entry.meaning)" + (entry.explanation.isEmpty ? "" : "\n\(entry.explanation)")
        return TutorContext(
            type: .word,
            title: title,
            focusText: focus,
            referenceText: entry.exampleSentence,
            extraNote: extra
        )
    }

    func askTutor() {
        guard let ctx = buildTutorContext() else { return }
        onAskTutor?(ctx)
    }

    func refreshFavoriteState() {
        guard let entry = current else {
            isFavorite = false
            return
        }
        isFavorite = (try? environment.database.findDictionaryFavorite(
            serverUrl: session.serverUrl,
            studentId: session.student.id,
            selection: entry.canonicalWord
        )) != nil
    }

    func cancelInFlight() {
        cancelSuggest()
        lookupRevision += 1
        lookupTask?.cancel()
        lookupTask = nil
        isLoading = false
    }

    func reset() {
        cancelInFlight()
        setQuerySilently("")
        isSuppressingFilterChange = true
        levelFilter = .all
        isSuppressingFilterChange = false
        suggestions = []
        history = []
        current = nil
        isFavorite = false
        errorMessage = nil
        confirmation = nil
    }

    // MARK: - Private Helpers

    private func setQuerySilently(_ value: String) {
        isSuppressingQueryChange = true
        query = value
        isSuppressingQueryChange = false
    }

    private func cancelSuggest() {
        suggestRevision += 1
        suggestTask?.cancel()
        suggestTask = nil
    }

    private func scheduleSuggest(for rawQuery: String) {
        cancelSuggest()
        let rev = suggestRevision
        let trimmed = TextMetrics.trimSpace(rawQuery)
        if trimmed.isEmpty {
            suggestions = []
            return
        }
        let level = levelFilter.apiValue
        let activeSession = session
        let api = environment.api
        suggestTask = Task { @MainActor [weak self] in
            do {
                try await Task.sleep(for: Self.suggestDebounce)
            } catch {
                return
            }
            guard let self, !Task.isCancelled, rev == self.suggestRevision else { return }
            do {
                let result = try await api.dictionarySuggest(
                    activeSession,
                    query: trimmed,
                    level: level,
                    limit: Self.defaultSuggestLimit
                )
                guard !Task.isCancelled, rev == self.suggestRevision else { return }
                self.suggestions = result
            } catch is CancellationError {
                return
            } catch {
                guard !Task.isCancelled, rev == self.suggestRevision else { return }
                if let apiError = error as? ApiError {
                    if apiError == .cancelled {
                        return
                    }
                    if apiError.isUnauthorized {
                        self.onUnauthorized()
                        return
                    }
                }
                self.suggestions = []
            }
        }
    }
}
