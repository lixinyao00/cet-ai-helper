import Foundation
import Observation

@MainActor @Observable final class TutorViewModel {
    private static let deltaThrottleMs: UInt64 = 50
    private static let maxHistoryMessages = 20

    private(set) var context: TutorContext = .free
    private(set) var messages: [TutorMessage] = []
    private(set) var streamingText: String = ""
    private(set) var followUps: [String] = []
    private(set) var isGenerating: Bool = false
    private(set) var streamMode: String = ""
    private(set) var errorMessage: String? = nil
    var draftInput: String = ""
    var confirmation: ConfirmationRequest? = nil

    var quickPrompts: [String] { context.quickPrompts() }
    var canSend: Bool { !isGenerating && !TextMetrics.trimSpace(draftInput).isEmpty }
    var canClear: Bool { !messages.isEmpty || isGenerating || !streamingText.isEmpty }

    @ObservationIgnored private let session: Session
    @ObservationIgnored private let environment: AppEnvironment
    @ObservationIgnored private let onUnauthorized: @MainActor () -> Void
    @ObservationIgnored private var activeHandle: TutorStreamHandle?
    @ObservationIgnored private var streamRevision = 0
    @ObservationIgnored private var pendingBuffer = ""
    @ObservationIgnored private var lastFlushUptimeNs: UInt64 = 0
    @ObservationIgnored private var throttleTask: Task<Void, Never>?

    init(
        session: Session,
        environment: AppEnvironment,
        onUnauthorized: @escaping @MainActor () -> Void
    ) {
        self.session = session
        self.environment = environment
        self.onUnauthorized = onUnauthorized
        loadMessages(for: .free)
    }

    /// 切换上下文，取消在途流并加载新 sessionKey 的历史消息。
    func attach(_ newContext: TutorContext) {
        if context == newContext && (isGenerating || !messages.isEmpty) {
            return
        }
        if isGenerating {
            stop()
        }
        context = newContext
        streamingText = ""
        pendingBuffer = ""
        followUps = []
        streamMode = ""
        errorMessage = nil
        loadMessages(for: newContext)
    }

    /// 快捷调用 attach(.free)。
    func clearContext() {
        attach(.free)
    }

    /// prompt 为 nil 时发送 draftInput 并清空输入框。
    func send(_ prompt: String? = nil) {
        let rawText = prompt ?? draftInput
        let trimmed = TextMetrics.trimSpace(rawText)
        guard !trimmed.isEmpty, !isGenerating else { return }

        if prompt == nil {
            draftInput = ""
        }

        let activeContext = context
        let now = environment.nowMs()
        let userMessage = appendAndPersist(
            role: .user,
            content: trimmed,
            context: activeContext,
            mode: "",
            createdAt: now
        )
        _ = userMessage

        let payloadMessages = Array(messages.suffix(Self.maxHistoryMessages))
        isGenerating = true
        streamingText = ""
        pendingBuffer = ""
        followUps = []
        streamMode = ""
        errorMessage = nil

        streamRevision += 1
        let rev = streamRevision

        activeHandle = environment.api.streamTutorChat(
            session,
            messages: payloadMessages,
            context: activeContext,
            onEvent: { [weak self] event in
                guard let self, rev == self.streamRevision else { return }
                switch event {
                case .meta(let mode, _):
                    guard self.isGenerating else { return }
                    self.streamMode = mode

                case .delta(let text):
                    guard self.isGenerating else { return }
                    self.pendingBuffer.append(text)
                    self.scheduleThrottledFlush(revision: rev)

                case .done(let doneFollowUps):
                    self.cancelThrottle()
                    let finalContent = self.pendingBuffer
                    let resolvedMode = self.streamMode
                    self.isGenerating = false
                    self.activeHandle = nil
                    self.pendingBuffer = ""
                    self.streamingText = ""
                    if !finalContent.isEmpty {
                        _ = self.appendAndPersist(
                            role: .assistant,
                            content: finalContent,
                            context: activeContext,
                            mode: resolvedMode,
                            createdAt: self.environment.nowMs()
                        )
                    }
                    self.followUps = doneFollowUps

                case .error(let code, let message):
                    let apiError = ApiError.http(status: 200, code: code, message: message)
                    self.handleFailure(apiError, revision: rev, context: activeContext)
                }
            },
            onError: { [weak self] error in
                guard let self, rev == self.streamRevision else { return }
                self.handleFailure(error, revision: rev, context: activeContext)
            }
        )
    }

    /// 主动停止流式生成：递增 streamRevision、取消流并将已有非空 streamingText 落库为 assistant 消息。
    func stop() {
        streamRevision += 1
        cancelThrottle()
        activeHandle?.cancel()
        activeHandle = nil

        isGenerating = false
        let partialText = pendingBuffer.isEmpty ? streamingText : pendingBuffer
        let resolvedMode = streamMode
        pendingBuffer = ""
        streamingText = ""

        if !partialText.isEmpty {
            _ = appendAndPersist(
                role: .assistant,
                content: partialText,
                context: context,
                mode: resolvedMode,
                createdAt: environment.nowMs()
            )
        }
    }

