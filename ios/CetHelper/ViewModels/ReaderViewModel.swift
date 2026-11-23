import Foundation
import Observation

@MainActor @Observable final class ReaderViewModel {
    let article: ReadingArticle
    let paragraphs: [Paragraph]
    private(set) var savedRanges: [TextRange] = []
    private(set) var selection: Selection? = nil
    private(set) var panel: ReaderPanel = .none
    let lookup: PracticeRequest<ExplanationResult>
    let analysis: PracticeRequest<SentenceAnalysis>
    private(set) var isSelectionSaved: Bool = false
    private(set) var selectionNotice: String? = nil
    private(set) var showsDemoBadge: Bool = false
    var canSaveSelection: Bool { selection?.sentence != nil && lookup.value != nil }
    var canAnalyze: Bool { selection?.sentence != nil }
    var canAskTutorWord: Bool { selection != nil }
    var canAskTutorSentence: Bool { analysis.value != nil }
    private(set) var fontTier: ReaderFontTier = .standard
    private(set) var initialScrollY: Int = 0
    var scrollTarget: TextRange? = nil
    private(set) var progress: Double = 0

    @ObservationIgnored var onAskTutor: ((TutorContext) -> Void)?
    @ObservationIgnored private let session: Session
    @ObservationIgnored private let environment: AppEnvironment
    @ObservationIgnored private var pendingScrollY: Int?
    @ObservationIgnored private var pendingProgress: Double?
    @ObservationIgnored private var scrollSaveTask: Task<Void, Never>?

    init(
        article: ReadingArticle,
        focus: TextRange?,
        session: Session,
        environment: AppEnvironment,
        onUnauthorized: @escaping @MainActor () -> Void
    ) {
        self.article = article
        self.paragraphs = article.paragraphs()
        self.session = session
        self.environment = environment
        self.fontTier = environment.readerPrefs.fontTier
        self.scrollTarget = focus

        var lookupDemoUnsupportedHandler: (@MainActor (Error) -> Void)?
        self.lookup = PracticeRequest<ExplanationResult>(
            errorMapper: ApiErrorFormatter.lookupFormat,
            onUnauthorized: onUnauthorized,
            onError: { error in
                lookupDemoUnsupportedHandler?(error)
            }
        )
        self.analysis = PracticeRequest<SentenceAnalysis>(
            errorMapper: ApiErrorFormatter.sentenceAnalysisFormat,
            onUnauthorized: onUnauthorized
        )

        lookupDemoUnsupportedHandler = { [weak self] error in
            if let apiError = error as? ApiError, apiError.isDemoUnsupported {
                self?.showsDemoBadge = true
            }
        }

        if article.isDemo {
            self.initialScrollY = environment.readerPrefs.demoScrollY(for: session.owner)
        } else if let stored = try? environment.database.findArticle(
            serverUrl: session.serverUrl,
            studentId: session.student.id,
            articleId: article.id
        ) {
            self.initialScrollY = stored.lastScrollY
        }

        if let savedProgress = environment.readerPrefs.articleProgress(for: session.owner, articleId: article.id) {
            self.progress = savedProgress
        }

        reloadSavedRanges()
    }

    /// WordTextView 点词 / "查释义" 菜单回调（绝对 UTF-16 区间）。
    func select(_ range: TextRange) {
        guard let paragraph = paragraphs.first(where: { $0.start <= range.start && range.end <= $0.end }),
              let sel = paragraph.select(from: range.start - paragraph.start, to: range.end - paragraph.start) else {
            return
        }

        lookup.reset()
        analysis.reset()
        selection = sel
        panel = .lookup
        showsDemoBadge = false
        updateSavedFlag()

        guard let sentence = sel.sentence else {
            selectionNotice = "请在同一句中选择单词或词组。"
            return
        }
        selectionNotice = nil
        performLookup(selection: sel.text, sentence: sentence)
    }

    func closePanel() {
        lookup.reset()
        analysis.reset()
        selection = nil
        selectionNotice = nil
        showsDemoBadge = false
        isSelectionSaved = false
        panel = .none
    }

    func toggleFavorite() {
        guard let sel = selection else { return }
        if isSelectionSaved {
            try? environment.database.deleteFavorite(
                serverUrl: session.serverUrl,
                studentId: session.student.id,
                articleId: article.id,
                startOffset: sel.start,
                endOffset: sel.end
            )
            reloadSavedRanges()
            return
        }
        guard let sentence = sel.sentence, let result = lookup.value else {
            return
        }
        let now = environment.nowMs()
        let favorite = Favorite(
            serverUrl: session.serverUrl,
            studentId: session.student.id,
            articleId: article.id,
            startOffset: sel.start,
            endOffset: sel.end,
            selection: sel.text,
            sentence: sentence,
            meaning: result.meaning,
            explanation: result.explanation,
            mode: result.mode,
            savedAt: now,
            phonetic: result.phonetic,
            partOfSpeech: result.partOfSpeech,
            masteryLevel: 0,
            reviewCount: 0,
            lastReviewedAt: 0,
            nextReviewAt: 0,
            articleTitle: article.title,
            lemma: result.lemma,
            cetLevel: result.cetLevel,
            sourceType: Favorite.sourceReader
        )
        try? environment.database.insertFavorite(favorite)
        reloadSavedRanges()
    }

