import CoreGraphics
import UIKit
import XCTest
@testable import CetHelper

final class PageOcrLayoutTests: XCTestCase {
    // MARK: - Legacy 3 Cases (Backward Compatibility)

    func testContinuesParagraphAllThreeBoundariesInsideAndOutside() {
        // lineHeight = 20 -> gap in [-5, 24], |left diff| <= 16
        let above = OcrLineBox(text: "Line 1", left: 100, top: 100, height: 20) // bottom = 120

        // 1. gap lower bound (-lh/4 = -5)
        XCTAssertTrue(PageOcrLayout.continuesParagraph(above: above, below: OcrLineBox(text: "L2", left: 100, top: 115, height: 20)))
        XCTAssertFalse(PageOcrLayout.continuesParagraph(above: above, below: OcrLineBox(text: "L2", left: 100, top: 114.9, height: 20)))

        // 2. gap upper bound (lh * 1.2 = 24)
        XCTAssertTrue(PageOcrLayout.continuesParagraph(above: above, below: OcrLineBox(text: "L2", left: 100, top: 144, height: 20)))
        XCTAssertFalse(PageOcrLayout.continuesParagraph(above: above, below: OcrLineBox(text: "L2", left: 100, top: 144.1, height: 20)))

        // 3. left diff bound (lh * 0.8 = 16)
        XCTAssertTrue(PageOcrLayout.continuesParagraph(above: above, below: OcrLineBox(text: "L2", left: 116, top: 125, height: 20)))
        XCTAssertFalse(PageOcrLayout.continuesParagraph(above: above, below: OcrLineBox(text: "L2", left: 116.1, top: 125, height: 20)))
    }

    func testVisionCoordinateFlipAndMergeSortingAndSeparators() {
        let size = CGSize(width: 1000, height: 2000)
        // Vision origin bottom-left: y=0.8, h=0.05 -> top = (1 - 0.85) * 2000 = 300, height = 100, width = 500
        let box = PageOcrLayout.fromVision(
            boundingBox: CGRect(x: 0.1, y: 0.8, width: 0.5, height: 0.05),
            text: "Hello",
            imageSize: size
        )
        XCTAssertEqual(box.left, 100, accuracy: 0.001)
        XCTAssertEqual(box.top, 300, accuracy: 0.001)
        XCTAssertEqual(box.height, 100, accuracy: 0.001)
        XCTAssertEqual(box.bottom, 400, accuracy: 0.001)
        XCTAssertEqual(box.width, 500, accuracy: 0.001)
        XCTAssertEqual(box.right, 600, accuracy: 0.001)

        // Unsorted lines: paragraph 1 (two close lines), paragraph 2 (wide gap)
        let enLines = [
            OcrLineBox(text: "Second para.", left: 50, top: 200, height: 20),
            OcrLineBox(text: "world.", left: 50, top: 125, height: 20),
            OcrLineBox(text: "Hello", left: 50, top: 100, height: 20)
        ]
        XCTAssertEqual(PageOcrLayout.merge(enLines, target: .reading), "Hello world.\n\nSecond para.")

        let zhLines = [
            OcrLineBox(text: "第二段。", left: 50, top: 200, height: 20),
            OcrLineBox(text: "数字经济", left: 50, top: 125, height: 20),
            OcrLineBox(text: "随着", left: 50, top: 100, height: 20)
        ]
        XCTAssertEqual(PageOcrLayout.merge(zhLines, target: .translation), "随着数字经济\n\n第二段。")
    }

    func testValidateLanguageAndLengthLimits() {
        XCTAssertEqual(
            PageOcrLayout.validate("12345 English only", target: .translation, existingBody: nil),
            "未识别到中文文字，请重拍或重选。"
        )
        XCTAssertNil(PageOcrLayout.validate("这是中文", target: .translation, existingBody: nil))

        XCTAssertEqual(
            PageOcrLayout.validate("只有中文 12345", target: .reading, existingBody: nil),
            "未识别到英文文字，请重拍或重选。"
        )
        XCTAssertNil(PageOcrLayout.validate("Hello", target: .reading, existingBody: nil))

        let existing = String(repeating: "a", count: 49_990)
        // Appending adds +2 for "\n\n": 49990 + 2 + 8 = 50000 (OK), +9 = 50001 (error)
        XCTAssertNil(PageOcrLayout.validate("1234567a", target: .reading, existingBody: existing))
        XCTAssertEqual(
            PageOcrLayout.validate("12345678a", target: .reading, existingBody: existing),
            "识别结果总长度超过限制，请缩小拍摄范围或分篇保存。"
        )
    }

    // MARK: - Android PageOcrLayoutTest 6 Cases (Layout 2.0)