    /// 弹出二次确认框（"清空当前会话"）。
    func requestClearSession() {
        guard canClear else { return }
        confirmation = ConfirmationRequest(
            title: "清空当前会话",
            message: "确定要清空当前上下文的对话记录吗？",
            confirmTitle: "清空",
            isDestructive: true,
            onConfirm: { [weak self] in
                self?.clearSession()
            }
        )
    }

    func clearSession() {
        streamRevision += 1
        cancelThrottle()
        activeHandle?.cancel()
        activeHandle = nil

        isGenerating = false
        pendingBuffer = ""
        streamingText = ""
        messages = []
        followUps = []
        streamMode = ""
        errorMessage = nil

        try? environment.database.clearTutorSession(
            serverUrl: session.serverUrl,
            studentId: session.student.id,
            sessionKey: context.sessionKey()
        )
    }

    func cancelInFlight() {
        streamRevision += 1
        cancelThrottle()
        activeHandle?.cancel()
        activeHandle = nil
        isGenerating = false
        pendingBuffer = ""
        streamingText = ""
    }

    func reset() {
        cancelInFlight()
        context = .free
        messages = []
        followUps = []
        streamMode = ""
        errorMessage = nil
        draftInput = ""
        confirmation = nil
    }

    // MARK: - Private Helpers

    private func loadMessages(for targetContext: TutorContext) {
        messages = (try? environment.database.listTutorMessages(
            serverUrl: session.serverUrl,
            studentId: session.student.id,
            sessionKey: targetContext.sessionKey()
        )) ?? []
    }

    @discardableResult
    private func appendAndPersist(
        role: TutorChatRole,
        content: String,
        context activeContext: TutorContext,
        mode: String,
        createdAt: Int64
    ) -> TutorMessage {
        let draft = TutorMessage(
            id: 0,
            serverUrl: session.serverUrl,
            studentId: session.student.id,
            sessionKey: activeContext.sessionKey(),
            role: role,
            content: content,
            contextType: activeContext.type.rawValue,
            contextSummary: activeContext.summary(),
            mode: mode,
            createdAt: createdAt
        )
        let persistedId = (try? environment.database.insertTutorMessage(draft))
            ?? Int64(-(messages.count + 1))
        let stored = TutorMessage(
            id: persistedId,
            serverUrl: draft.serverUrl,
            studentId: draft.studentId,
            sessionKey: draft.sessionKey,
            role: draft.role,
            content: draft.content,
            contextType: draft.contextType,
            contextSummary: draft.contextSummary,
            mode: draft.mode,
            createdAt: draft.createdAt
        )
        messages.append(stored)
        return stored
    }

    private func handleFailure(_ error: Error, revision rev: Int, context activeContext: TutorContext) {
        guard rev == streamRevision else { return }
        cancelThrottle()
        let partialText = pendingBuffer.isEmpty ? streamingText : pendingBuffer
        let resolvedMode = streamMode
        isGenerating = false
        activeHandle = nil
        pendingBuffer = ""
        streamingText = ""

        if let apiError = error as? ApiError {
            if apiError == .cancelled {
                return
            }
            if apiError.isUnauthorized {
                onUnauthorized()
                return
            }
        }

        if !partialText.isEmpty {
            _ = appendAndPersist(
                role: .assistant,
                content: partialText,
                context: activeContext,
                mode: resolvedMode,
                createdAt: environment.nowMs()
            )
        }
        errorMessage = ApiErrorFormatter.generalMessage(error)
    }

    private func scheduleThrottledFlush(revision rev: Int) {
        let nowNs = DispatchTime.now().uptimeNanoseconds
        let throttleNs = Self.deltaThrottleMs * 1_000_000
        let elapsed = nowNs >= lastFlushUptimeNs ? (nowNs - lastFlushUptimeNs) : throttleNs

        if elapsed >= throttleNs {
            cancelThrottle()
            lastFlushUptimeNs = nowNs
            streamingText = pendingBuffer
            return
        }

        guard throttleTask == nil else { return }
        let remainingNs = throttleNs - elapsed
        throttleTask = Task { @MainActor [weak self] in
            do {
                try await Task.sleep(nanoseconds: remainingNs)
            } catch {
                return
            }
            guard let self, rev == self.streamRevision, self.isGenerating else { return }
            self.throttleTask = nil
            self.lastFlushUptimeNs = DispatchTime.now().uptimeNanoseconds
            self.streamingText = self.pendingBuffer
        }
    }

    private func cancelThrottle() {
        throttleTask?.cancel()
        throttleTask = nil
    }
}
