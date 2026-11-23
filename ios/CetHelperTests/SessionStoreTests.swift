import XCTest
@testable import CetHelper

final class SessionStoreTests: XCTestCase {
    private var suiteName: String!
    private var defaults: UserDefaults!
    private var store: SessionStore!

    override func setUp() {
        suiteName = "com.cethelper.tests.session.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)!
        store = SessionStore(defaults: defaults)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        store = nil
        defaults = nil
    }

    func testFingerprintMatchesStandardSha256Base64() {
        XCTAssertEqual(SessionStore.fingerprint("abc"), "ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0=")
    }

    func testDefaultServerAndVerifiedSessionRoundTripAndTamperRejection() {
        XCTAssertEqual(store.serverUrl, "http://127.0.0.1:8080")
        XCTAssertNil(store.token)

        let session = Session(
            serverUrl: "http://127.0.0.1:8080",
            token: "tok-123",
            student: Student(id: 7, username: "stu_a")
        )
        store.save(session)

        XCTAssertEqual(store.token, "tok-123")
        XCTAssertEqual(store.verified(server: session.serverUrl, token: session.token), session)

        // Mismatched server or token -> nil
        XCTAssertNil(store.verified(server: "http://other:8080", token: session.token))
        XCTAssertNil(store.verified(server: session.serverUrl, token: "wrong-token"))

        // Corrupt JSON / invalid id / blank username -> nil
        defaults.set("{\"server\":\"http://127.0.0.1:8080\",\"credential\":\"\(SessionStore.fingerprint("tok-123"))\",\"id\":0,\"username\":\"stu_a\"}", forKey: SessionStore.verifiedSessionKey)
        XCTAssertNil(store.verified(server: session.serverUrl, token: session.token))

        defaults.set("{\"server\":\"http://127.0.0.1:8080\",\"credential\":\"\(SessionStore.fingerprint("tok-123"))\",\"id\":1,\"username\":\"   \"}", forKey: SessionStore.verifiedSessionKey)
        XCTAssertNil(store.verified(server: session.serverUrl, token: session.token))

        // Restore valid and verify changeServer clears token + verified_session
        store.save(session)
        store.changeServer("https://api.example.com")
        XCTAssertEqual(store.serverUrl, "https://api.example.com")
        XCTAssertNil(store.token)
        XCTAssertNil(defaults.string(forKey: SessionStore.verifiedSessionKey))
    }
}
