import CoreGraphics
import Foundation
import ImageIO
import UIKit
import Vision

struct OcrLineBox: Hashable {
    let text: String
    let left: Double
    let top: Double
    let height: Double
    let width: Double

    init(text: String, left: Double, top: Double, height: Double, width: Double = 0) {
        self.text = text
        self.left = left
        self.top = top
        self.height = height
        self.width = width
    }

    var right: Double { left + width }
    var bottom: Double { top + height }
    var hasBounds: Bool { right > left && bottom > top }
}

struct OcrBlock: Hashable {
    let left: Double
    let top: Double
    let right: Double
    let bottom: Double
    let lines: [OcrLineBox]

    var width: Double { max(0, right - left) }
    var height: Double { max(0, bottom - top) }
    var hasBounds: Bool { right > left && bottom > top }

    static func of(
        left: Double,
        top: Double,
        right: Double,
        bottom: Double,
        texts: [String]
    ) -> OcrBlock {
        let clean = texts.compactMap { raw -> String? in
            let trimmed = TextMetrics.trimSpace(raw)
            return trimmed.isEmpty ? nil : trimmed
        }
        var ocrLines: [OcrLineBox] = []
        let count = clean.count
        if count > 0 {
            ocrLines.reserveCapacity(count)
            let totalHeight = max(0, bottom - top)
            let blockWidth = max(0, right - left)
            for index in 0..<count {
                let lineTop = top + (totalHeight * Double(index)) / Double(count)
                let lineBottom = top + (totalHeight * Double(index + 1)) / Double(count)
                ocrLines.append(
                    OcrLineBox(
                        text: clean[index],
                        left: left,
                        top: lineTop,
                        height: max(0, lineBottom - lineTop),
                        width: blockWidth
                    )
                )
            }
        }
        return OcrBlock(left: left, top: top, right: right, bottom: bottom, lines: ocrLines)
    }
}

enum PageOcrLayout {
    static let maxTotalLength = 50_000

    private static let englishLetterRegex = try! NSRegularExpression(pattern: "[A-Za-z]", options: [])
    private static let numericPageMarkerRegex = try! NSRegularExpression(
        pattern: "^[-—–·•|\\[\\]()\\s]*\\d{1,4}(?:\\s*/\\s*\\d{1,4})?[-—–·•|\\[\\]().\\s]*$",
        options: []
    )
    private static let englishPageMarkerRegex = try! NSRegularExpression(
        pattern: "^(?:page|pg\\.?|p\\.?)\\s*\\d{1,4}(?:\\s*(?:of|/)\\s*\\d{1,4})?$",
        options: [.caseInsensitive]
    )
    private static let chinesePageMarkerRegex = try! NSRegularExpression(
        pattern: "^第\\s*\\d{1,4}\\s*页(?:\\s*[/／共]\\s*\\d{1,4}\\s*页?)?$",
        options: []
    )

    private struct OrderedBlock {
        let block: OcrBlock
        let columnId: Int
    }

    /// Vision boundingBox 原点在左下角（归一化 0...1），转为左上角原点像素坐标。→ §3.2
    static func fromVision(boundingBox: CGRect, text: String, imageSize: CGSize) -> OcrLineBox {
        let width = Double(imageSize.width)
        let height = Double(imageSize.height)
        let top = (1.0 - Double(boundingBox.origin.y + boundingBox.size.height)) * height
        let left = Double(boundingBox.origin.x) * width
        let boxWidth = Double(boundingBox.size.width) * width
        let boxHeight = Double(boundingBox.size.height) * height
        return OcrLineBox(text: text, left: left, top: top, height: boxHeight, width: boxWidth)
    }

    /// 1:1 对齐 Android PageOcr.continuesParagraph。→ §3.2
    static func continuesParagraph(above: OcrLineBox, below: OcrLineBox) -> Bool {
        let lineHeight = (above.height + below.height) / 2.0
        guard lineHeight > 0 else { return false }
        let gap = below.top - above.bottom
        return gap >= -lineHeight / 4.0
            && gap <= lineHeight * 1.2
            && abs(above.left - below.left) <= lineHeight * 0.8
    }

