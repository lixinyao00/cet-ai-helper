import Foundation
import Observation

/// → §1.3 防乱序。Views 只读；start/reset 仅由 VM 调用。
@MainActor @Observable final class PracticeRequest<Value> {
    private(set) var value: Value? = nil
    private(set) var errorMessage: String? = nil
    private(set) var canRetry: Bool = false
    private(set) var isPending: Bool = false
    var hasValue: Bool { value != nil }

    @ObservationIgnored private var revision: Int = 0
    @ObservationIgnored private var activeTask: Task<Void, Never>?
    @ObservationIgnored private let errorMapper: (Error) -> (message: String, canRetry: Bool)
    @ObservationIgnored private let onUnauthorized: (@MainActor () -> Void)?
    @ObservationIgnored private let onError: (@MainActor (Error) -> Void)?

    init(
        errorMapper: @escaping (Error) -> (message: String, canRetry: Bool) = ApiErrorFormatter.defaultFormat,
        onUnauthorized: (@MainActor () -> Void)? = nil,
        onError: (@MainActor (Error) -> Void)? = nil
    ) {
        self.errorMapper = errorMapper
        self.onUnauthorized = onUnauthorized
        self.onError = onError
    }

    func start(_ operation: @escaping () async throws -> Value, onComplete: ((Value) -> Void)? = nil) {
        reset()
        let current = revision
        isPending = true
        activeTask = Task { @MainActor [weak self] in
            do {
                let result = try await operation()
                guard let self, self.revision == current else { return }
                self.activeTask = nil
                self.isPending = false
                self.value = result
                self.errorMessage = nil
                self.canRetry = false
                onComplete?(result)
            } catch is CancellationError {
                guard let self, self.revision == current else { return }
                self.activeTask = nil
                self.isPending = false
            } catch let apiError as ApiError where apiError == .cancelled {
                guard let self, self.revision == current else { return }
                self.activeTask = nil
                self.isPending = false
            } catch {
                guard let self, self.revision == current else { return }
                self.activeTask = nil
                self.isPending = false
                if let apiError = error as? ApiError, apiError.isUnauthorized {
                    self.onUnauthorized?()
                    return
                }
                let formatted = self.errorMapper(error)
                self.errorMessage = formatted.message
                self.canRetry = formatted.canRetry
                self.onError?(error)
            }
        }
    }

    func reset() {
        revision += 1
        activeTask?.cancel()
        activeTask = nil
        isPending = false
        value = nil
        errorMessage = nil
        canRetry = false
    }

    func setValue(_ restored: Value?) {
        reset()
        value = restored
    }
}
