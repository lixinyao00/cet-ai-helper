import XCTest
@testable import CetHelper

@MainActor
final class PracticeRequestTests: XCTestCase {
    func testOutOfOrderStaleResultIsDiscarded() async throws {
        let request = PracticeRequest<String>()
        var completedValues: [String] = []

        // First request finishes slowly
        request.start({
            try await Task.sleep(for: .milliseconds(120))
            return "stale"
        }, onComplete: { completedValues.append($0) })

        // Second request finishes quickly
        request.start({
            try await Task.sleep(for: .milliseconds(20))
            return "fresh"
        }, onComplete: { completedValues.append($0) })

        try await Task.sleep(for: .milliseconds(200))
        XCTAssertEqual(request.value, "fresh")
        XCTAssertEqual(completedValues, ["fresh"])
        XCTAssertFalse(request.isPending)
    }

    func testResetCancelsInFlightAndClearsState() async throws {
        let request = PracticeRequest<String>()
        request.start {
            try await Task.sleep(for: .milliseconds(150))
            return "late"
        }
        XCTAssertTrue(request.isPending)

        request.reset()
        XCTAssertFalse(request.isPending)
        XCTAssertNil(request.value)
        XCTAssertNil(request.errorMessage)

        try await Task.sleep(for: .milliseconds(200))
        XCTAssertNil(request.value)
    }

    func testDemoUnsupportedSetsCanRetryFalse() async throws {
        let request = PracticeRequest<String>()
        request.start {
            throw ApiError.http(status: 422, code: "DEMO_UNSUPPORTED", message: "演示模式暂未收录")
        }
        try await Task.sleep(for: .milliseconds(50))
        XCTAssertEqual(request.errorMessage, "演示模式暂未收录")
        XCTAssertFalse(request.canRetry)
    }
}