    func testSingleColumnJoinsWrappedLinesAndMergesHyphenatedWords() {
        let block1 = OcrBlock(left: 50, top: 100, right: 700, bottom: 170, lines: [
            OcrLineBox(text: "Reading compre-", left: 50, top: 100, height: 30, width: 650),
            OcrLineBox(text: "hension requires steady", left: 50, top: 140, height: 30, width: 650)
        ])
        let block2 = OcrBlock(left: 50, top: 180, right: 700, bottom: 210, lines: [
            OcrLineBox(text: "practice every day.", left: 50, top: 180, height: 30, width: 650)
        ])
        let block3 = OcrBlock(left: 50, top: 270, right: 700, bottom: 340, lines: [
            OcrLineBox(text: "Students should focus on con-", left: 50, top: 270, height: 30, width: 650),
            OcrLineBox(text: "textual clues rather than isolated words.", left: 50, top: 310, height: 30, width: 650)
        ])

        let formatted = PageOcrLayout.formatBlocks([block1, block2, block3], target: .reading)

        XCTAssertEqual(
            formatted,
            "Reading comprehension requires steady practice every day.\n\n"
                + "Students should focus on contextual clues rather than isolated words."
        )
    }

    func testHyphenOnlyMergesWhenNextLineStartsWithLowercase() {
        let block = OcrBlock(left: 40, top: 100, right: 600, bottom: 170, lines: [
            OcrLineBox(text: "Students prepare for CET-", left: 40, top: 100, height: 30, width: 560),
            OcrLineBox(text: "6 exams all semester.", left: 40, top: 140, height: 30, width: 560)
        ])

        let formatted = PageOcrLayout.formatBlocks([block], target: .reading)

        XCTAssertEqual(formatted, "Students prepare for CET- 6 exams all semester.")
    }

    func testTwoColumnLayoutOrdersLeftColumnBeforeRightColumn() {
        let title = OcrBlock.of(left: 100, top: 30, right: 880, bottom: 68, texts: ["Passage One"])
        let left1 = OcrBlock.of(left: 40, top: 110, right: 440, bottom: 190, texts: [
            "Modern cities face grow-",
            "ing traffic pressure."
        ])
        let right1 = OcrBlock.of(left: 520, top: 110, right: 920, bottom: 190, texts: [
            "Public transit offers a",
            "cleaner daily commute."
        ])
        let left2 = OcrBlock.of(left: 40, top: 240, right: 440, bottom: 320, texts: [
            "Planners now invest in",
            "safer bike lanes."
        ])
        let right2 = OcrBlock.of(left: 520, top: 240, right: 920, bottom: 320, texts: [
            "Walkable streets also",
            "boost local shops."
        ])

        // Intentionally supply blocks in interleaved row order as OCR engines often emit.
        let formatted = PageOcrLayout.formatBlocks([right1, left1, title, right2, left2], target: .reading)

        XCTAssertEqual(
            formatted,
            "Passage One\n\n"
                + "Modern cities face growing traffic pressure.\n\n"
                + "Planners now invest in safer bike lanes.\n\n"
                + "Public transit offers a cleaner daily commute.\n\n"
                + "Walkable streets also boost local shops."
        )
    }

    func testEdgePageNumbersAreFilteredWithoutRemovingRealBodyLines() {
        let topHeader = OcrBlock.of(left: 420, top: 10, right: 520, bottom: 36, texts: ["Page 3"])
        let body1 = OcrBlock.of(left: 60, top: 120, right: 880, bottom: 190, texts: [
            "In 2024, researchers studied 3 groups of college readers.",
            "Their findings highlighted steady vocabulary growth."
        ])
        let midNumber = OcrBlock.of(left: 60, top: 250, right: 240, bottom: 280, texts: ["1984"])
        let body2 = OcrBlock.of(left: 60, top: 340, right: 880, bottom: 410, texts: [
            "Chapter 2 discusses long-term retention strategies."
        ])
        let bottomFooter = OcrBlock.of(left: 430, top: 860, right: 510, bottom: 888, texts: ["- 2 -"])

        let formatted = PageOcrLayout.formatBlocks(
            [topHeader, body1, midNumber, body2, bottomFooter],
            target: .reading
        )

        XCTAssertFalse(formatted.contains("Page 3"))
        XCTAssertFalse(formatted.contains("- 2 -"))
        XCTAssertTrue(formatted.contains("In 2024, researchers studied 3 groups of college readers."))
        XCTAssertTrue(formatted.contains("1984"))
        XCTAssertTrue(formatted.contains("Chapter 2 discusses long-term retention strategies."))
    }

    func testOutputIsCompatibleWithReadingArticleParagraphBreakSplitting() {
        let p1a = OcrBlock(left: 50, top: 100, right: 750, bottom: 130, lines: [
            OcrLineBox(text: "First paragraph begins with a wrap-", left: 50, top: 100, height: 30, width: 700)
        ])
        let p1b = OcrBlock(left: 50, top: 140, right: 750, bottom: 170, lines: [
            OcrLineBox(text: "ped word across blocks.", left: 50, top: 140, height: 30, width: 700)
        ])
        let p2 = OcrBlock(left: 50, top: 230, right: 750, bottom: 260, lines: [
            OcrLineBox(text: "Second paragraph stands on its own.", left: 50, top: 230, height: 30, width: 700)
        ])
        let p3 = OcrBlock(left: 50, top: 320, right: 750, bottom: 350, lines: [
            OcrLineBox(text: "Third paragraph concludes the article.", left: 50, top: 320, height: 30, width: 700)
        ])

        let formatted = PageOcrLayout.formatBlocks([p1a, p1b, p2, p3], target: .reading)
        let article = ReadingArticle(id: "ocr-sample", title: "Sample", body: formatted)
        let paragraphs = article.paragraphs()

        XCTAssertEqual(paragraphs.count, 3)
        XCTAssertEqual(paragraphs[0].text, "First paragraph begins with a wrapped word across blocks.")
        XCTAssertEqual(paragraphs[1].text, "Second paragraph stands on its own.")
        XCTAssertEqual(paragraphs[2].text, "Third paragraph concludes the article.")
    }