    /// 对齐 Android PageOcr.isPageMarkerText：识别页码/页眉页脚文本。
    static func isPageMarkerText(_ text: String) -> Bool {
        let trimmed = TextMetrics.trimSpace(text)
        guard !trimmed.isEmpty, !trimmed.contains("\n"), !trimmed.contains("\r") else {
            return false
        }
        let range = NSRange(location: 0, length: (trimmed as NSString).length)
        return numericPageMarkerRegex.firstMatch(in: trimmed, options: [], range: range) != nil
            || englishPageMarkerRegex.firstMatch(in: trimmed, options: [], range: range) != nil
            || chinesePageMarkerRegex.firstMatch(in: trimmed, options: [], range: range) != nil
    }

    /// 对齐 Android PageOcr.formatBlocks：边缘页码过滤 -> 双栏/通栏阅读流排序 -> 段内拼接与连字符处理。
    static func formatBlocks(_ rawBlocks: [OcrBlock], target: PageImportTarget) -> String {
        guard !rawBlocks.isEmpty else { return "" }
        let chinese = (target == .translation)
        let cleaned = sanitizeBlocks(rawBlocks)
        guard !cleaned.isEmpty else { return "" }

        let hasBounds = cleaned.contains(where: \.hasBounds)
        if !hasBounds {
            let plain = cleaned.map { OrderedBlock(block: $0, columnId: 1) }
            return joinOrderedBlocks(plain, chinese: chinese, medianLineHeight: 0)
        }

        let medianLineHeight = computeMedianLineHeight(cleaned)
        var filtered = filterEdgeNoise(cleaned, medianLineHeight: medianLineHeight)
        if filtered.isEmpty {
            filtered = cleaned
        }
        let ordered = orderBlocksByReadingFlow(filtered, medianLineHeight: medianLineHeight)
        return joinOrderedBlocks(ordered, chinese: chinese, medianLineHeight: medianLineHeight)
    }

    /// 排序 + 同段连接 + 段间 "\n\n"。
    /// 当行框带真实宽度（hasBounds）时走 OcrBlock 排版管线；
    /// 兼容旧版 4 参 OcrLineBox（width == 0），并享受连字符与中文 ASCII 空格拼接增强。
    static func merge(_ lines: [OcrLineBox], target: PageImportTarget) -> String {
        let cleaned: [OcrLineBox] = lines.compactMap { box in
            let trimmed = TextMetrics.trimSpace(box.text)
            guard !trimmed.isEmpty else { return nil }
            return OcrLineBox(text: trimmed, left: box.left, top: box.top, height: box.height, width: box.width)
        }
        guard !cleaned.isEmpty else { return "" }

        if cleaned.contains(where: \.hasBounds) {
            let blocks = groupLinesIntoBlocks(cleaned)
            return formatBlocks(blocks, target: target)
        }

        let chinese = (target == .translation)
        let lineSeparator = chinese ? "" : " "

        // 先严格按 (top, left) 排序，再把同一视觉行的碎片按 left 升序聚类合并。
        let byTop = cleaned.sorted { lhs, rhs in
            if lhs.top != rhs.top { return lhs.top < rhs.top }
            return lhs.left < rhs.left
        }

        var rows: [[OcrLineBox]] = []
        for box in byTop {
            if let lastRow = rows.last, let anchor = lastRow.first {
                let threshold = min(anchor.height, box.height) * 0.4
                let sameRow = threshold > 0
                    && abs(box.top - anchor.top) <= threshold
                    && !lastRow.contains(where: { abs($0.left - box.left) <= 1.0 })
                if sameRow {
                    rows[rows.count - 1].append(box)
                    continue
                }
            }
            rows.append([box])
        }

        let orderedLines: [OcrLineBox] = rows.map { row in
            if row.count == 1 { return row[0] }
            let sortedRow = row.sorted { $0.left < $1.left }
            let combinedText = sortedRow.map(\.text).joined(separator: lineSeparator)
            let minTop = sortedRow.map(\.top).min() ?? sortedRow[0].top
            let maxBottom = sortedRow.map(\.bottom).max() ?? sortedRow[0].bottom
            return OcrLineBox(
                text: combinedText,
                left: sortedRow[0].left,
                top: minTop,
                height: maxBottom - minTop
            )
        }

        var result = ""
        var previous: OcrLineBox?
        for line in orderedLines {
            if let prev = previous {
                if continuesParagraph(above: prev, below: line) {
                    appendJoinedLine(&result, next: line.text, chinese: chinese)
                } else {
                    result += "\n\n"
                    result += line.text
                }
            } else {
                result += line.text
            }
            previous = line
        }
        return result
    }

