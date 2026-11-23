import XCTest
@testable import CetHelper

final class DraftStoreTests: XCTestCase {
    private var suiteName: String!
    private var defaults: UserDefaults!

    override func setUp() {
        suiteName = "com.cethelper.tests.draft.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)!
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        defaults = nil
    }

    func testKeyPrefixDebounceFlushImmediateClearAndReaderPreferences() async throws {
        let owner = ArticleOwner(serverUrl: "http://127.0.0.1:8080", studentId: 42)
        XCTAssertEqual(DraftStore.prefix(owner), "42|http://127.0.0.1:8080|")

        let store = DraftStore(defaults: defaults, debounce: .milliseconds(80))
        store.saveTranslation(owner: owner, source: "s1", draft: "d1", immediate: false)
        store.saveTranslation(owner: owner, source: "s2", draft: "d2", immediate: false)

        // Not written yet before debounce expires
        XCTAssertNil(defaults.string(forKey: "42|http://127.0.0.1:8080|translation.source"))

        try await Task.sleep(for: .milliseconds(160))
        XCTAssertEqual(defaults.string(forKey: "42|http://127.0.0.1:8080|translation.source"), "s2")
        XCTAssertEqual(defaults.string(forKey: "42|http://127.0.0.1:8080|translation.draft"), "d2")

        // Flush immediately persists pending writing draft
        store.saveWriting(owner: owner, prompt: "p1", essay: "e1", immediate: false)
        store.flush()
        XCTAssertEqual(defaults.string(forKey: "42|http://127.0.0.1:8080|writing.prompt"), "p1")
        XCTAssertEqual(defaults.string(forKey: "42|http://127.0.0.1:8080|writing.essay"), "e1")

        // Explicit clear writes "" and survives new DraftStore instance
        store.saveWriting(owner: owner, prompt: "", essay: "", immediate: true)
        let recreated = DraftStore(defaults: defaults)
        let restored = recreated.writing(for: owner)
        XCTAssertEqual(restored.prompt, "")
        XCTAssertEqual(restored.essay, "")

        // ReaderPreferences font_tier & demo_scroll key format
        let prefs = ReaderPreferences(defaults: defaults)
        XCTAssertEqual(prefs.fontTier, .standard)
        prefs.fontTier = .extraLarge
        XCTAssertEqual(defaults.integer(forKey: "font_tier"), 2)
        XCTAssertEqual(prefs.fontTier, .extraLarge)

        prefs.setDemoScrollY(245, for: owner)
        XCTAssertEqual(defaults.integer(forKey: "demo_scroll|http://127.0.0.1:8080|42"), 245)
        XCTAssertEqual(prefs.demoScrollY(for: owner), 245)
    }
}
