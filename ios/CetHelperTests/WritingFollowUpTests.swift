import XCTest
@testable import CetHelper

@MainActor
final class WritingFollowUpTests: XCTestCase {
    private var suiteName: String!
    private var defaults: UserDefaults!
    private var tempDir: URL!

    override func setUpWithError() throws {
        StubURLProtocol.setHandler(nil)
        suiteName = "com.cethelper.tests.writing.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)!
        tempDir = FileManager.default.temporaryDirectory.appendingPathComponent("cet-writing-\(UUID().uuidString)")
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

    private func makeWritingVM() throws -> WritingViewModel {
        let db = try StudyDatabase(path: tempDir.appendingPathComponent("study.db").path)
        let env = AppEnvironment(
            database: db,
            sessions: SessionStore(defaults: defaults),
            drafts: DraftStore(defaults: defaults, debounce: .milliseconds(20)),
            readerPrefs: ReaderPreferences(defaults: defaults),
            api: ApiClient(configuration: StubURLProtocol.makeConfiguration())
        )
        let session = Session(
            serverUrl: "http://127.0.0.1:8080",
            token: "tok",
            student: Student(id: 1, username: "stu")
        )
        return WritingViewModel(session: session, environment: env, onUnauthorized: {})
    }

    func testOutOfBoundsExerciseDoesNotMountAndFollowUpLifecycle() async throws {
        let vm = try makeWritingVM()
        let issue = ReviewIssue(
            index: 0, type: .grammar, quote: "prefers", detail: "verb", suggestion: "prefer",
            target: .draft, matches: [TextRange(start: 14, end: 21)]
        )

        // Out of bounds exercise -> followUp remains nil
        let badFeedback = WritingFeedback(
            prompt: "P", essay: "Many students prefers", mode: "demo", overallFeedback: "ok",
            issues: [issue], exercise: WritingExercise(issueIndex: 5, instruction: "Fix", item: "x"),
            estimatedScore: 10, scoreMax: 15, scoreBand: nil, rawJson: "{}"
        )
        vm.applyFollowUp(from: badFeedback)
        XCTAssertNil(vm.followUp)

        // Valid exercise mounts followUp
        let validFeedback = WritingFeedback(
            prompt: "P", essay: "Many students prefers", mode: "demo", overallFeedback: "ok",
            issues: [issue], exercise: WritingExercise(issueIndex: 0, instruction: "Fix", item: "Students prefers"),
            estimatedScore: 10, scoreMax: 15, scoreBand: nil, rawJson: "{}"
        )
        vm.applyFollowUp(from: validFeedback)
        let followUp = try XCTUnwrap(vm.followUp)

        // Over 2000 limit validation
        followUp.exerciseAttempt.input = String(repeating: "a", count: 2001)
        followUp.submitExercise()
        XCTAssertNotNil(followUp.exerciseAttempt.validationMessage)
        XCTAssertFalse(followUp.exerciseAttempt.request.isPending)

        // Duplicate submit prevention while pending
        StubURLProtocol.setHandler { req in
            if req.url?.path.hasSuffix("/writing-exercise-attempts") == true {
                Thread.sleep(forTimeInterval: 0.12)
                return makeJSONResponse(url: req.url!, json: [
                    "issue": ["type": "GRAMMAR", "quote": "prefers", "detail": "verb", "suggestion": "prefer"],
                    "mode": "demo",
                    "answer": "Students prefer",
                    "verdict": "CORRECT",
                    "feedback": "Good job",
                    "nextStep": "Try retest"
                ])
            }
            if req.url?.path.hasSuffix("/writing-retests") == true {
                return makeJSONResponse(url: req.url!, json: [
                    "issue": ["type": "GRAMMAR", "quote": "prefers", "detail": "verb", "suggestion": "prefer"],
                    "mode": "demo",
                    "task": ["instruction": "New context", "item": "Write one sentence"]
                ])
            }
            if req.url?.path.hasSuffix("/writing-reviews") == true {
                return makeJSONResponse(url: req.url!, json: [
                    "prompt": "New Prompt",
                    "essay": "New essay with no issues.",
                    "mode": "demo",
                    "overallFeedback": "Great!",
                    "issues": []
                ])
            }
            return makeJSONResponse(url: req.url!, status: 404, json: [:])
        }

        followUp.exerciseAttempt.input = "Students prefer"
        followUp.submitExercise()
        XCTAssertFalse(followUp.exerciseAttempt.canSubmit)

        try await Task.sleep(for: .milliseconds(220))
        XCTAssertEqual(followUp.exerciseAttempt.assessment?.verdict, .correct)

        // Request retest resets retestAttempt and populates retestTask
        followUp.retestAttempt.input = "old draft"
        followUp.requestRetest()
        try await Task.sleep(for: .milliseconds(80))
        XCTAssertEqual(followUp.retestTask?.instruction, "New context")
        XCTAssertEqual(followUp.retestAttempt.input, "")
        XCTAssertTrue(followUp.canSubmitRetest)

        // Resubmitting essay replaces/clears old followUp when new review has no exercise
        vm.prompt = "New Prompt"
        vm.essay = "New essay with no issues."
        vm.submitReview()
        try await Task.sleep(for: .milliseconds(100))
        XCTAssertNil(vm.followUp)
    }
}