    /// 将 Vision 返回的逐行 OcrLineBox 按列重叠与行距聚类为 OcrBlock，供 formatBlocks 处理。
    static func groupLinesIntoBlocks(_ lines: [OcrLineBox]) -> [OcrBlock] {
        let sorted = lines.sorted { lhs, rhs in
            if lhs.top != rhs.top { return lhs.top < rhs.top }
            return lhs.left < rhs.left
        }
        var grouped: [[OcrLineBox]] = []
        for line in sorted {
            guard line.hasBounds else {
                grouped.append([line])
                continue
            }
            var attachedIndex: Int? = nil
            for idx in stride(from: grouped.count - 1, through: 0, by: -1) {
                guard let lastLine = grouped[idx].last, lastLine.hasBounds else { continue }
                if isPageMarkerText(lastLine.text) || isPageMarkerText(line.text) {
                    continue
                }
                let lineHeight = (lastLine.height + line.height) / 2.0
                guard lineHeight > 0 else { continue }
                let gap = line.top - lastLine.bottom
                if gap < -lineHeight * 0.25 || gap > lineHeight * 0.85 {
                    continue
                }
                let overlap = min(lastLine.right, line.right) - max(lastLine.left, line.left)
                guard overlap > 0 else { continue }
                let indentDelta = line.left - lastLine.left
                if indentDelta >= lineHeight * 0.9 || lastLine.left - line.left > lineHeight * 3.0 {
                    continue
                }
                if grouped[idx].count == 1
                    && lastLine.right - line.right > lineHeight * 3.0
                    && abs(lastLine.left - line.left) > lineHeight * 1.5 {
                    continue
                }
                attachedIndex = idx
                break
            }
            if let idx = attachedIndex {
                grouped[idx].append(line)
            } else {
                grouped.append([line])
            }
        }

        return grouped.map { group in
            var minLeft = Double.greatestFiniteMagnitude
            var minTop = Double.greatestFiniteMagnitude
            var maxRight = -Double.greatestFiniteMagnitude
            var maxBottom = -Double.greatestFiniteMagnitude
            var anyBounds = false
            for line in group where line.hasBounds {
                anyBounds = true
                minLeft = min(minLeft, line.left)
                minTop = min(minTop, line.top)
                maxRight = max(maxRight, line.right)
                maxBottom = max(maxBottom, line.bottom)
            }
            if anyBounds {
                return OcrBlock(left: minLeft, top: minTop, right: maxRight, bottom: maxBottom, lines: group)
            }
            return OcrBlock(left: 0, top: 0, right: 0, bottom: 0, lines: group)
        }
    }

    /// 校验识别结果语种与总长度上限；合法返回 nil，否则返回中文错误文案。→ §3.2
    static func validate(_ text: String, target: PageImportTarget, existingBody: String?) -> String? {
        switch target {
        case .translation:
            if !text.unicodeScalars.contains(where: isHanScalar) {
                return "未识别到中文文字，请重拍或重选。"
            }
        case .reading:
            let nsText = text as NSString
            let range = NSRange(location: 0, length: nsText.length)
            if englishLetterRegex.firstMatch(in: text, options: [], range: range) == nil {
                return "未识别到英文文字，请重拍或重选。"
            }
        }

        let existingLength = existingBody.map { $0.utf16.count + 2 } ?? 0
        if existingLength + text.utf16.count > maxTotalLength {
            return "识别结果总长度超过限制，请缩小拍摄范围或分篇保存。"
        }
        return nil
    }

    // MARK: - Block Layout Pipeline (1:1 Android PageOcr)

