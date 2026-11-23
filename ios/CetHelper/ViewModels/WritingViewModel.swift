import Foundation
import Observation

@MainActor @Observable final class WritingAttempt {
    var input: String = "" {
        didSet {
            if input != oldValue {
                validationMessage = nil
            }
        }
    }
    let request: PracticeRequest<WritingAssessment>
    private(set) var validationMessage: String? = nil

    var canSubmit: Bool {
        if isBlocked() { return false }
        let trimmed = TextMetrics.trimSpace(input)
        return !pendingAnswer(trimmed)
    }

    var assessment: WritingAssessment? { request.value }

    @ObservationIgnored private var submittedAnswer: String?
    @ObservationIgnored private let isBlocked: () -> Bool

    init(
        onUnauthorized: @escaping @MainActor () -> Void,
        isBlocked: @escaping () -> Bool = { false }
    ) {
        self.isBlocked = isBlocked
        self.request = PracticeRequest<WritingAssessment>(
            errorMapper: ApiErrorFormatter.writingFollowUpFormat,
            onUnauthorized: onUnauthorized
        )
    }

    func pendingAnswer(_ answer: String) -> Bool {
        request.isPending && answer == submittedAnswer
    }

    func validateAndPrepare() -> String? {
        guard !isBlocked() else { return nil }
        let trimmed = TextMetrics.trimSpace(input)
        if pendingAnswer(trimmed) {
            return nil
        }
        if trimmed.isEmpty {
            validationMessage = "请输入英文作答。"
            return nil
        }
        if trimmed.utf16.count > WritingViewModel.maxAnswer {
            validationMessage = "英文作答最多 \(WritingViewModel.maxAnswer) 个字符，请精简后再试。"
            return nil
        }
        validationMessage = nil
        submittedAnswer = trimmed
        return trimmed
    }

    func reset() {
        request.reset()
        input = ""
        submittedAnswer = nil
        validationMessage = nil
    }
}

/// 挂在 issues[exercise.issueIndex] 卡片下方。→ §3.5
@MainActor @Observable final class WritingFollowUp {
    let issue: ReviewIssue
    let exercise: WritingExercise
    let exerciseAttempt: WritingAttempt
    let taskRequest: PracticeRequest<WritingTask>
    private(set) var retestTask: WritingTask? = nil
    let retestAttempt: WritingAttempt

    var canRequestRetest: Bool { !taskRequest.isPending }
    var canSubmitRetest: Bool { retestTask != nil && !taskRequest.isPending && retestAttempt.canSubmit }

    @ObservationIgnored private let session: Session
    @ObservationIgnored private let api: ApiClient

    init(
        issue: ReviewIssue,
        exercise: WritingExercise,
        session: Session,
        api: ApiClient,
        onUnauthorized: @escaping @MainActor () -> Void
    ) {
        self.issue = issue
        self.exercise = exercise
        self.session = session
        self.api = api
        self.exerciseAttempt = WritingAttempt(onUnauthorized: onUnauthorized)
        self.taskRequest = PracticeRequest<WritingTask>(
            errorMapper: ApiErrorFormatter.writingFollowUpFormat,
            onUnauthorized: onUnauthorized
        )
        var retestBlockedProvider: () -> Bool = { true }
        self.retestAttempt = WritingAttempt(
            onUnauthorized: onUnauthorized,
            isBlocked: { retestBlockedProvider() }
        )
        retestBlockedProvider = { [weak self] in
            guard let self else { return true }
            return self.retestTask == nil || self.taskRequest.isPending
        }
    }

    func submitExercise() {
        guard let answer = exerciseAttempt.validateAndPrepare() else { return }
        let activeSession = session
        let currentApi = api
        let currentIssue = issue
        let currentExercise = exercise
        exerciseAttempt.request.start {
            try await currentApi.attemptWritingExercise(
                activeSession,
                issue: currentIssue,
                exercise: currentExercise,
                answer: answer
            )
        }
    }

