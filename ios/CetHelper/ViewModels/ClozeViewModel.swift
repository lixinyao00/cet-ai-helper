import Foundation
import Observation

enum ClozeOutcome: Equatable {
    case pending
    case correct
    case wrong
    case revealed
}

@MainActor @Observable final class ClozeViewModel {
    private(set) var round: ClozeRound? = nil
    private(set) var emptyMessage: String? = nil
    private(set) var skippedNotice: String? = nil
    var input: String = "" {
        didSet {
            if input != oldValue {
                inputError = nil
            }
        }
    }
    private(set) var inputError: String? = nil
    private(set) var lastResult: ClozeSubmitResult? = nil
    private(set) var outcome: ClozeOutcome = .pending
    private(set) var isHintShown: Bool = false

    var current: ClozeQuestion? { round?.current }
    var currentState: ClozeQuestionState? { round?.currentState }
    var isAnswered: Bool { outcome != .pending }

    var displayTitle: String {
        guard let question = current else { return "" }
        return isAnswered ? question.articleTitle : question.maskedTitle
    }

    var displaySentence: String {
        current?.maskedSentence ?? ""
    }

    var progressText: String {
        guard let round, !round.questions.isEmpty else { return "" }
        let number = min(round.currentIndex + 1, round.questions.count)
        return "第 \(number) / \(round.questions.count) 题"
    }

    var progress: Double {
        guard let round, !round.questions.isEmpty else { return 0 }
        if round.isFinished { return 1.0 }
        return Double(round.currentIndex + 1) / Double(round.questions.count)
    }

    var canSubmit: Bool { outcome == .pending }
    var canAdvance: Bool { outcome != .pending }
    var isFinished: Bool { round?.isFinished ?? false }

    var outcomeText: String {
        switch outcome {
        case .pending:
            return ""
        case .correct:
            return "答对了"
        case .wrong:
            return "答错了"
        case .revealed:
            return "已查看答案，本题不计入独立答对。"
        }
    }

    var summaryText: String {
        guard let round else { return "" }
        return "独立答对 \(round.correctCount) / \(round.questions.count) 题"
    }

    var hasWrongQuestions: Bool { round?.hasWrongQuestions ?? false }

    var isCurrentDictionarySource: Bool { current?.favorite.isDictionarySource ?? false }
    var currentSourceButtonTitle: String { isCurrentDictionarySource ? "词典详情" : "回原文" }

    @ObservationIgnored private let session: Session
    @ObservationIgnored private let environment: AppEnvironment
    @ObservationIgnored private let onOpenSource: ((Favorite) -> Void)?
    @ObservationIgnored private var recordedIndices: Set<Int> = []

    init(
        session: Session,
        environment: AppEnvironment,
        onOpenSource: ((Favorite) -> Void)? = nil
    ) {
        self.session = session
        self.environment = environment
        self.onOpenSource = onOpenSource
    }

    func openCurrentSource() {
        guard let favorite = current?.favorite else { return }
        onOpenSource?(favorite)
    }

    func start() {
        let serverUrl = session.serverUrl
        let studentId = session.student.id
        let now = environment.nowMs()
        let db = environment.database

        let favorites = (try? db.listFavorites(serverUrl: serverUrl, studentId: studentId)) ?? []
        var articles: [String: ReadingArticle] = [DemoArticle.id: DemoArticle.article]
        let uniqueArticleIds = Set(favorites.map(\.articleId))
        for articleId in uniqueArticleIds where articleId != DemoArticle.id && articleId != Favorite.dictArticleId {
            if let imported = try? db.findArticle(serverUrl: serverUrl, studentId: studentId, articleId: articleId) {
                articles[articleId] = imported.readingArticle
            }
        }

        let creation = ClozeRound.create(favorites: favorites, articles: articles, now: now)
        round = creation.round
        recordedIndices.removeAll()
        resetQuestionUI()

        if favorites.isEmpty {
            emptyMessage = "还没有收藏。请先到阅读页收藏单词或词组。"
        } else if creation.round == nil {
            emptyMessage = "有收藏无法核对原文位置，本轮无法开始。"
        } else {
            emptyMessage = nil
        }

        if creation.skippedCount > 0 {
            skippedNotice = "已跳过 \(creation.skippedCount) 条无法定位原句的生词。"
        } else {
            skippedNotice = nil
        }
    }

    func showHint() {
        guard outcome == .pending else { return }
        isHintShown = true
    }

    func submit() {
        guard outcome == .pending, var currentRound = round, let question = currentRound.current else { return }
        let idx = currentRound.currentIndex
        let result = currentRound.submit(input)
        lastResult = result

        switch result {
        case .empty:
            round = currentRound
            inputError = "请输入单词或词组。"
        case .correct:
            round = currentRound
            inputError = nil
            outcome = .correct
            recordSrsIfNeeded(index: idx, favorite: question.favorite, correct: true)
        case .wrong:
            // Transition underlying ClozeRound state out of .pending so advance() works cleanly
            // while preserving wrongIndices and not incrementing correctCount.
            _ = currentRound.reveal()
            round = currentRound
            inputError = nil
            outcome = .wrong
            recordSrsIfNeeded(index: idx, favorite: question.favorite, correct: false)
        case .ignored:
            round = currentRound
        }
    }

    func reveal() {
        guard outcome == .pending, var currentRound = round, let question = currentRound.current else { return }
        let idx = currentRound.currentIndex
        guard currentRound.reveal() else { return }
        round = currentRound
        inputError = nil
        lastResult = nil
        outcome = .revealed
        recordSrsIfNeeded(index: idx, favorite: question.favorite, correct: false)
    }

    func next() {
        guard var currentRound = round else { return }
        currentRound.advance()
        round = currentRound
        resetQuestionUI()
    }

    func retryWrong() {
        guard let wrongRound = round?.retryWrongRound() else { return }
        round = wrongRound
        emptyMessage = nil
        skippedNotice = nil
        recordedIndices.removeAll()
        resetQuestionUI()
    }

    func restart() {
        start()
    }

    func speak(_ text: String) {
        environment.speech.speak(text)
    }

    private func recordSrsIfNeeded(index: Int, favorite: Favorite, correct: Bool) {
        guard !recordedIndices.contains(index) else { return }
        recordedIndices.insert(index)
        let now = environment.nowMs()
        let newMastery = Srs.nextMastery(current: favorite.masteryLevel, correct: correct)
        let nextReviewAt = Srs.nextReviewAt(mastery: newMastery, now: now)
        try? environment.database.recordReview(
            serverUrl: favorite.serverUrl,
            studentId: favorite.studentId,
            articleId: favorite.articleId,
            startOffset: favorite.startOffset,
            endOffset: favorite.endOffset,
            masteryLevel: newMastery,
            reviewedAt: now,
            nextReviewAt: nextReviewAt
        )
    }

    private func resetQuestionUI() {
        input = ""
        inputError = nil
        lastResult = nil
        outcome = .pending
        isHintShown = false
    }
}