    private static func sanitizeBlocks(_ rawBlocks: [OcrBlock]) -> [OcrBlock] {
        var result: [OcrBlock] = []
        result.reserveCapacity(rawBlocks.count)
        for block in rawBlocks {
            guard !block.lines.isEmpty else { continue }
            var cleanLines: [OcrLineBox] = []
            cleanLines.reserveCapacity(block.lines.count)
            for line in block.lines {
                let trimmed = TextMetrics.trimSpace(line.text)
                if !trimmed.isEmpty {
                    cleanLines.append(
                        OcrLineBox(
                            text: trimmed,
                            left: line.left,
                            top: line.top,
                            height: line.height,
                            width: line.width
                        )
                    )
                }
            }
            if !cleanLines.isEmpty {
                result.append(
                    OcrBlock(
                        left: block.left,
                        top: block.top,
                        right: block.right,
                        bottom: block.bottom,
                        lines: cleanLines
                    )
                )
            }
        }
        return result
    }

    private static func computeMedianLineHeight(_ blocks: [OcrBlock]) -> Double {
        var heights: [Double] = []
        for block in blocks {
            for line in block.lines {
                if line.hasBounds && line.height > 0 {
                    heights.append(line.height)
                }
            }
            if heights.isEmpty && block.hasBounds && !block.lines.isEmpty {
                heights.append(max(1.0, block.height / Double(block.lines.count)))
            }
        }
        guard !heights.isEmpty else { return 24.0 }
        heights.sort()
        return heights[heights.count / 2]
    }

    private static func filterEdgeNoise(_ blocks: [OcrBlock], medianLineHeight: Double) -> [OcrBlock] {
        guard blocks.count >= 2 else { return blocks }
        var pageTop = Double.greatestFiniteMagnitude
        var pageBottom = -Double.greatestFiniteMagnitude
        for block in blocks where block.hasBounds {
            pageTop = min(pageTop, block.top)
            pageBottom = max(pageBottom, block.bottom)
        }
        let pageHeight = pageBottom - pageTop
        guard pageHeight > 0 else { return blocks }

        var kept: [OcrBlock] = []
        kept.reserveCapacity(blocks.count)
        for block in blocks {
            if isEdgePageNoise(
                block,
                pageTop: pageTop,
                pageBottom: pageBottom,
                pageHeight: pageHeight,
                medianLineHeight: medianLineHeight
            ) {
                continue
            }
            kept.append(block)
        }
        return kept.isEmpty ? blocks : kept
    }

    private static func isEdgePageNoise(
        _ block: OcrBlock,
        pageTop: Double,
        pageBottom: Double,
        pageHeight: Double,
        medianLineHeight: Double
    ) -> Bool {
        guard block.hasBounds, block.lines.count == 1 else { return false }
        let text = block.lines[0].text
        guard text.utf16.count <= 24, isPageMarkerText(text) else { return false }

        let edgeBand = max(pageHeight * 0.12, medianLineHeight * 2.2)
        let atTopEdge = block.top <= pageTop + pageHeight * 0.08
            && block.bottom <= pageTop + edgeBand
            && block.bottom < pageBottom - pageHeight * 0.15
        let atBottomEdge = block.bottom >= pageBottom - pageHeight * 0.08
            && block.top >= pageBottom - edgeBand
            && block.top > pageTop + pageHeight * 0.15
        return atTopEdge || atBottomEdge
    }

