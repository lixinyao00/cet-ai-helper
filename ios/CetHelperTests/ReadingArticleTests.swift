import XCTest
@testable import CetHelper

final class ReadingArticleTests: XCTestCase {
    func testParagraphSplitWithWhitespaceAndNbspLinesAndEmojiOffsets() {
        let body = "😀 First para.\n   \n\u{00A0}\nSecond para.\n\n   \t  \nThird para."
        let article = ReadingArticle(id: "t1", title: "T", body: body)
        let paragraphs = article.paragraphs()

        XCTAssertEqual(paragraphs.count, 3)
        XCTAssertEqual(paragraphs[0].start, 0)
        XCTAssertEqual(paragraphs[0].text, "😀 First para.")

        let nsBody = body as NSString
        XCTAssertEqual(paragraphs[1].start, nsBody.range(of: "Second para.").location)
        XCTAssertEqual(paragraphs[1].text, "Second para.")
        XCTAssertEqual(paragraphs[2].start, nsBody.range(of: "Third para.").location)
        XCTAssertEqual(paragraphs[2].text, "Third para.")
    }

    func testDemoArticleTwoDeclinesReturnDistinctSentences() {
        let article = DemoArticle.article
        let paragraphs = article.paragraphs()
        XCTAssertEqual(paragraphs.count, 2)

        let p0 = paragraphs[0]
        let r0 = (p0.text as NSString).range(of: "decline")
        let sel0 = p0.select(from: r0.location, to: r0.location + r0.length)
        XCTAssertEqual(sel0?.text, "decline")
        XCTAssertEqual(sel0?.sentence, "The report attributed the decline to lower demand.")
        XCTAssertEqual(sel0?.start, p0.start + r0.location)

        let p1 = paragraphs[1]
        let r1 = (p1.text as NSString).range(of: "decline")
        let sel1 = p1.select(from: r1.location, to: r1.location + r1.length)
        XCTAssertEqual(sel1?.text, "decline")
        XCTAssertEqual(sel1?.sentence, "Some students decline the invitation.")
        XCTAssertEqual(sel1?.start, p1.start + r1.location)
    }

    func testSelectTrimsSpacesReturnsNilOnBlankAndNilSentenceAcrossSentences() {
        let article = ReadingArticle(id: "s", title: "S", body: "First sentence here. Second sentence follows.")
        let p = article.paragraphs()[0]

        XCTAssertNil(p.select(from: 5, to: 6)) // space only

        // Include leading/trailing space around "sentence"
        let trimmed = p.select(from: 5, to: 15)
        XCTAssertEqual(trimmed?.text, "sentence")
        XCTAssertEqual(trimmed?.sentence, "First sentence here.")

        // Cross two sentences
        let cross = p.select(from: 15, to: 27)
        XCTAssertNotNil(cross)
        XCTAssertEqual(cross?.text, "here. Second")
        XCTAssertNil(cross?.sentence)
    }

    func testAbbreviationsAndQuotesSentenceBoundaries() {
        let body = "Mr. Smith visited Washington. He said, \"Hello.\" Then he left."
        let p = ReadingArticle(id: "abbr", title: "Abbr", body: body).paragraphs()[0]

        let mrSmithRange = (p.text as NSString).range(of: "Smith")
        let selMr = p.select(from: mrSmithRange.location, to: mrSmithRange.location + mrSmithRange.length)
        XCTAssertEqual(selMr?.sentence, "Mr. Smith visited Washington.")

        let helloRange = (p.text as NSString).range(of: "Hello")
        let selHello = p.select(from: helloRange.location, to: helloRange.location + helloRange.length)
        XCTAssertEqual(selHello?.sentence, "He said, \"Hello.\"")
    }
}
