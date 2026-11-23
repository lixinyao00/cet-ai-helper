import Foundation
import Observation

@MainActor @Observable final class TranslationViewModel {
    static let maxSource = 2000
    static let maxDraft = 3000
    static let demoSource = "随着数字经济的快速发展，掌握基本的编程能力对高校毕业生越来越重要。许多企业因此更愿意录用那些既能独立思考又能团队协作的年轻人。"

    var source: String = "" {
        didSet {
            guard !isSuppressingUpdates, source != oldValue else { return }
            validationMessage = nil
            inlineHighlight = nil
            environment.drafts.saveTranslation(owner: session.owner, source: source, draft: draft, immediate: false)
        }
    }

    var draft: String = "" {
        didSet {
            guard !isSuppressingUpdates, draft != oldValue else { return }
            validationMessage = nil
            inlineHighlight = nil
            environment.drafts.saveTranslation(owner: session.owner, source: source, draft: draft, immediate: false)
        }
    }

    var sourceCountText: String { "\(source.utf16.count) / \(Self.maxSource)" }
    var draftWordBadge: String { TextMetrics.translationWordBadge(TextMetrics.englishWordCount(draft)) }
    private(set) var validationMessage: String? = nil
    let hint: PracticeRequest<TranslationHint>
    private(set) var activeHintType: TranslationHintType? = nil
    let review: PracticeRequest<TranslationFeedback>
    var inlineHighlight: InlineHighlight? = nil
    var quoteSnapshot: QuoteSnapshot? = nil
    var confirmation: ConfirmationRequest? = nil
    private(set) var history: [PracticeRecord] = []
    var isHistoryPresented: Bool = false
    var selectedRecord: PracticeRecord? = nil

    var hasInput: Bool { !source.isEmpty || !draft.isEmpty }
    var canAskTutor: Bool { review.value != nil && !review.isPending }

    @ObservationIgnored private let session: Session
    @ObservationIgnored private let environment: AppEnvironment
    @ObservationIgnored private var activeSource: String = ""
    @ObservationIgnored private var isSuppressingUpdates = false
    @ObservationIgnored var onPushAnswer: (() -> Void)?
    @ObservationIgnored var onAskTutor: ((TutorContext) -> Void)?

    init(
        session: Session,
        environment: AppEnvironment,
        onUnauthorized: @escaping @MainActor () -> Void
    ) {
        self.session = session
        self.environment = environment

        var hintTypeProvider: () -> TranslationHintType = { .keyword }
        self.hint = PracticeRequest<TranslationHint>(
            errorMapper: { error in
                ApiErrorFormatter.translationHintFormat(error, typeTitle: hintTypeProvider().title)
            },
            onUnauthorized: onUnauthorized
        )
        self.review = PracticeRequest<TranslationFeedback>(
            errorMapper: ApiErrorFormatter.translationReviewFormat,
            onUnauthorized: onUnauthorized
        )

        hintTypeProvider = { [weak self] in
            self?.activeHintType ?? .keyword
        }

        let saved = environment.drafts.translation(for: session.owner)
        isSuppressingUpdates = true
        self.source = saved.source
        self.draft = saved.draft
        isSuppressingUpdates = false
    }

    func useDemoSource() {
        let apply: @MainActor () -> Void = { [weak self] in
            self?.replaceInputs(newSource: Self.demoSource, newDraft: "")
        }
        if hasInput {
            confirmation = ConfirmationRequest(
                title: "替换本次练习",
                message: "替换中文原文并清空当前英文草稿。",
                confirmTitle: "替换",
                isDestructive: true,
                onConfirm: apply
            )
        } else {
            apply()
        }
    }

    func clearSource() {
        guard hasInput else { return }
        confirmation = ConfirmationRequest(
            title: "清空本练习",
            message: "清空后会删除本机保存的本次练习输入。",
            confirmTitle: "清空",
            isDestructive: true,
            onConfirm: { [weak self] in
                self?.replaceInputs(newSource: "", newDraft: "")
            }
        )
    }