    private static func orderBlocksByReadingFlow(
        _ blocks: [OcrBlock],
        medianLineHeight: Double
    ) -> [OrderedBlock] {
        if blocks.count <= 1 {
            return wrapColumn(blocks, columnId: 1)
        }

        var pageLeft = Double.greatestFiniteMagnitude
        var pageRight = -Double.greatestFiniteMagnitude
        for block in blocks where block.hasBounds {
            pageLeft = min(pageLeft, block.left)
            pageRight = max(pageRight, block.right)
        }
        let pageWidth = pageRight - pageLeft
        guard pageWidth > 0 else {
            return wrapColumn(sortSingleColumn(blocks, medianLineHeight: medianLineHeight), columnId: 1)
        }

        var candidates: [OcrBlock] = []
        var spanning: [OcrBlock] = []
        let spanningThreshold = pageWidth * 0.62
        for block in blocks {
            if block.hasBounds && block.width > spanningThreshold {
                spanning.append(block)
            } else {
                candidates.append(block)
            }
        }

        guard let gutterX = detectColumnGutter(
            candidates,
            pageLeft: pageLeft,
            pageRight: pageRight,
            pageWidth: pageWidth,
            medianLineHeight: medianLineHeight
        ) else {
            return wrapColumn(sortSingleColumn(blocks, medianLineHeight: medianLineHeight), columnId: 1)
        }

        var leftCol: [OcrBlock] = []
        var rightCol: [OcrBlock] = []
        for block in candidates {
            let centerX = (block.left + block.right) / 2.0
            if centerX < gutterX {
                leftCol.append(block)
            } else {
                rightCol.append(block)
            }
        }
        guard !leftCol.isEmpty, !rightCol.isEmpty else {
            return wrapColumn(sortSingleColumn(blocks, medianLineHeight: medianLineHeight), columnId: 1)
        }

        let leftTop = minTop(leftCol)
        let leftBottom = maxBottom(leftCol)
        let rightTop = minTop(rightCol)
        let rightBottom = maxBottom(rightCol)
        let verticalOverlap = min(leftBottom, rightBottom) - max(leftTop, rightTop)
        guard verticalOverlap > 0 else {
            return wrapColumn(sortSingleColumn(blocks, medianLineHeight: medianLineHeight), columnId: 1)
        }

        let columnTop = min(leftTop, rightTop)
        let columnBottom = max(leftBottom, rightBottom)
        var topSpanning: [OcrBlock] = []
        var bottomSpanning: [OcrBlock] = []
        for span in spanning {
            if span.bottom <= columnTop + medianLineHeight * 0.5 {
                topSpanning.append(span)
            } else if span.top >= columnBottom - medianLineHeight * 0.5 {
                bottomSpanning.append(span)
            } else {
                return wrapColumn(sortSingleColumn(blocks, medianLineHeight: medianLineHeight), columnId: 1)
            }
        }

        var ordered: [OrderedBlock] = []
        ordered.reserveCapacity(blocks.count)
        ordered.append(contentsOf: wrapColumn(sortSingleColumn(topSpanning, medianLineHeight: medianLineHeight), columnId: 0))
        ordered.append(contentsOf: wrapColumn(sortSingleColumn(leftCol, medianLineHeight: medianLineHeight), columnId: 1))
        ordered.append(contentsOf: wrapColumn(sortSingleColumn(rightCol, medianLineHeight: medianLineHeight), columnId: 2))
        ordered.append(contentsOf: wrapColumn(sortSingleColumn(bottomSpanning, medianLineHeight: medianLineHeight), columnId: 3))
        return ordered
    }

    private static func detectColumnGutter(
        _ candidates: [OcrBlock],
        pageLeft: Double,
        pageRight: Double,
        pageWidth: Double,
        medianLineHeight: Double
    ) -> Double? {
        guard candidates.count >= 2 else { return nil }
        var intervals: [(left: Double, right: Double)] = []
        intervals.reserveCapacity(candidates.count)
        for block in candidates where block.hasBounds {
            intervals.append((block.left, block.right))
        }
        guard intervals.count >= 2 else { return nil }
        intervals.sort { lhs, rhs in
            if lhs.left != rhs.left { return lhs.left < rhs.left }
            return lhs.right < rhs.right
        }

        let centralMin = pageLeft + pageWidth * 0.25
        let centralMax = pageLeft + pageWidth * 0.75
        let minGapWidth = max(medianLineHeight * 0.5, pageWidth * 0.02)

        var mergedRight = intervals[0].right
        var bestGapWidth = -1.0
        var bestGutter: Double? = nil

        for index in 1..<intervals.count {
            let nextLeft = intervals[index].left
            let nextRight = intervals[index].right
            if nextLeft > mergedRight {
                let gapWidth = nextLeft - mergedRight
                let mid = (mergedRight + nextLeft) / 2.0
                if gapWidth >= minGapWidth && mid >= centralMin && mid <= centralMax && gapWidth > bestGapWidth {
                    bestGapWidth = gapWidth
                    bestGutter = mid
                }
            }
            if nextRight > mergedRight {
                mergedRight = nextRight
            }
        }
        return bestGutter
    }

