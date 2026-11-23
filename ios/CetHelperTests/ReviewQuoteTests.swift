import XCTest
@testable import CetHelper

final class ReviewQuoteTests: XCTestCase {
    func testOverlappingMatchesStepByOneUTF16Unit() {
        let matches = ReviewQuote.findMatches(in: "aaa", quote: "aa")
        XCTAssertEqual(matches, [TextRange(start: 0, end: 2), TextRange(start: 1, end: 3)])
    }

    func testEmptyQuoteOrSnapshotReturnsEmpty() {
        XCTAssertEqual(ReviewQuote.findMatches(in: "", quote: "a"), [])
        XCTAssertEqual(ReviewQuote.findMatches(in: "abc", quote: ""), [])
    }

    func testOutOfOrderIndependentSearchAndChineseOmission() {
        let chinese = "今天天气很好，明天我们去公园。"
        let tomorrowMatches = ReviewQuote.findMatches(in: chinese, quote: "明天")
        let todayMatches = ReviewQuote.findMatches(in: chinese, quote: "今天")

        XCTAssertEqual(tomorrowMatches, [TextRange(start: 7, end: 9)])
        XCTAssertEqual(todayMatches, [TextRange(start: 0, end: 2)])
    }
}