    func clearDraft() {
        guard !draft.isEmpty else { return }
        confirmation = ConfirmationRequest(
            title: "清空英文草稿",
            message: "清空后会删除当前英文草稿。",
            confirmTitle: "清空",
            isDestructive: true,
            onConfirm: { [weak self] in
                guard let self else { return }
                self.review.reset()
                self.inlineHighlight = nil
                self.quoteSnapshot = nil
                self.validationMessage = nil
                self.isSuppressingUpdates = true
                self.draft = ""
                self.isSuppressingUpdates = false
                self.environment.drafts.saveTranslation(
                    owner: self.session.owner,
                    source: self.source,
                    draft: "",
                    immediate: true
                )
            }
        )
    }

    /// 校验 checkSource 通过后 push .translationAnswer，否则写 validationMessage。
    func startAnswering() {
        let corrected = TextMetrics.trimSpace(source)
        if corrected.isEmpty {
            validationMessage = "请输入中文原文。"
            return
        }
        if corrected.utf16.count > Self.maxSource {
            validationMessage = "中文原文最多 \(Self.maxSource) 个字符，请精简后再试。"
            return
        }
        validationMessage = nil
        if activeSource != corrected {
            resetResponses()
        }
        activeSource = corrected
        onPushAnswer?()
    }

    func requestHint(_ type: TranslationHintType) {
        let sourceToUse = activeSource.isEmpty ? TextMetrics.trimSpace(source) : activeSource
        guard !sourceToUse.isEmpty else {
            validationMessage = "请输入中文原文。"
            return
        }
        activeSource = sourceToUse
        activeHintType = type
        let activeSession = session
        let api = environment.api
        hint.start {
            try await api.translationHint(activeSession, sourceText: sourceToUse, type: type)
        }
    }

    func submitReview() {
        let sourceToUse = activeSource.isEmpty ? TextMetrics.trimSpace(source) : activeSource
        if sourceToUse.isEmpty {
            validationMessage = "请输入中文原文。"
            return
        }
        if sourceToUse.utf16.count > Self.maxSource {
            validationMessage = "中文原文最多 \(Self.maxSource) 个字符，请精简后再试。"
            return
        }
        let submittedDraft = TextMetrics.trimSpace(draft)
        if submittedDraft.isEmpty {
            validationMessage = "请输入你的英文译文。"
            return
        }
        if submittedDraft.utf16.count > Self.maxDraft {
            validationMessage = "英文译文最多 \(Self.maxDraft) 个字符，请精简后再试。"
            return
        }
        validationMessage = nil
        activeSource = sourceToUse
        flushDraft()

        let activeSession = session
        let api = environment.api
        review.start({
            try await api.reviewTranslation(
                activeSession,
                sourceText: sourceToUse,
                studentTranslation: submittedDraft
            )
        }, onComplete: { [weak self] feedback in
            self?.savePracticeRecord(feedback)
        })
    }

    /// 收起键盘由 View 负责；VM 设置 inlineHighlight + quoteSnapshot。
    func locate(_ issue: ReviewIssue) {
        let field: PracticeField = issue.target == .source ? .translationSource : .translationDraft
        let liveText = issue.target == .source ? source : draft
        let liveMatches = ReviewQuote.findMatches(in: liveText, quote: issue.quote)
        if !liveMatches.isEmpty {
            inlineHighlight = InlineHighlight(field: field, ranges: liveMatches, token: UUID())
        } else {
            inlineHighlight = nil
        }

        if let feedback = review.value {
            let title = issue.target == .source ? "本次提交的中文原文" : "本次提交的英文译文"
            let snapshotText = issue.target == .source ? feedback.sourceText : feedback.studentTranslation
            quoteSnapshot = QuoteSnapshot(
                id: UUID(),
                title: title,
                text: snapshotText,
                highlights: issue.matches
            )
        }
    }

    func presentHistory() {
        loadHistory()
        isHistoryPresented = true
    }

    func feedback(for record: PracticeRecord) -> TranslationFeedback? {
        try? ApiClient.parseTranslationReview(json: record.reviewJson)
    }