    private static func sortSingleColumn(_ blocks: [OcrBlock], medianLineHeight: Double) -> [OcrBlock] {
        guard blocks.count > 1 else { return blocks }
        let sorted = blocks.sorted { lhs, rhs in
            if lhs.top != rhs.top { return lhs.top < rhs.top }
            return lhs.left < rhs.left
        }

        var result: [OcrBlock] = []
        result.reserveCapacity(sorted.count)
        var row: [OcrBlock] = [sorted[0]]
        var rowTop = sorted[0].top
        var rowBottom = sorted[0].bottom
        let rowTolerance = max(8.0, medianLineHeight * 0.5)

        for index in 1..<sorted.count {
            let curr = sorted[index]
            let currCenterY = (curr.top + curr.bottom) / 2.0
            let sameRow = curr.top <= rowTop + rowTolerance
                || (currCenterY >= rowTop && currCenterY <= rowBottom)
            if sameRow {
                row.append(curr)
                rowBottom = max(rowBottom, curr.bottom)
            } else {
                row.sort { lhs, rhs in
                    if lhs.left != rhs.left { return lhs.left < rhs.left }
                    return lhs.top < rhs.top
                }
                result.append(contentsOf: row)
                row = [curr]
                rowTop = curr.top
                rowBottom = curr.bottom
            }
        }
        row.sort { lhs, rhs in
            if lhs.left != rhs.left { return lhs.left < rhs.left }
            return lhs.top < rhs.top
        }
        result.append(contentsOf: row)
        return result
    }

    private static func wrapColumn(_ blocks: [OcrBlock], columnId: Int) -> [OrderedBlock] {
        blocks.map { OrderedBlock(block: $0, columnId: columnId) }
    }

    private static func minTop(_ blocks: [OcrBlock]) -> Double {
        blocks.reduce(Double.greatestFiniteMagnitude) { min($0, $1.top) }
    }

    private static func maxBottom(_ blocks: [OcrBlock]) -> Double {
        blocks.reduce(-Double.greatestFiniteMagnitude) { max($0, $1.bottom) }
    }

    private static func joinOrderedBlocks(
        _ orderedBlocks: [OrderedBlock],
        chinese: Bool,
        medianLineHeight: Double
    ) -> String {
        var page = ""
        var previousLine: OcrLineBox? = nil
        var previousColumnId = -1
        var previousBlockIndex = -1

        for (blockIndex, ordered) in orderedBlocks.enumerated() {
            let columnId = ordered.columnId
            for line in ordered.block.lines {
                let text = line.text
                if text.isEmpty { continue }
                if let prev = previousLine {
                    let sameColumn = (columnId == previousColumnId)
                    let sameBlock = (blockIndex == previousBlockIndex)
                    if shouldJoinIntoSameParagraph(
                        previous: prev,
                        next: line,
                        sameColumn: sameColumn,
                        sameBlock: sameBlock,
                        chinese: chinese,
                        medianLineHeight: medianLineHeight
                    ) {
                        appendJoinedLine(&page, next: text, chinese: chinese)
                    } else {
                        page += "\n\n"
                        page += text
                    }
                } else {
                    page += text
                }
                previousLine = line
                previousColumnId = columnId
                previousBlockIndex = blockIndex
            }
        }
        return page
    }

    private static func shouldJoinIntoSameParagraph(
        previous: OcrLineBox,
        next: OcrLineBox,
        sameColumn: Bool,
        sameBlock: Bool,
        chinese: Bool,
        medianLineHeight: Double
    ) -> Bool {
        guard sameColumn else { return false }
        if !previous.hasBounds || !next.hasBounds {
            return sameBlock || !endsWithTerminalPunctuation(previous.text, chinese: chinese)
        }

        var lineHeight = (previous.height + next.height) / 2.0
        if lineHeight <= 0 {
            lineHeight = medianLineHeight > 0 ? medianLineHeight : 24.0
        }
        let gap = next.top - previous.bottom
        let indentDelta = next.left - previous.left
        let terminalEnd = endsWithTerminalPunctuation(previous.text, chinese: chinese)

        if sameBlock {
            if gap > lineHeight * 1.15 {
                return false
            }
            if terminalEnd && (gap > lineHeight * 0.8 || indentDelta >= lineHeight * 0.9) {
                return false
            }
            return true
        }

        if terminalEnd {
            return false
        }
        let normalVerticalSpacing = gap >= -lineHeight * 0.35 && gap <= lineHeight * 0.85
        let indentedParagraphStart = indentDelta >= lineHeight * 0.9
        return normalVerticalSpacing && !indentedParagraphStart
    }