    func buildWordTutorContext() -> TutorContext? {
        guard let sel = selection else { return nil }
        let result = lookup.value
        let title: String
        if let result, !result.lemma.isEmpty {
            title = result.lemma
        } else {
            title = sel.text
        }
        let extraNote: String
        if let result {
            extraNote = "释义：\(result.meaning)" + (result.explanation.isEmpty ? "" : "\n\(result.explanation)")
        } else {
            extraNote = ""
        }
        return TutorContext(
            type: .word,
            title: title,
            focusText: sel.text,
            referenceText: sel.sentence ?? "",
            extraNote: extraNote
        )
    }

    func buildSentenceTutorContext() -> TutorContext? {
        guard let analysisValue = analysis.value else { return nil }
        let focus = analysisValue.sentence.isEmpty ? (selection?.sentence ?? "") : analysisValue.sentence
        let extraNote = "参考译文：\(analysisValue.translation)\n句子主干：\(analysisValue.structure)"
        return TutorContext(
            type: .sentence,
            title: "长难句分析",
            focusText: focus,
            referenceText: "",
            extraNote: extraNote
        )
    }

    /// 根据当前 panel 自动选择 .sentence（panel == .analysis）或 .word 上下文并调用 onAskTutor。
    func askTutor() {
        if panel == .analysis {
            askTutorForSentence()
        } else {
            askTutorForWord()
        }
    }

    func askTutorForWord() {
        guard let ctx = buildWordTutorContext() else { return }
        onAskTutor?(ctx)
    }

    func askTutorForSentence() {
        guard let ctx = buildSentenceTutorContext() else { return }
        onAskTutor?(ctx)
    }

    func analyzeSentence() {
        guard let sentence = selection?.sentence else { return }
        panel = .analysis
        performAnalysis(sentence: sentence)
    }

    func showLookupPanel() {
        analysis.reset()
        if selection != nil {
            panel = .lookup
        } else {
            panel = .none
        }
    }

    func retryLookup() {
        guard let sel = selection, let sentence = sel.sentence else { return }
        showsDemoBadge = false
        performLookup(selection: sel.text, sentence: sentence, bypassCache: true)
    }

    func retryAnalysis() {
        guard let sentence = selection?.sentence else { return }
        performAnalysis(sentence: sentence, bypassCache: true)
    }

    func cycleFontTier() {
        fontTier = fontTier.next
        environment.readerPrefs.fontTier = fontTier
    }

    func speak(_ text: String) {
        environment.speech.speak(text)
    }

    /// 滚动回调（600ms 防抖落库）。
    func updateScroll(offsetY: Int, progress: Double) {
        let clampedY = max(0, offsetY)
        let clampedProgress = min(1.0, max(0.0, progress))
        self.progress = clampedProgress
        pendingScrollY = clampedY
        pendingProgress = clampedProgress

        scrollSaveTask?.cancel()
        scrollSaveTask = Task { @MainActor [weak self] in
            do {
                try await Task.sleep(for: .milliseconds(600))
            } catch {
                return
            }
            self?.flushProgress()
        }
    }

    /// .onDisappear 必调。
    func flushProgress() {
        scrollSaveTask?.cancel()
        scrollSaveTask = nil
        guard let scrollY = pendingScrollY else { return }
        let savedProgress = pendingProgress ?? progress
        pendingScrollY = nil
        pendingProgress = nil

        environment.readerPrefs.setArticleProgress(savedProgress, for: session.owner, articleId: article.id)
        if article.isDemo {
            environment.readerPrefs.setDemoScrollY(scrollY, for: session.owner)
        } else {
            try? environment.database.updateArticleProgress(
                serverUrl: session.serverUrl,
                studentId: session.student.id,
                articleId: article.id,
                lastReadAt: environment.nowMs(),
                lastScrollY: scrollY
            )
        }
    }

    // MARK: - Private Helpers

    private func performLookup(selection text: String, sentence: String, bypassCache: Bool = false) {
        let cacheKey = "\(session.serverUrl)\0\(text)\0\(sentence)"
        if !bypassCache, let cached = environment.explanationCache.get(cacheKey) {
            lookup.setValue(cached)
            showsDemoBadge = cached.isDemo
            return
        }
        let activeSession = session
        let api = environment.api
        let cache = environment.explanationCache
        lookup.start({
            try await api.explain(activeSession, selection: text, sentence: sentence)
        }, onComplete: { [weak self] result in
            cache.set(result, forKey: cacheKey)
            self?.showsDemoBadge = result.isDemo
        })
    }

    private func performAnalysis(sentence: String, bypassCache: Bool = false) {
        let cacheKey = "\(session.serverUrl)\0\(sentence)"
        if !bypassCache, let cached = environment.analysisCache.get(cacheKey) {
            analysis.setValue(cached)
            return
        }
        let activeSession = session
        let api = environment.api
        let cache = environment.analysisCache
        analysis.start({
            try await api.analyzeSentence(activeSession, sentence: sentence)
        }, onComplete: { result in
            cache.set(result, forKey: cacheKey)
        })
    }

    func reloadSavedRanges() {
        let favorites = (try? environment.database.listFavoritesForArticle(
            serverUrl: session.serverUrl,
            studentId: session.student.id,
            articleId: article.id
        )) ?? []
        savedRanges = favorites.map(\.range)
        updateSavedFlag()
    }

    private func updateSavedFlag() {
        guard let sel = selection else {
            isSelectionSaved = false
            return
        }
        isSelectionSaved = savedRanges.contains(sel.range)
    }
}
