import Foundation
import Observation

@MainActor @Observable final class AppViewModel {
    static let offlineSessionBanner = "离线模式 · 正在等待服务恢复"
    static let offlineVerificationFailedBanner = "离线模式 · 会话尚未重新验证，将继续重试"

    private(set) var phase: AppPhase = .restoring
    private(set) var offlineBanner: String? = nil
    let login: LoginViewModel

    @ObservationIgnored let environment: AppEnvironment
    @ObservationIgnored private var hasStarted = false
    @ObservationIgnored private var isForeground = true
    @ObservationIgnored private var isOffline = false
    @ObservationIgnored private var isReverifying = false
    @ObservationIgnored private var restoreRevision = 0
    @ObservationIgnored private var restoreTask: Task<Void, Never>?
    @ObservationIgnored private var reverifyScheduleTask: Task<Void, Never>?
    @ObservationIgnored private var reverifyTask: Task<Void, Never>?

    init(environment: AppEnvironment = .live()) {
        self.environment = environment
        self.login = LoginViewModel(environment: environment)
        self.login.onSignedIn = { [weak self] session in
            self?.openSession(session, offline: false)
        }
    }

    func start() {
        guard !hasStarted else { return }
        hasStarted = true
        isForeground = true
        restoreSession()
    }

    func handleLifecycle(_ event: AppLifecycleEvent) {
        switch event {
        case .active:
            isForeground = true
            if isOffline {
                reverifyNow()
            }
        case .inactive, .background:
            isForeground = false
            cancelReverifySchedule()
            environment.drafts.flush()
            if case .signedIn(let scope) = phase {
                scope.flushAll()
            }
        }
    }

    func retryRestore() {
        restoreSession()
    }

    func backToLogin() {
        clearSession(notice: nil)
    }

    // MARK: - Session Management

    func handleUnauthorized(for session: Session) {
        guard case .signedIn(let scope) = phase, scope.session == session else {
            return
        }
        clearSession(notice: ApiErrorFormatter.signInAgain)
    }

    func signOut(for session: Session) {
        guard case .signedIn(let scope) = phase, scope.session == session else {
            return
        }
        let serverUrl = session.serverUrl
        let token = session.token
        clearSession(notice: nil)
        Task.detached { [api = environment.api] in
            try? await api.logout(baseUrl: serverUrl, token: token)
        }
    }

    func handleServerChanged(for session: Session, to normalizedUrl: String) {
        guard case .signedIn(let scope) = phase, scope.session == session else {
            return
        }
        environment.sessions.changeServer(normalizedUrl)
        clearSession(notice: "服务端地址已保存，请重新登录。")
    }

    func clearSession(notice: String?) {
        restoreRevision += 1
        restoreTask?.cancel()
        restoreTask = nil
        cancelReverifyAll()
        isOffline = false
        offlineBanner = nil

        if case .signedIn(let scope) = phase {
            scope.cancelAllInFlight()
            scope.flushAll()
        }
        environment.drafts.flush()
        environment.sessions.clear()
        environment.speech.stop()

        login.resetForSignOut(notice: notice)
        phase = .signedOut
    }