    private static func endsWithTerminalPunctuation(_ text: String, chinese: Bool) -> Bool {
        guard let last = text.unicodeScalars.last else { return false }
        switch last {
        case ".", "?", "!", ":", "\"", "\u{201D}", "\u{2019}":
            return true
        case "。", "？", "！", "：":
            return chinese
        default:
            return false
        }
    }

    private static func appendJoinedLine(_ target: inout String, next: String, chinese: Bool) {
        if target.isEmpty {
            target += next
            return
        }
        if !chinese && shouldMergeHyphenatedWord(target: target, next: next) {
            target.removeLast()
            target += next
            return
        }
        if chinese {
            if let prevChar = target.unicodeScalars.last,
               let nextChar = next.unicodeScalars.first,
               isAsciiWordChar(prevChar) && isAsciiWordChar(nextChar) {
                target.append(" ")
            }
            target += next
        } else {
            target.append(" ")
            target += next
        }
    }

    private static func shouldMergeHyphenatedWord(target: String, next: String) -> Bool {
        let scalars = target.unicodeScalars
        guard scalars.count >= 2, let nextFirst = next.unicodeScalars.first else {
            return false
        }
        let lastIndex = scalars.index(before: scalars.endIndex)
        let last = scalars[lastIndex]
        guard last == "-" || last == "\u{00AD}" || last == "\u{2010}" else {
            return false
        }
        let beforeHyphen = scalars[scalars.index(before: lastIndex)]
        let beforeIsAsciiLetter = (beforeHyphen >= "A" && beforeHyphen <= "Z")
            || (beforeHyphen >= "a" && beforeHyphen <= "z")
        let nextIsAsciiLower = (nextFirst >= "a" && nextFirst <= "z")
        return beforeIsAsciiLetter && nextIsAsciiLower
    }

    private static func isAsciiWordChar(_ scalar: Unicode.Scalar) -> Bool {
        (scalar >= "A" && scalar <= "Z")
            || (scalar >= "a" && scalar <= "z")
            || (scalar >= "0" && scalar <= "9")
    }

    private static func isHanScalar(_ scalar: Unicode.Scalar) -> Bool {
        let v = scalar.value
        return (v >= 0x4E00 && v <= 0x9FFF)
            || (v >= 0x3400 && v <= 0x4DBF)
            || (v >= 0x20000 && v <= 0x2A6DF)
            || (v >= 0x2A700 && v <= 0x2B73F)
            || (v >= 0x2B740 && v <= 0x2B81F)
            || (v >= 0x2B820 && v <= 0x2CEAF)
            || (v >= 0x2CEB0 && v <= 0x2EBEF)
            || (v >= 0x30000 && v <= 0x3134F)
            || (v >= 0xF900 && v <= 0xFAFF)
            || (v >= 0x2F800 && v <= 0x2FA1F)
            || (v >= 0x2E80 && v <= 0x2EFF)
            || (v >= 0x2F00 && v <= 0x2FDF)
    }
}

final class PageOcrService {
    typealias ImageRecognizer = @Sendable (UIImage, PageImportTarget) async -> String?

    static let maxImageEdge: CGFloat = 3200
    static let previewMaxEdge: CGFloat = 1280
    static let organizeImageMaxEdge: CGFloat = 1600
    static let organizeImageMaxBytes = 1_500_000
    static let organizeJpegInitialQuality: CGFloat = 0.82
    static let organizeJpegMinQuality: CGFloat = 0.42
    static let organizeJpegQualityStep: CGFloat = 0.12

    private let customRecognizer: ImageRecognizer?

    init(recognizer: ImageRecognizer? = nil) {
        self.customRecognizer = recognizer
    }

    func recognize(image: UIImage, target: PageImportTarget) async -> String? {
        if let customRecognizer {
            return await customRecognizer(image, target)
        }
        guard let normalized = Self.normalizedImage(image, maxEdge: Self.maxImageEdge),
              let cgImage = normalized.cgImage else {
            return nil
        }
        return await recognize(cgImage: cgImage, target: target)
    }

