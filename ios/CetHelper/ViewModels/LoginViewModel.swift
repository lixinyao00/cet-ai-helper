import Foundation
import Observation

@MainActor @Observable final class LoginViewModel {
    private static let usernameRegex = try! NSRegularExpression(
        pattern: "^[A-Za-z][A-Za-z0-9_]{2,31}$",
        options: []
    )

    private(set) var mode: LoginMode = .signIn
    var username: String = ""
    var password: String = ""
    private(set) var isSubmitting: Bool = false
    private(set) var errorMessage: String? = nil
    private(set) var noticeMessage: String? = nil
    private(set) var serverUrl: String = ""
    var canSubmit: Bool { !isSubmitting }

    // —— 服务端设置 Sheet ——
    var isServerSheetPresented: Bool = false
    var serverDraft: String = ""
    private(set) var serverDraftError: String? = nil
    private(set) var healthCheck: HealthCheckState = .idle

    @ObservationIgnored private let environment: AppEnvironment
    @ObservationIgnored var onSignedIn: ((Session) -> Void)?
    @ObservationIgnored private var submitTask: Task<Void, Never>?
    @ObservationIgnored private var healthTask: Task<Void, Never>?

    init(environment: AppEnvironment) {
        self.environment = environment
        self.serverUrl = environment.sessions.serverUrl
    }

    func switchMode(_ mode: LoginMode) {
        guard self.mode != mode else { return }
        self.mode = mode
        errorMessage = nil
        noticeMessage = nil
    }

    func signIn() {
        mode = .signIn
        submit()
    }

    func register() {
        mode = .register
        submit()
    }

    func submit() {
        guard !isSubmitting else { return }
        errorMessage = nil
        noticeMessage = nil

        let trimmedUsername = TextMetrics.trimSpace(username)
        switch mode {
        case .register:
            if !Self.isValidUsername(trimmedUsername) {
                errorMessage = "用户名需为 3–32 位，以英文字母开头，仅可使用字母、数字或下划线。"
                return
            }
            if !Self.isValidPassword(password) {
                errorMessage = "密码需至少 8 位，UTF-8 编码最多 72 字节，首尾不能有空白。"
                return
            }
        case .signIn:
            if trimmedUsername.isEmpty {
                errorMessage = "请输入用户名。"
                return
            }
            if password.isEmpty {
                errorMessage = "请输入密码。"
                return
            }
        }

        let currentServer = environment.sessions.serverUrl
        serverUrl = currentServer
        let submittedPassword = password
        let currentMode = mode
        isSubmitting = true

        submitTask?.cancel()
        submitTask = Task { @MainActor [weak self] in
            guard let self else { return }
            defer { self.isSubmitting = false }
            do {
                switch currentMode {
                case .register:
                    try await self.environment.api.register(
                        baseUrl: currentServer,
                        username: trimmedUsername,
                        password: submittedPassword
                    )
                    self.mode = .signIn
                    self.username = trimmedUsername
                    self.errorMessage = nil
                    self.noticeMessage = "注册成功，请登录。"
                case .signIn:
                    let token = try await self.environment.api.login(
                        baseUrl: currentServer,
                        username: trimmedUsername,
                        password: submittedPassword
                    )
                    let student = try await self.environment.api.me(baseUrl: currentServer, token: token)
                    let session = Session(serverUrl: currentServer, token: token, student: student)
                    self.environment.sessions.save(session)
                    self.password = ""
                    self.errorMessage = nil
                    self.noticeMessage = nil
                    self.onSignedIn?(session)
                }
            } catch is CancellationError {
                return
            } catch let apiError as ApiError where apiError == .cancelled {
                return
            } catch {
                self.errorMessage = ApiErrorFormatter.generalMessage(error)
            }
        }
    }

    func presentServerSettings() {
        serverUrl = environment.sessions.serverUrl
        serverDraft = serverUrl
        serverDraftError = nil
        healthCheck = .idle
        isServerSheetPresented = true
    }

    func testConnection() {
        serverDraftError = nil
        let normalized: String
        do {
            normalized = try ApiClient.normalizeBaseUrl(serverDraft)
        } catch {
            serverDraftError = BaseUrlError.invalid.localizedDescription
            healthCheck = .idle
            return
        }

        healthCheck = .checking
        healthTask?.cancel()
        healthTask = Task { @MainActor [weak self] in
            guard let self else { return }
            do {
                let info = try await self.environment.api.health(baseUrl: normalized)
                self.healthCheck = .ok(info)
            } catch is CancellationError {
                return
            } catch let apiError as ApiError where apiError == .cancelled {
                return
            } catch {
                self.healthCheck = .failed(message: ApiErrorFormatter.generalMessage(error))
            }
        }
    }

    func saveServer() {
        serverDraftError = nil
        let normalized: String
        do {
            normalized = try ApiClient.normalizeBaseUrl(serverDraft)
        } catch {
            serverDraftError = BaseUrlError.invalid.localizedDescription
            return
        }
        healthTask?.cancel()
        environment.sessions.changeServer(normalized)
        serverUrl = normalized
        serverDraft = normalized
        errorMessage = nil
        noticeMessage = "服务端地址已保存。"
        isServerSheetPresented = false
    }

    func resetForSignOut(notice: String?) {
        submitTask?.cancel()
        submitTask = nil
        healthTask?.cancel()
        healthTask = nil
        isSubmitting = false
        password = ""
        errorMessage = nil
        noticeMessage = notice
        serverUrl = environment.sessions.serverUrl
    }

    static func isValidUsername(_ username: String) -> Bool {
        let ns = username as NSString
        let range = NSRange(location: 0, length: ns.length)
        return usernameRegex.firstMatch(in: username, options: [], range: range) != nil
    }

    static func isValidPassword(_ password: String) -> Bool {
        guard password.count >= 8 else { return false }
        guard password.utf8.count <= 72 else { return false }
        return TextMetrics.trimSpace(password) == password
    }
}