    func requestRetest() {
        guard canRequestRetest else { return }
        let activeSession = session
        let currentApi = api
        let currentIssue = issue
        let currentExercise = exercise
        taskRequest.start({
            try await currentApi.requestWritingRetest(
                activeSession,
                issue: currentIssue,
                exercise: currentExercise
            )
        }, onComplete: { [weak self] task in
            guard let self else { return }
            self.retestAttempt.reset()
            self.retestTask = task
        })
    }

    func submitRetest() {
        guard let task = retestTask, !taskRequest.isPending else { return }
        guard let answer = retestAttempt.validateAndPrepare() else { return }
        let activeSession = session
        let currentApi = api
        let currentIssue = issue
        let currentExercise = exercise
        retestAttempt.request.start {
            try await currentApi.attemptWritingRetest(
                activeSession,
                issue: currentIssue,
                exercise: currentExercise,
                task: task,
                answer: answer
            )
        }
    }

    func cancel() {
        exerciseAttempt.reset()
        retestAttempt.reset()
        taskRequest.reset()
        retestTask = nil
    }
}

@MainActor @Observable final class WritingViewModel {
    static let maxPrompt = 2000
    static let maxEssay = 6000
    static let maxAnswer = 2000

    static let demoPrompt = "For this part, you are allowed 30 minutes to write an essay on the benefits of volunteering for college students. Give an example to support your view. You should write at least 120 words but no more than 180 words."
    static let demoEssay = """
    Many students prefers to spend their weekends relaxing, but volunteering is another way to use that time well. It gives college students a chance to help others while putting what they learn in class into practice.

    Last semester, our class organize a weekend cleanup near the campus. At first, we all wanted to work on the same street, and no one had brought enough bags. We divided the area into smaller sections and asked a nearby shop for extra bags. By the end of the morning, we had learned how to share tasks and solve a problem together.

    A student does not have to volunteer every weekend to benefit. Even a few hours a month can build confidence and make the local community a better place to live.
    """

    var prompt: String = "" {
        didSet {
            guard !isSuppressingUpdates, prompt != oldValue else { return }
            validationMessage = nil
            inlineHighlight = nil
            environment.drafts.saveWriting(owner: session.owner, prompt: prompt, essay: essay, immediate: false)
        }
    }

    var essay: String = "" {
        didSet {
            guard !isSuppressingUpdates, essay != oldValue else { return }
            validationMessage = nil
            inlineHighlight = nil
            environment.drafts.saveWriting(owner: session.owner, prompt: prompt, essay: essay, immediate: false)
        }
    }

    var wordCount: Int { TextMetrics.englishWordCount(essay) }
    var wordBadge: String { TextMetrics.writingWordBadge(wordCount) }
    private(set) var validationMessage: String? = nil
    let review: PracticeRequest<WritingFeedback>
    private(set) var followUp: WritingFollowUp? = nil
    var inlineHighlight: InlineHighlight? = nil
    var quoteSnapshot: QuoteSnapshot? = nil
    var confirmation: ConfirmationRequest? = nil
    private(set) var history: [PracticeRecord] = []
    var isHistoryPresented: Bool = false
    var selectedRecord: PracticeRecord? = nil

    var hasInput: Bool { !prompt.isEmpty || !essay.isEmpty }
    var canAskTutor: Bool { review.value != nil && !review.isPending }

    @ObservationIgnored var onAskTutor: ((TutorContext) -> Void)?
    @ObservationIgnored private let session: Session
    @ObservationIgnored private let environment: AppEnvironment
    @ObservationIgnored private let onUnauthorized: @MainActor () -> Void
    @ObservationIgnored private var isSuppressingUpdates = false