    func restore(from record: PracticeRecord) {
        resetResponses()
        inlineHighlight = nil
        quoteSnapshot = nil
        validationMessage = nil

        isSuppressingUpdates = true
        source = record.promptOrSource
        draft = record.draft
        isSuppressingUpdates = false
        activeSource = record.promptOrSource

        if let parsed = feedback(for: record) {
            review.setValue(parsed)
        }
        environment.drafts.saveTranslation(owner: session.owner, source: source, draft: draft, immediate: true)
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

    func applyImportedSource(_ importedSource: String) {
        replaceInputs(newSource: importedSource, newDraft: "")
        activeSource = importedSource
    }

    func buildTutorContext() -> TutorContext? {
        guard let feedback = review.value, !review.isPending else { return nil }
        let trimmedDraft = TextMetrics.trimSpace(draft)
        let focusRaw = trimmedDraft.isEmpty ? feedback.studentTranslation : trimmedDraft
        let focusText = TutorContext.clampUTF16(focusRaw, maxLength: 2000)
        let referenceRaw = TextMetrics.trimSpace(source).isEmpty ? feedback.sourceText : source
        let referenceText = TutorContext.clampUTF16(referenceRaw, maxLength: 6000)

        var parts: [String] = []
        if feedback.hasScore {
            var scoreLine = "预估分：\(feedback.estimatedScore)/\(feedback.scoreMax)分"
            if let band = feedback.scoreBand, !band.isEmpty {
                scoreLine += "（\(band)）"
            }
            parts.append(scoreLine)
        }
        parts.append("总评：\(feedback.overallFeedback)")
        if let ref = feedback.referenceTranslation, !ref.isEmpty {
            parts.append("参考译文：\(ref)")
        }
        for issue in feedback.issues {
            var line = "- \(issue.type.label)「\(issue.quote)」：\(issue.detail)"
            if !issue.suggestion.isEmpty {
                line += " 建议：\(issue.suggestion)"
            }
            parts.append(line)
        }
        let extraNote = TutorContext.clampUTF16(parts.joined(separator: "\n"), maxLength: 4000)
        return TutorContext(
            type: .translation,
            title: "翻译练习",
            focusText: focusText,
            referenceText: referenceText,
            extraNote: extraNote
        )
    }

    func askTutor() {
        guard let ctx = buildTutorContext() else { return }
        onAskTutor?(ctx)
    }

    /// 切 L1 Tab 时清空内存 hint/review/highlight，保留 DraftStore 落盘草稿。
    func resetTransientState() {
        resetResponses()
        inlineHighlight = nil
        quoteSnapshot = nil
        validationMessage = nil
        confirmation = nil
        isHistoryPresented = false
        selectedRecord = nil
    }

    // MARK: - Private Helpers

    private func replaceInputs(newSource: String, newDraft: String) {
        resetResponses()
        inlineHighlight = nil
        quoteSnapshot = nil
        validationMessage = nil
        activeSource = ""

        isSuppressingUpdates = true
        source = newSource
        draft = newDraft
        isSuppressingUpdates = false

        environment.drafts.saveTranslation(owner: session.owner, source: newSource, draft: newDraft, immediate: true)
    }

    private func resetResponses() {
        activeHintType = nil
        hint.reset()
        review.reset()
    }

    private func loadHistory() {
        let key = PracticeRecord.ownerKey(session.owner)
        history = (try? environment.database.listPracticeRecords(ownerKey: key, type: .translation, limit: 20)) ?? []
    }

    private func savePracticeRecord(_ feedback: TranslationFeedback) {
        guard !feedback.rawJson.isEmpty else { return }
        _ = try? environment.database.insertPracticeRecord(
            ownerKey: PracticeRecord.ownerKey(session.owner),
            type: .translation,
            promptOrSource: feedback.sourceText,
            draft: feedback.studentTranslation,
            reviewJson: feedback.rawJson,
            estimatedScore: feedback.estimatedScore,
            createdAt: environment.nowMs()
        )
    }
}