    func recognize(imageData: Data, target: PageImportTarget) async -> (text: String?, preview: UIImage?) {
        let preview = Self.decodeImage(from: imageData, maxEdge: Self.previewMaxEdge)
        if let customRecognizer {
            guard let decoded = preview ?? UIImage(data: imageData) else {
                return (nil, preview)
            }
            let text = await customRecognizer(decoded, target)
            return (text, preview)
        }
        guard let recognitionImage = Self.decodeImage(from: imageData, maxEdge: Self.maxImageEdge),
              let cgImage = recognitionImage.cgImage else {
            return (nil, preview)
        }
        let text = await recognize(cgImage: cgImage, target: target)
        return (text, preview)
    }

    func makePreview(from image: UIImage) -> UIImage? {
        Self.normalizedImage(image, maxEdge: Self.previewMaxEdge)
    }

    /// 长边缩放至 <= 1600，JPEG 质量从 0.82 每次降 0.12 至最低 0.42，直到字节数 <= 1_500_000，返回无换行 Base64。
    static func encodeJpegBase64(from image: UIImage) -> String? {
        guard let resized = normalizedImage(image, maxEdge: organizeImageMaxEdge) else {
            return nil
        }
        var quality = organizeJpegInitialQuality
        guard var data = resized.jpegData(compressionQuality: quality) else {
            return nil
        }
        while data.count > organizeImageMaxBytes && quality > organizeJpegMinQuality + 0.001 {
            quality = max(organizeJpegMinQuality, quality - organizeJpegQualityStep)
            guard let nextData = resized.jpegData(compressionQuality: quality) else {
                break
            }
            data = nextData
        }
        guard !data.isEmpty else { return nil }
        return data.base64EncodedString()
    }

    private func recognize(cgImage: CGImage, target: PageImportTarget) async -> String? {
        let imageSize = CGSize(width: cgImage.width, height: cgImage.height)
        return await withCheckedContinuation { continuation in
            DispatchQueue.global(qos: .userInitiated).async {
                let request = VNRecognizeTextRequest()
                request.recognitionLevel = .accurate
                request.usesLanguageCorrection = true
                request.recognitionLanguages = target == .translation ? ["zh-Hans", "en-US"] : ["en-US"]

                let handler = VNImageRequestHandler(cgImage: cgImage, orientation: .up, options: [:])
                do {
                    try handler.perform([request])
                    let observations = request.results ?? []
                    let boxes: [OcrLineBox] = observations.compactMap { observation in
                        guard let candidate = observation.topCandidates(1).first else { return nil }
                        return PageOcrLayout.fromVision(
                            boundingBox: observation.boundingBox,
                            text: candidate.string,
                            imageSize: imageSize
                        )
                    }
                    let blocks = PageOcrLayout.groupLinesIntoBlocks(boxes)
                    let formatted = PageOcrLayout.formatBlocks(blocks, target: target)
                    continuation.resume(returning: formatted)
                } catch {
                    continuation.resume(returning: nil)
                }
            }
        }
    }

    static func decodeImage(from data: Data, maxEdge: CGFloat) -> UIImage? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else {
            return nil
        }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: Int(maxEdge)
        ]
        guard let cgImage = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else {
            return nil
        }
        return UIImage(cgImage: cgImage)
    }

    static func normalizedImage(_ image: UIImage, maxEdge: CGFloat) -> UIImage? {
        let width = image.size.width * image.scale
        let height = image.size.height * image.scale
        guard width > 0, height > 0 else { return nil }

        let longest = max(width, height)
        let scale = longest > maxEdge ? (maxEdge / longest) : 1.0
        let targetSize = CGSize(
            width: max(1, (width * scale).rounded()),
            height: max(1, (height * scale).rounded())
        )

        if image.imageOrientation == .up, scale == 1.0, image.cgImage != nil {
            return image
        }

        let format = UIGraphicsImageRendererFormat()
        format.scale = 1.0
        format.opaque = false
        let renderer = UIGraphicsImageRenderer(size: targetSize, format: format)
        return renderer.image { _ in
            image.draw(in: CGRect(origin: .zero, size: targetSize))
        }
    }
}