    init(
        session: Session,
        environment: AppEnvironment,
        onUnauthorized: @escaping @MainActor () -> Void
    ) {
        self.session = session
        self.environment = environment
        self.onUnauthorized = onUnauthorized
        self.review = PracticeRequest<WritingFeedback>(
            errorMapper: ApiErrorFormatter.writingReviewFormat,
            onUnauthorized: onUnauthorized
        )

        let saved = environment.drafts.writing(for: session.owner)
        isSuppressingUpdates = true
        self.prompt = saved.prompt
        self.essay = saved.essay
        isSuppressingUpdates = false
    }

    func useDemo() {
        let apply: @MainActor () -> Void = { [weak self] in
            self?.replaceInputs(newPrompt: Self.demoPrompt, newEssay: Self.demoEssay)
        }
        if hasInput {
            confirmation = ConfirmationRequest(
                title: "替换本次练习",
                message: "替换后会覆盖本机保存的题目和草稿。",
                confirmTitle: "替换",
                isDestructive: true,
                onConfirm: apply
            )
        } else {
            apply()
        }
    }

    func clearAll() {
        guard hasInput else { return }
        confirmation = ConfirmationRequest(
            title: "清空本练习",
            message: "清空后会删除本机保存的本次练习输入。",
            confirmTitle: "清空",
            isDestructive: true,
            onConfirm: { [weak self] in
                self?.replaceInputs(newPrompt: "", newEssay: "")
            }
        )
    }

    func submitReview() {
        let trimmedPrompt = TextMetrics.trimSpace(prompt)
        if trimmedPrompt.isEmpty {
            validationMessage = "请输入作文题目。"
            return
        }
        if trimmedPrompt.utf16.count > Self.maxPrompt {
            validationMessage = "作文题目最多 \(Self.maxPrompt) 个字符，请精简后再试。"
            return
        }
        let trimmedEssay = TextMetrics.trimSpace(essay)
        if trimmedEssay.isEmpty {
            validationMessage = "请输入英文作文。"
            return
        }
        if trimmedEssay.utf16.count > Self.maxEssay {
            validationMessage = "英文作文最多 \(Self.maxEssay) 个字符，请精简后再试。"
            return
        }
        validationMessage = nil
        flushDraft()

        let activeSession = session
        let api = environment.api
        review.start({
            try await api.reviewWriting(activeSession, prompt: trimmedPrompt, essay: trimmedEssay)
        }, onComplete: { [weak self] feedback in
            guard let self else { return }
            self.applyFollowUp(from: feedback)
            self.savePracticeRecord(feedback)
        })
    }

    func locate(_ issue: ReviewIssue) {
        let liveMatches = ReviewQuote.findMatches(in: essay, quote: issue.quote)
        if !liveMatches.isEmpty {
            inlineHighlight = InlineHighlight(field: .writingEssay, ranges: liveMatches, token: UUID())
        } else {
            inlineHighlight = nil
        }

        if let feedback = review.value {
            quoteSnapshot = QuoteSnapshot(
                id: UUID(),
                title: "本次批改的作文原文",
                text: feedback.essay,
                highlights: issue.matches
            )
        }
    }

    func presentHistory() {
        loadHistory()
        isHistoryPresented = true
    }

    func feedback(for record: PracticeRecord) -> WritingFeedback? {
        try? ApiClient.parseWritingReview(json: record.reviewJson)
    }

    func restore(from record: PracticeRecord) {
        cancelRequests()
        inlineHighlight = nil
        quoteSnapshot = nil
        validationMessage = nil

        isSuppressingUpdates = true
        prompt = record.promptOrSource
        essay = record.draft
        isSuppressingUpdates = false

        if let parsed = feedback(for: record) {
            review.setValue(parsed)
            applyFollowUp(from: parsed)
        }
        environment.drafts.saveWriting(owner: session.owner, prompt: prompt, essay: essay, immediate: true)
        selectedRecord = nil
        isHistoryPresented = false
    }

    func deleteRecord(_ record: PracticeRecord) {
        try? environment.database.deletePracticeRecord(id: record.id)
        if selectedRecord?.id == record.id {
            selectedRecord = nil
        }
        loadHistory()
    }

