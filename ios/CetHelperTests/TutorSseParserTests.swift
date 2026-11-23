import XCTest
@testable import CetHelper

final class TutorSseParserTests: XCTestCase {
    override func tearDown() {
        StubURLProtocol.setHandler(nil)
        super.tearDown()
    }

    func testFullSseStreamWithCommentsCrLfMultilineDataAndDoneFollowUps() {
        let raw = [
            ":ping",
            "",
            "event: meta",
            "data: {\"mode\":\"demo\",\"contextType\":\"WORD\"}",
            "",
            ":heartbeat",
            "event: delta",
            "data: {\"text\":\"我们先看 \"}",
            "",
            "event: delta",
            "data: {",
            "data:   \"text\": \"abandon 这个词\"",
            "data: }",
            "",
            "event: done",
            "data: {\"followUps\":[\"能再给我两个例句吗？\",\"   \",\"和近义词有什么区别？\"]}",
            "",
            ""
        ].joined(separator: "\r\n")

        var events: [TutorSseEvent] = []
        var errors: [Error] = []
        ApiClient.parseTutorSseStream(
            data: Data(raw.utf8),
            onEvent: { events.append($0) },
            onError: { errors.append($0) }
        )

        XCTAssertTrue(errors.isEmpty)
        XCTAssertEqual(events, [
            .meta(mode: "demo", contextType: "WORD"),
            .delta(text: "我们先看 "),
            .delta(text: "abandon 这个词"),
            .done(followUps: ["能再给我两个例句吗？", "和近义词有什么区别？"])
        ])
    }

    func testSseErrorEventsModelErrorAndModelTimeout() {
        let modelErrorRaw = """
        event: meta
        data: {"mode":"live","contextType":"FREE"}

        event: error
        data: {"code":"MODEL_ERROR","message":"AI 助教暂时不可用，请稍后再试。"}

        event: delta
        data: {"text":"should never fire"}

        """

        var events: [TutorSseEvent] = []
        var errors: [Error] = []
        ApiClient.parseTutorSseStream(
            data: Data(modelErrorRaw.utf8),
            onEvent: { events.append($0) },
            onError: { errors.append($0) }
        )

        XCTAssertTrue(errors.isEmpty)
        XCTAssertEqual(events, [
            .meta(mode: "live", contextType: "FREE"),
            .error(code: "MODEL_ERROR", message: "AI 助教暂时不可用，请稍后再试。")
        ])

        let timeoutRaw = """
        event: error
        data: {"code":"MODEL_TIMEOUT","message":"AI 助教响应超时，请稍后重试。"}

        """
        var timeoutEvents: [TutorSseEvent] = []
        ApiClient.parseTutorSseStream(
            data: Data(timeoutRaw.utf8),
            onEvent: { timeoutEvents.append($0) },
            onError: { XCTFail("Unexpected transport error: \($0)") }
        )
        XCTAssertEqual(timeoutEvents, [
            .error(code: "MODEL_TIMEOUT", message: "AI 助教响应超时，请稍后重试。")
        ])
    }

    func testPrematureEofReportsConnectionFailure() {
        let raw = """
        event: meta
        data: {"mode":"demo","contextType":"FREE"}

        event: delta
        data: {"text":"部分内容"}
        """

        var events: [TutorSseEvent] = []
        var errors: [Error] = []
        ApiClient.parseTutorSseStream(
            data: Data(raw.utf8),
            onEvent: { events.append($0) },
            onError: { errors.append($0) }
        )

        XCTAssertEqual(events, [
            .meta(mode: "demo", contextType: "FREE"),
            .delta(text: "部分内容")
        ])
        XCTAssertEqual(errors.count, 1)
        XCTAssertEqual(errors.first as? ApiError, .connectionFailure)
    }

    func testMalformedJsonInSseReportsMalformedError() {
        let raw = """
        event: delta
        data: {not-valid-json}

        """
        var errors: [Error] = []
        ApiClient.parseTutorSseStream(
            data: Data(raw.utf8),
            onEvent: { XCTFail("Unexpected event: \($0)") },
            onError: { errors.append($0) }
        )
        XCTAssertEqual(errors.count, 1)
        XCTAssertEqual(errors.first as? ApiError, .malformed)
    }

