import XCTest
@testable import CetHelper

final class SentenceAnalysisTests: XCTestCase {
    func testLocateSequentialDuplicateChunksWithoutOverlap() throws {
        let sentence = "I think that the plan that she proposed works well."
        let chunks = try SentenceAnalysis.locate(sentence: sentence, chunks: [
            (text: "that", role: "引导词1", explanation: "宾语从句引导词"),
            (text: "that", role: "引导词2", explanation: "定语从句引导词")
        ])

        XCTAssertEqual(chunks.count, 2)
        XCTAssertEqual(chunks[0].range, TextRange(start: 8, end: 12))
        XCTAssertEqual(chunks[1].range, TextRange(start: 22, end: 26))
    }

    func testLocateThrowsOnMissingEmptyOrOutOfOrderChunk() {
        let sentence = "First second third."

        XCTAssertThrowsError(try SentenceAnalysis.locate(sentence: sentence, chunks: [
            (text: "", role: "r", explanation: "e")
        ]))

        XCTAssertThrowsError(try SentenceAnalysis.locate(sentence: sentence, chunks: [
            (text: "missing", role: "r", explanation: "e")
        ]))

        XCTAssertThrowsError(try SentenceAnalysis.locate(sentence: sentence, chunks: [
            (text: "third", role: "r1", explanation: "e1"),
            (text: "First", role: "r2", explanation: "e2")
        ]))
    }

    func testLocateUTF16OffsetsWithEmojiAndChinese() throws {
        // "😀" is 2 UTF-16 code units, "中文" is 2 UTF-16 code units, " " is 1.
        let sentence = "😀中文 hello world"
        let chunks = try SentenceAnalysis.locate(sentence: sentence, chunks: [
            (text: "hello", role: "word", explanation: "greeting"),
            (text: "world", role: "noun", explanation: "planet")
        ])

        XCTAssertEqual(chunks[0].range, TextRange(start: 5, end: 10))
        XCTAssertEqual(chunks[1].range, TextRange(start: 11, end: 16))
    }
}