    func flushDraft() {
        environment.drafts.flush()
    }

    func buildTutorContext() -> TutorContext? {
        guard let feedback = review.value, !review.isPending else { return nil }
        let trimmedPrompt = TextMetrics.trimSpace(prompt)
        let promptText = trimmedPrompt.isEmpty ? feedback.prompt : trimmedPrompt
        let title = promptText.isEmpty ? "写作练习" : String(promptText.prefix(40))
        let trimmedEssay = TextMetrics.trimSpace(essay)
        let focusRaw = trimmedEssay.isEmpty ? feedback.essay : trimmedEssay
        let focusText = TutorContext.clampUTF16(focusRaw, maxLength: 2000)
        let referenceText = TutorContext.clampUTF16(promptText, maxLength: 6000)

        var parts: [String] = []
        if feedback.hasScore {
            var scoreLine = "预估分：\(feedback.estimatedScore)/\(feedback.scoreMax)分"
            if let band = feedback.scoreBand, !band.isEmpty {
                scoreLine += "（\(band)）"
            }
            parts.append(scoreLine)
        }
        parts.append("总评：\(feedback.overallFeedback)")
        for issue in feedback.issues {
            var line = "- \(issue.type.label)「\(issue.quote)」：\(issue.detail)"
            if !issue.suggestion.isEmpty {
                line += " 建议：\(issue.suggestion)"
            }
            parts.append(line)
        }
        let extraNote = TutorContext.clampUTF16(parts.joined(separator: "\n"), maxLength: 4000)
        return TutorContext(
            type: .writing,
            title: title,
            focusText: focusText,
            referenceText: referenceText,
            extraNote: extraNote
        )
    }

    func askTutor() {
        guard let ctx = buildTutorContext() else { return }
        onAskTutor?(ctx)
    }

    /// 切 L1 Tab 时清空内存 review/followUp/highlight，保留 DraftStore 落盘草稿。
    func resetTransientState() {
        cancelRequests()
        inlineHighlight = nil
        quoteSnapshot = nil
        validationMessage = nil
        confirmation = nil
        isHistoryPresented = false
        selectedRecord = nil
    }

    /// 供测试或恢复场景挂载/重置 followUp。
    func applyFollowUp(from feedback: WritingFeedback) {
        followUp?.cancel()
        followUp = nil
        guard let exercise = feedback.exercise,
              exercise.issueIndex >= 0,
              exercise.issueIndex < feedback.issues.count else {
            return
        }
        followUp = WritingFollowUp(
            issue: feedback.issues[exercise.issueIndex],
            exercise: exercise,
            session: session,
            api: environment.api,
            onUnauthorized: onUnauthorized
        )
    }

    // MARK: - Private Helpers

    private func replaceInputs(newPrompt: String, newEssay: String) {
        cancelRequests()
        inlineHighlight = nil
        quoteSnapshot = nil
        validationMessage = nil

        isSuppressingUpdates = true
        prompt = newPrompt
        essay = newEssay
        isSuppressingUpdates = false

        environment.drafts.saveWriting(owner: session.owner, prompt: newPrompt, essay: newEssay, immediate: true)
    }

    private func cancelRequests() {
        review.reset()
        followUp?.cancel()
        followUp = nil
    }

    private func loadHistory() {
        let key = PracticeRecord.ownerKey(session.owner)
        history = (try? environment.database.listPracticeRecords(ownerKey: key, type: .writing, limit: 20)) ?? []
    }

    private func savePracticeRecord(_ feedback: WritingFeedback) {
        guard !feedback.rawJson.isEmpty else { return }
        _ = try? environment.database.insertPracticeRecord(
            ownerKey: PracticeRecord.ownerKey(session.owner),
            type: .writing,
            promptOrSource: feedback.prompt,
            draft: feedback.essay,
            reviewJson: feedback.rawJson,
            estimatedScore: feedback.estimatedScore,
            createdAt: environment.nowMs()
        )
    }
}