    func testCancelledStreamEmitsZeroCallbacksAfterCancellation() {
        let raw = """
        event: meta
        data: {"mode":"demo","contextType":"FREE"}

        event: delta
        data: {"text":"chunk-1"}

        event: delta
        data: {"text":"chunk-2"}

        event: done
        data: {"followUps":["q1"]}

        """

        var cancelled = false
        var events: [TutorSseEvent] = []
        var errors: [Error] = []
        ApiClient.parseTutorSseStream(
            data: Data(raw.utf8),
            isCancelled: { cancelled },
            onEvent: { event in
                events.append(event)
                if case .delta = event {
                    cancelled = true
                }
            },
            onError: { errors.append($0) }
        )

        XCTAssertEqual(events, [
            .meta(mode: "demo", contextType: "FREE"),
            .delta(text: "chunk-1")
        ])
        XCTAssertTrue(errors.isEmpty)

        // Pre-cancelled stream emits nothing at all.
        var preEvents: [TutorSseEvent] = []
        var preErrors: [Error] = []
        ApiClient.parseTutorSseStream(
            data: Data(raw.utf8),
            isCancelled: { true },
            onEvent: { preEvents.append($0) },
            onError: { preErrors.append($0) }
        )
        XCTAssertTrue(preEvents.isEmpty)
        XCTAssertTrue(preErrors.isEmpty)
    }

    @MainActor
    func testStreamTutorChatHeadersPayloadAndTutorViewModelPersistence() async throws {
        let tempDir = FileManager.default.temporaryDirectory.appendingPathComponent("cet-tutorvm-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }

        let suiteName = "com.cethelper.tests.tutorvm.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defer { defaults.removePersistentDomain(forName: suiteName) }

        let db = try StudyDatabase(path: tempDir.appendingPathComponent("study.db").path)
        let api = ApiClient(configuration: StubURLProtocol.makeConfiguration())
        let env = AppEnvironment(
            database: db,
            sessions: SessionStore(defaults: defaults),
            drafts: DraftStore(defaults: defaults),
            readerPrefs: ReaderPreferences(defaults: defaults),
            api: api
        )
        let session = Session(
            serverUrl: "http://127.0.0.1:8080",
            token: "sse-tok",
            student: Student(id: 9, username: "bob")
        )

        var capturedAccept: String?
        var capturedCacheControl: String?
        var capturedAuth: String?
        var capturedContextType: String?
        StubURLProtocol.setHandler { req in
            capturedAccept = req.value(forHTTPHeaderField: "Accept")
            capturedCacheControl = req.value(forHTTPHeaderField: "Cache-Control")
            capturedAuth = req.value(forHTTPHeaderField: "Authorization")
            if let obj = try? JSONSerialization.jsonObject(with: readBodyData(from: req)) as? [String: Any],
               let ctx = obj["context"] as? [String: Any] {
                capturedContextType = ctx["type"] as? String
            }
            let sse = """
            event: meta
            data: {"mode":"demo","contextType":"WORD"}

            event: delta
            data: {"text":"Hello "}

            event: delta
            data: {"text":"world!"}

            event: done
            data: {"followUps":["追问 1"]}

            """
            let response = HTTPURLResponse(
                url: req.url!,
                statusCode: 200,
                httpVersion: "HTTP/1.1",
                headerFields: ["Content-Type": "text/event-stream; charset=utf-8"]
            )!
            return (response, Data(sse.utf8))
        }

        let vm = TutorViewModel(session: session, environment: env, onUnauthorized: {})
        let wordCtx = TutorContext(
            type: .word,
            title: "abandon",
            focusText: "abandon",
            referenceText: "They had to abandon the car.",
            extraNote: "释义：放弃"
        )
        vm.attach(wordCtx)
        vm.send("怎么记这个词？")

        let deadline = Date().addingTimeInterval(2.0)
        while (vm.isGenerating || vm.messages.count < 2) && Date() < deadline {
            try? await Task.sleep(for: .milliseconds(20))
        }

        XCTAssertEqual(capturedAccept, "text/event-stream")
        XCTAssertEqual(capturedCacheControl, "no-cache")
        XCTAssertEqual(capturedAuth, "Bearer sse-tok")
        XCTAssertEqual(capturedContextType, "WORD")
        XCTAssertFalse(vm.isGenerating)
        XCTAssertEqual(vm.messages.count, 2)
        XCTAssertEqual(vm.messages[0].role, .user)
        XCTAssertEqual(vm.messages[0].content, "怎么记这个词？")
        XCTAssertEqual(vm.messages[1].role, .assistant)
        XCTAssertEqual(vm.messages[1].content, "Hello world!")
        XCTAssertEqual(vm.messages[1].mode, "demo")
        XCTAssertEqual(vm.followUps, ["追问 1"])

        // Reload persisted session from DB via fresh TutorViewModel
        let reloaded = TutorViewModel(session: session, environment: env, onUnauthorized: {})
        reloaded.attach(wordCtx)
        XCTAssertEqual(reloaded.messages.map(\.content), ["怎么记这个词？", "Hello world!"])
    }
}