    func testChineseBlocksJoinLinesWithoutExtraSpacesAndFilterChinesePageMarkers() {
        let topNoise = OcrBlock.of(left: 380, top: 12, right: 500, bottom: 38, texts: ["第 3 页"])
        let p1 = OcrBlock.of(left: 60, top: 120, right: 820, bottom: 200, texts: [
            "近年来，绿色出行逐渐成为城市居民的",
            "日常选择。"
        ])
        let p2 = OcrBlock.of(left: 60, top: 280, right: 820, bottom: 360, texts: [
            "越来越多的年轻人选择乘坐地铁或骑行",
            "上下班。"
        ])
        let bottomNoise = OcrBlock.of(left: 420, top: 840, right: 480, bottom: 868, texts: ["3"])

        let formatted = PageOcrLayout.formatBlocks([topNoise, p1, p2, bottomNoise], target: .translation)

        XCTAssertEqual(
            formatted,
            "近年来，绿色出行逐渐成为城市居民的日常选择。\n\n"
                + "越来越多的年轻人选择乘坐地铁或骑行上下班。"
        )
    }

    // MARK: - Additional Layout & Image Encoding Checks

    func testMergeEnhancementsPageMarkersAndJpegBase64Encoding() throws {
        // Legacy 4-arg merge also enjoys hyphen merging and Chinese ASCII boundary spacing.
        let legacyHyphen = [
            OcrLineBox(text: "Reading compre-", left: 40, top: 100, height: 24),
            OcrLineBox(text: "hension matters.", left: 40, top: 130, height: 24)
        ]
        XCTAssertEqual(PageOcrLayout.merge(legacyHyphen, target: .reading), "Reading comprehension matters.")

        let zhAsciiBoundary = OcrBlock.of(left: 50, top: 100, right: 700, bottom: 180, texts: [
            "备考CET",
            "6需要坚持"
        ])
        XCTAssertEqual(PageOcrLayout.formatBlocks([zhAsciiBoundary], target: .translation), "备考CET 6需要坚持")

        XCTAssertTrue(PageOcrLayout.isPageMarkerText("Page 3 of 10"))
        XCTAssertTrue(PageOcrLayout.isPageMarkerText("- 12 -"))
        XCTAssertTrue(PageOcrLayout.isPageMarkerText("第 2 页 / 8 页"))
        XCTAssertFalse(PageOcrLayout.isPageMarkerText("Chapter 2"))

        // Vision-like boxes with real widths go through block grouping + two-column layout.
        let visionTwoCol = [
            OcrLineBox(text: "Right col line 1", left: 520, top: 110, height: 28, width: 380),
            OcrLineBox(text: "Left col wrap-", left: 40, top: 110, height: 28, width: 380),
            OcrLineBox(text: "ped line 2.", left: 40, top: 146, height: 28, width: 380),
            OcrLineBox(text: "Right col line 2.", left: 520, top: 146, height: 28, width: 380)
        ]
        XCTAssertEqual(
            PageOcrLayout.merge(visionTwoCol, target: .reading),
            "Left col wrapped line 2.\n\nRight col line 1 Right col line 2."
        )

        // Large image (> 1600) encodes to valid non-wrapped JPEG Base64 with longest edge <= 1600.
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1.0
        let largeImage = UIGraphicsImageRenderer(size: CGSize(width: 2400, height: 1200), format: format).image { ctx in
            UIColor.white.setFill()
            ctx.fill(CGRect(x: 0, y: 0, width: 2400, height: 1200))
            UIColor.black.setFill()
            ctx.fill(CGRect(x: 100, y: 100, width: 400, height: 200))
        }
        let encoded = try XCTUnwrap(PageOcrService.encodeJpegBase64(from: largeImage))
        XCTAssertFalse(encoded.isEmpty)
        XCTAssertFalse(encoded.contains("\n"))
        XCTAssertFalse(encoded.contains("\r"))
        let decodedData = try XCTUnwrap(Data(base64Encoded: encoded))
        XCTAssertLessThanOrEqual(decodedData.count, PageOcrService.organizeImageMaxBytes)
        let decodedImage = try XCTUnwrap(UIImage(data: decodedData))
        XCTAssertLessThanOrEqual(max(decodedImage.size.width * decodedImage.scale, decodedImage.size.height * decodedImage.scale), 1600)
    }
}