    private func restoreSession() {
        restoreRevision += 1
        let currentRevision = restoreRevision
        restoreTask?.cancel()
        cancelReverifyAll()

        guard let token = environment.sessions.token,
              !TextMetrics.trimSpace(token).isEmpty else {
            clearSession(notice: nil)
            return
        }

        let server = environment.sessions.serverUrl
        let cached = environment.sessions.verified(server: server, token: token)
        phase = .restoring

        restoreTask = Task { @MainActor [weak self] in
            guard let self else { return }
            do {
                let student = try await self.environment.api.me(baseUrl: server, token: token)
                guard self.restoreRevision == currentRevision,
                      self.environment.sessions.matches(server: server, token: token) else {
                    return
                }
                if let cached, cached.student.id != student.id {
                    self.clearSession(notice: ApiErrorFormatter.signInAgain)
                    return
                }
                let verified = Session(serverUrl: server, token: token, student: student)
                self.environment.sessions.save(verified)
                self.openSession(verified, offline: false)
            } catch {
                guard self.restoreRevision == currentRevision,
                      self.environment.sessions.matches(server: server, token: token) else {
                    return
                }
                if let apiError = error as? ApiError {
                    if apiError.isUnauthorized {
                        self.clearSession(notice: ApiErrorFormatter.signInAgain)
                        return
                    }
                    if (apiError == .connectionFailure || apiError == .timeout), let cached {
                        self.openSession(cached, offline: true)
                        return
                    }
                }
                self.phase = .restoreFailed(message: ApiErrorFormatter.generalMessage(error))
            }
        }
    }

    private func openSession(_ session: Session, offline: Bool) {
        let scope = SessionScope(
            session: session,
            environment: environment,
            onUnauthorized: { [weak self] failedSession in
                self?.handleUnauthorized(for: failedSession)
            },
            onSignOut: { [weak self] activeSession in
                self?.signOut(for: activeSession)
            },
            onServerChanged: { [weak self] activeSession, normalizedUrl in
                self?.handleServerChanged(for: activeSession, to: normalizedUrl)
            }
        )
        phase = .signedIn(scope)
        setOffline(offline)
    }

    private func setOffline(_ offline: Bool) {
        isOffline = offline
        if offline {
            offlineBanner = Self.offlineSessionBanner
            scheduleReverification()
        } else {
            offlineBanner = nil
            cancelReverifyAll()
        }
    }

    private func scheduleReverification() {
        cancelReverifySchedule()
        guard isForeground, isOffline, !isReverifying else { return }
        let delay = environment.reverifyDelay
        reverifyScheduleTask = Task { @MainActor [weak self] in
            do {
                try await Task.sleep(for: delay)
            } catch {
                return
            }
            self?.reverifyNow()
        }
    }

    func reverifyNow() {
        guard isForeground, isOffline, !isReverifying,
              case .signedIn(let scope) = phase else {
            return
        }
        cancelReverifySchedule()
        isReverifying = true
        let activeSession = scope.session

        reverifyTask = Task { @MainActor [weak self] in
            guard let self else { return }
            do {
                let student = try await self.environment.api.me(
                    baseUrl: activeSession.serverUrl,
                    token: activeSession.token
                )
                self.isReverifying = false
                guard case .signedIn(let currentScope) = self.phase,
                      currentScope.session == activeSession else {
                    return
                }
                if student.id != activeSession.student.id {
                    self.clearSession(notice: ApiErrorFormatter.signInAgain)
                    return
                }
                let refreshed = Session(
                    serverUrl: activeSession.serverUrl,
                    token: activeSession.token,
                    student: student
                )
                self.environment.sessions.save(refreshed)
                self.setOffline(false)
            } catch {
                self.isReverifying = false
                guard case .signedIn(let currentScope) = self.phase,
                      currentScope.session == activeSession else {
                    return
                }
                if let apiError = error as? ApiError {
                    if apiError.isUnauthorized {
                        self.clearSession(notice: ApiErrorFormatter.signInAgain)
                        return
                    }
                    if apiError == .cancelled {
                        return
                    }
                    if apiError != .connectionFailure && apiError != .timeout {
                        self.offlineBanner = Self.offlineVerificationFailedBanner
                    }
                } else {
                    self.offlineBanner = Self.offlineVerificationFailedBanner
                }
                self.scheduleReverification()
            }
        }
    }

    private func cancelReverifySchedule() {
        reverifyScheduleTask?.cancel()
        reverifyScheduleTask = nil
    }

    private func cancelReverifyAll() {
        cancelReverifySchedule()
        reverifyTask?.cancel()
        reverifyTask = nil
        isReverifying = false
    }
}
