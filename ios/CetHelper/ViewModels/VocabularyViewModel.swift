import Foundation
import Observation

@MainActor @Observable final class VocabularyViewModel {
    var query: String = ""
    var levelFilter: VocabularyLevelFilter = .all
    var sourceFilter: VocabularySourceFilter = .all
    var masteryFilter: VocabularyMasteryFilter = .all
    private(set) var favorites: [Favorite] = []

    var filteredFavorites: [Favorite] {
        let trimmed = TextMetrics.trimSpace(query).lowercased()
        return favorites.filter { fav in
            switch levelFilter {
            case .all:
                break
            case .cet4:
                guard fav.cetLevel.caseInsensitiveCompare("CET-4") == .orderedSame else { return false }
            case .cet6:
                guard fav.cetLevel.caseInsensitiveCompare("CET-6") == .orderedSame else { return false }
            }

            switch sourceFilter {
            case .all:
                break
            case .reader:
                guard !fav.isDictionarySource else { return false }
            case .dictionary:
                guard fav.isDictionarySource else { return false }
            }

            switch masteryFilter {
            case .all:
                break
            case .unreviewed:
                guard fav.masteryLevel <= 0 else { return false }
            case .learning:
                guard fav.masteryLevel == 1 || fav.masteryLevel == 2 else { return false }
            case .mastered:
                guard fav.masteryLevel >= 3 else { return false }
            }

            guard !trimmed.isEmpty else { return true }
            return fav.selection.lowercased().contains(trimmed)
                || fav.lemma.lowercased().contains(trimmed)
                || fav.meaning.lowercased().contains(trimmed)
                || fav.sentence.lowercased().contains(trimmed)
                || fav.resolvedSourceTitle.lowercased().contains(trimmed)
        }
    }
    var username: String { session.student.username }
    private(set) var totalCount: Int = 0
    private(set) var dueCount: Int = 0
    private(set) var masteredCount: Int = 0
    var confirmation: ConfirmationRequest? = nil
    var canStartCloze: Bool { !favorites.isEmpty }
    var emptyMessage: String? {
        if favorites.isEmpty {
            return "还没有收藏。请先到阅读页收藏单词或词组。"
        }
        if filteredFavorites.isEmpty {
            return "未找到匹配的生词。"
        }
        return nil
    }

    @ObservationIgnored private let session: Session
    @ObservationIgnored private let environment: AppEnvironment
    @ObservationIgnored private let onOpenInReader: (Favorite) -> Void
    @ObservationIgnored private let onStartCloze: () -> Void
    @ObservationIgnored private let onOpenDictionary: ((String) -> Void)?

    init(
        session: Session,
        environment: AppEnvironment,
        onOpenInReader: @escaping (Favorite) -> Void,
        onStartCloze: @escaping () -> Void,
        onOpenDictionary: ((String) -> Void)? = nil
    ) {
        self.session = session
        self.environment = environment
        self.onOpenInReader = onOpenInReader
        self.onStartCloze = onStartCloze
        self.onOpenDictionary = onOpenDictionary
    }

    func refresh() {
        let serverUrl = session.serverUrl
        let studentId = session.student.id
        let now = environment.nowMs()
        let db = environment.database

        let loaded = (try? db.listFavorites(serverUrl: serverUrl, studentId: studentId)) ?? []
        favorites = loaded.map { fav in
            if fav.articleTitle == nil && fav.articleId == DemoArticle.id {
                var copy = fav
                copy.articleTitle = DemoArticle.title
                return copy
            }
            return fav
        }
        totalCount = (try? db.countAllFavorites(serverUrl: serverUrl, studentId: studentId)) ?? favorites.count
        dueCount = (try? db.countDueFavorites(serverUrl: serverUrl, studentId: studentId, nowMs: now)) ?? 0
        masteredCount = favorites.filter { $0.masteryLevel >= 3 }.count
    }

    func requestRemove(_ favorite: Favorite) {
        confirmation = ConfirmationRequest(
            title: "移除生词",
            message: "确定要从生词本中移除「\(favorite.selection)」吗？",
            confirmTitle: "移除",
            isDestructive: true,
            onConfirm: { [weak self] in
                self?.remove(favorite)
            }
        )
    }

    func openFavorite(_ favorite: Favorite) {
        if favorite.isDictionarySource {
            onOpenDictionary?(favorite.selection)
        } else {
            onOpenInReader(favorite)
        }
    }

    func openInReader(_ favorite: Favorite) {
        openFavorite(favorite)
    }

    func resetFilters() {
        levelFilter = .all
        sourceFilter = .all
        masteryFilter = .all
        query = ""
    }

    func startCloze() {
        guard canStartCloze else { return }
        onStartCloze()
    }

    func speak(_ text: String) {
        environment.speech.speak(text)
    }

    private func remove(_ favorite: Favorite) {
        try? environment.database.deleteFavorite(
            serverUrl: favorite.serverUrl,
            studentId: favorite.studentId,
            articleId: favorite.articleId,
            startOffset: favorite.startOffset,
            endOffset: favorite.endOffset
        )
        refresh()
    }
}
