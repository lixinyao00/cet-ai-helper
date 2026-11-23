import XCTest
@testable import CetHelper

@MainActor
final class AppViewModelTests: XCTestCase {
    private var suiteName: String!
    private var defaults: UserDefaults!
    private var tempDir: URL!

    override func setUpWithError() throws {
        StubURLProtocol.setHandler(nil)
        suiteName = "com.cethelper.tests.appvm.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)!
        tempDir = FileManager.default.temporaryDirectory.appendingPathComponent("cet-appvm-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        StubURLProtocol.setHandler(nil)
        defaults.removePersistentDomain(forName: suiteName)
        defaults = nil
        if let tempDir {
            try? FileManager.default.removeItem(at: tempDir)
        }
    }

    private func makeEnv(reverifyDelay: Duration = .milliseconds(80)) throws -> AppEnvironment {
        let db = try StudyDatabase(path: tempDir.appendingPathComponent("study.db").path)
        return AppEnvironment(
            database: db,
            sessions: SessionStore(defaults: defaults),
            drafts: DraftStore(defaults: defaults, debounce: .milliseconds(20)),
            readerPrefs: ReaderPreferences(defaults: defaults),
            api: ApiClient(configuration: StubURLProtocol.makeConfiguration()),
            reverifyDelay: reverifyDelay
        )
    }

    private func waitUntil(timeout: TimeInterval = 2.0, _ condition: @escaping () -> Bool) async {
        let deadline = Date().addingTimeInterval(timeout)
        while !condition() && Date() < deadline {
            try? await Task.sleep(for: .milliseconds(20))
        }
    }

    func testNoTokenSignsOutImmediately() throws {
        let vm = AppViewModel(environment: try makeEnv())
        vm.start()
        guard case .signedOut = vm.phase else {
            return XCTFail("Expected .signedOut")
        }
    }

    func testMeSuccessSignsInOnline() async throws {
        let env = try makeEnv()
        let saved = Session(serverUrl: "http://127.0.0.1:8080", token: "tok1", student: Student(id: 5, username: "alice"))
        env.sessions.save(saved)

        StubURLProtocol.setHandler { req in
            makeJSONResponse(url: req.url!, json: ["id": 5, "username": "alice"])
        }

        let vm = AppViewModel(environment: env)
        vm.start()
        await waitUntil {
            if case .signedIn = vm.phase { return true }
            return false
        }

        guard case .signedIn(let scope) = vm.phase else {
            return XCTFail("Expected .signedIn")
        }
        XCTAssertEqual(scope.session.student.username, "alice")
        XCTAssertNil(vm.offlineBanner)
    }

    func testMeStudentIdMismatchAnd401ClearSessionWithNotice() async throws {
        let env = try makeEnv()
        let saved = Session(serverUrl: "http://127.0.0.1:8080", token: "tok1", student: Student(id: 5, username: "alice"))
        env.sessions.save(saved)

        // ID mismatch
        StubURLProtocol.setHandler { req in
            makeJSONResponse(url: req.url!, json: ["id": 99, "username": "bob"])
        }
        let vm1 = AppViewModel(environment: env)
        vm1.start()
        await waitUntil {
            if case .signedOut = vm1.phase { return true }
            return false
        }
        XCTAssertEqual(vm1.login.noticeMessage, "会话已失效，请重新登录。")
        XCTAssertNil(env.sessions.token)

        // HTTP 401
        env.sessions.save(saved)
        StubURLProtocol.setHandler { req in
            makeJSONResponse(url: req.url!, status: 401, json: ["code": "UNAUTHORIZED", "message": "expired"])
        }
        let vm2 = AppViewModel(environment: env)
        vm2.start()
        await waitUntil {
            if case .signedOut = vm2.phase { return true }
            return false
        }
        XCTAssertEqual(vm2.login.noticeMessage, "会话已失效，请重新登录。")
    }

    func testOfflineRecoveryPreservesScopeAndNonConnectionErrorSwitchesBannerAndGlobal401() async throws {
        let env = try makeEnv(reverifyDelay: .milliseconds(60))
        let saved = Session(serverUrl: "http://127.0.0.1:8080", token: "tok-off", student: Student(id: 3, username: "offline_u"))
        env.sessions.save(saved)

        // Cold start with connectionFailure + cached session -> offline signedIn
        var mode = 0
        StubURLProtocol.setHandler { req in
            if req.url?.path.hasSuffix("/me") == true {
                if mode == 0 {
                    throw URLError(.notConnectedToInternet)
                } else if mode == 1 {
                    return makeJSONResponse(url: req.url!, status: 500, json: ["code": "INTERNAL_ERROR", "message": "boom"])
                } else {
                    return makeJSONResponse(url: req.url!, json: ["id": 3, "username": "offline_u"])
                }
            }
            if req.url?.path.hasSuffix("/explanations") == true {
                return makeJSONResponse(url: req.url!, status: 401, json: ["code": "UNAUTHORIZED", "message": "revoked"])
            }
            return makeJSONResponse(url: req.url!, json: ["status": "UP"])
        }

        let vm = AppViewModel(environment: env)
        vm.start()
        await waitUntil {
            if case .signedIn = vm.phase { return true }
            return false
        }
        guard case .signedIn(let scope) = vm.phase else {
            return XCTFail("Expected offline .signedIn")
        }
        XCTAssertEqual(vm.offlineBanner, "离线模式 · 正在等待服务恢复")
        scope.writing.essay = "Keep my draft!"

        // Next reverify gets 500 -> banner switches
        mode = 1
        await waitUntil { vm.offlineBanner == "离线模式 · 会话尚未重新验证，将继续重试" }
        XCTAssertEqual(vm.offlineBanner, "离线模式 · 会话尚未重新验证，将继续重试")

        // Server comes back -> banner clears and same scope instance & draft preserved
        mode = 2
        await waitUntil { vm.offlineBanner == nil }
        XCTAssertNil(vm.offlineBanner)
        guard case .signedIn(let scopeAfter) = vm.phase else {
            return XCTFail("Should stay signedIn")
        }
        XCTAssertTrue(scopeAfter === scope)
        XCTAssertEqual(scopeAfter.writing.essay, "Keep my draft!")

        // Any business API returning 401 triggers global sign-out
        let reader = scope.makeReader(articleId: DemoArticle.id, focus: nil)!
        let r = (DemoArticle.body as NSString).range(of: "decline")
        reader.select(TextRange(start: r.location, end: r.location + r.length))
        await waitUntil {
            if case .signedOut = vm.phase { return true }
            return false
        }
        XCTAssertEqual(vm.login.noticeMessage, "会话已失效，请重新登录。")
    }

    func testConnectionFailureWithoutCacheShowsRestoreFailed() async throws {
        let env = try makeEnv()
        // Only token, no verified_session cache
        defaults.set("orphan-token", forKey: SessionStore.tokenKey)
        StubURLProtocol.setHandler { _ in throw URLError(.cannotConnectToHost) }

        let vm = AppViewModel(environment: env)
        vm.start()
        await waitUntil {
            if case .restoreFailed = vm.phase { return true }
            return false
        }
        guard case .restoreFailed(let message) = vm.phase else {
            return XCTFail("Expected .restoreFailed")
        }
        XCTAssertEqual(message, "无法连接服务，请检查网络和服务端地址。")
    }
}
