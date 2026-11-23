import XCTest
@testable import CetHelper

final class TextMetricsTests: XCTestCase {
    func testTrimSpaceCoversUnicodeSpaces() {
        let raw = "\u{3000}\u{00A0}\t\n  hello\u{3000}world \r\n\u{00A0}\u{3000}"
        XCTAssertEqual(TextMetrics.trimSpace(raw), "hello\u{3000}world")
        XCTAssertEqual(TextMetrics.trimSpace(" \t\n\u{00A0}\u{3000} "), "")
    }

    func testEnglishWordCountAndBadges() {
        XCTAssertEqual(TextMetrics.englishWordCount("I don't know a well-known 2026 fact!"), 6)
        XCTAssertEqual(TextMetrics.englishWordCount("12345 67890 ---"), 0)

        XCTAssertEqual(TextMetrics.writingWordBadge(0), "0 词 · 四六级建议 120-180 词")
        XCTAssertEqual(TextMetrics.writingWordBadge(119), "119 / 120-180 词（偏少）")
        XCTAssertEqual(TextMetrics.writingWordBadge(120), "120 / 120-180 词（达标）")
        XCTAssertEqual(TextMetrics.writingWordBadge(180), "180 / 120-180 词（达标）")
        XCTAssertEqual(TextMetrics.writingWordBadge(181), "181 / 120-180 词（超出建议）")

        XCTAssertEqual(TextMetrics.translationWordBadge(42), "当前 42 词")
    }

    func testExplanationFormattedMetaOrderAndFallback() {
        let full = ExplanationResult(
            selection: "declines",
            sentence: "Sales declined.",
            meaning: "下降",
            explanation: "减少",
            mode: "live",
            phonetic: "/dɪˈklaɪn/",
            partOfSpeech: "v.",
            lemma: "decline",
            cetLevel: "CET-4",
            contextual: false
        )
        XCTAssertEqual(full.formattedMeta(), "/dɪˈklaɪn/  v.  · CET-4  原型: decline  通用词典")

        let contextualOnly = ExplanationResult(
            selection: "decline",
            sentence: "The decline.",
            meaning: "下降",
            explanation: "减少",
            mode: "demo",
            phonetic: "",
            partOfSpeech: "n.",
            lemma: "",
            cetLevel: "CET-6",
            contextual: true
        )
        XCTAssertEqual(contextualOnly.formattedMeta(), "n.  · CET-6")
    }
}
