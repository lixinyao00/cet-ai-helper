import Foundation

enum TextMetrics {
    private static let englishWordRegex = try! NSRegularExpression(
        pattern: "[A-Za-z]+(?:['’\\-][A-Za-z]+)*",
        options: []
    )

    static func isSpace(_ scalar: Unicode.Scalar) -> Bool {
        if CharacterSet.whitespacesAndNewlines.contains(scalar) {
            return true
        }
        switch scalar.properties.generalCategory {
        case .spaceSeparator, .lineSeparator, .paragraphSeparator:
            return true
        default:
            break
        }
        // Match Java Character.isWhitespace control separators (U+001C...U+001F).
        let value = scalar.value
        return (value >= 0x0009 && value <= 0x000D) || (value >= 0x001C && value <= 0x001F)
    }

    static func trimSpace(_ s: String) -> String {
        let scalars = s.unicodeScalars
        var start = scalars.startIndex
        var end = scalars.endIndex
        while start < end && isSpace(scalars[start]) {
            start = scalars.index(after: start)
        }
        while end > start {
            let prev = scalars.index(before: end)
            if isSpace(scalars[prev]) {
                end = prev
            } else {
                break
            }
        }
        return String(scalars[start..<end])
    }

    static func englishWordCount(_ s: String) -> Int {
        guard !s.isEmpty else { return 0 }
        let nsText = s as NSString
        return englishWordRegex.numberOfMatches(
            in: s,
            options: [],
            range: NSRange(location: 0, length: nsText.length)
        )
    }

    static func writingWordBadge(_ words: Int) -> String {
        if words == 0 {
            return "0 词 · 四六级建议 120-180 词"
        }
        let hint: String
        if words < 120 {
            hint = "偏少"
        } else if words <= 180 {
            hint = "达标"
        } else {
            hint = "超出建议"
        }
        return "\(words) / 120-180 词（\(hint)）"
    }

    static func translationWordBadge(_ words: Int) -> String {
        "当前 \(words) 词"
    }

    // MARK: - Internal UTF-16 Helpers

    static func skipSpaceUTF16(in text: String, from: Int, to: Int) -> Int {
        let nsText = text as NSString
        var index = max(0, from)
        let upper = min(to, nsText.length)
        while index < upper {
            let unit = nsText.character(at: index)
            if let scalar = Unicode.Scalar(unit), isSpace(scalar) {
                index += 1
            } else {
                break
            }
        }
        return index
    }

    static func trimEndUTF16(in text: String, from: Int, to: Int) -> Int {
        let nsText = text as NSString
        let lower = max(0, from)
        var index = min(to, nsText.length)
        while index > lower {
            let unit = nsText.character(at: index - 1)
            if let scalar = Unicode.Scalar(unit), isSpace(scalar) {
                index -= 1
            } else {
                break
            }
        }
        return index
    }

    static func substringUTF16(in text: String, start: Int, end: Int) -> String {
        let nsText = text as NSString
        let safeStart = max(0, min(start, nsText.length))
        let safeEnd = max(safeStart, min(end, nsText.length))
        return nsText.substring(with: NSRange(location: safeStart, length: safeEnd - safeStart))
    }
}
