import CoreFoundation
import Foundation

struct Selection: Hashable, Sendable {
    let start: Int
    let end: Int
    let text: String
    let sentence: String?
    var range: TextRange { TextRange(start: start, end: end) }
}

struct Paragraph: Hashable, Sendable, Identifiable {
    let start: Int
    let text: String
    var end: Int { start + text.utf16.count }
    var id: Int { start }

    /// from/to 为段内相对 UTF-16；返回的 Selection 为绝对偏移。→ §3.1
    func select(from rawFrom: Int, to rawTo: Int) -> Selection? {
        let totalLength = text.utf16.count
        guard rawFrom >= 0, rawTo <= totalLength, rawFrom < rawTo else {
            return nil
        }
        let trimmedFrom = TextMetrics.skipSpaceUTF16(in: text, from: rawFrom, to: rawTo)
        let trimmedTo = TextMetrics.trimEndUTF16(in: text, from: trimmedFrom, to: rawTo)
        guard trimmedFrom < trimmedTo else {
            return nil
        }

        let selectedText = TextMetrics.substringUTF16(in: text, start: trimmedFrom, end: trimmedTo)
        let sentence = extractSentence(from: trimmedFrom, to: trimmedTo)
        return Selection(
            start: start + trimmedFrom,
            end: start + trimmedTo,
            text: selectedText,
            sentence: sentence
        )
    }

    private func extractSentence(from: Int, to: Int) -> String? {
        let flat = text.replacingOccurrences(of: "\n", with: " ")
        let cfText = flat as CFString
        let totalLength = CFStringGetLength(cfText)
        let locale = CFLocaleCreate(kCFAllocatorDefault, CFLocaleIdentifier("en-u-ss-standard" as CFString))
        guard let tokenizer = CFStringTokenizerCreate(
            kCFAllocatorDefault,
            cfText,
            CFRange(location: 0, length: totalLength),
            kCFStringTokenizerUnitSentence,
            locale
        ) else {
            return nil
        }

        var sentenceStart = 0
        var sentenceEnd = totalLength
        var found = false

        var tokenType = CFStringTokenizerAdvanceToNextToken(tokenizer)
        while tokenType != CFStringTokenizerTokenType() {
            let range = CFStringTokenizerGetCurrentTokenRange(tokenizer)
            let rangeStart = range.location
            let rangeEnd = range.location + range.length
            if rangeStart <= from && from < rangeEnd {
                sentenceStart = rangeStart
                sentenceEnd = rangeEnd
                found = true
                break
            }
            if rangeStart > from {
                break
            }
            tokenType = CFStringTokenizerAdvanceToNextToken(tokenizer)
        }

        if !found {
            let goType = CFStringTokenizerGoToTokenAtIndex(tokenizer, from)
            if goType != CFStringTokenizerTokenType() {
                let range = CFStringTokenizerGetCurrentTokenRange(tokenizer)
                sentenceStart = range.location
                sentenceEnd = range.location + range.length
                found = true
            }
        }

        guard found, to <= sentenceEnd else {
            return nil
        }
        let rawSentence = TextMetrics.substringUTF16(in: text, start: sentenceStart, end: sentenceEnd)
        return TextMetrics.trimSpace(rawSentence)
    }
}

struct ReadingArticle: Hashable, Sendable, Identifiable {
    let id: String
    let title: String
    let body: String
    var isDemo: Bool { id == DemoArticle.id }

    private static let paragraphBreakRegex = try! NSRegularExpression(pattern: "\\n(?:[^\\S\\n]*\\n)+", options: [])

    func paragraphs() -> [Paragraph] {
        let nsBody = body as NSString
        let fullLength = nsBody.length
        let matches = Self.paragraphBreakRegex.matches(
            in: body,
            options: [],
            range: NSRange(location: 0, length: fullLength)
        )

        var result: [Paragraph] = []
        var cursor = 0
        for match in matches {
            appendParagraph(&result, from: cursor, to: match.range.location)
            cursor = match.range.location + match.range.length
        }
        appendParagraph(&result, from: cursor, to: fullLength)
        return result
    }

    private func appendParagraph(_ result: inout [Paragraph], from: Int, to: Int) {
        if TextMetrics.skipSpaceUTF16(in: body, from: from, to: to) != to {
            let slice = TextMetrics.substringUTF16(in: body, start: from, end: to)
            result.append(Paragraph(start: from, text: slice))
        }
    }
}

enum DemoArticle {
    static let id = "decline-demo-v1"
    static let title = "Two meanings of “decline”"
    static let body = "The report attributed the decline to lower demand.\n\nSome students decline the invitation."
    static var article: ReadingArticle { ReadingArticle(id: id, title: title, body: body) }
}

/// POST /articles/organize 结果。
struct OrganizedArticle: Hashable, Sendable {
    let title: String
    let body: String
    let summary: String
    let mode: String
}

/// POST /explanations 结果。→ §1.2 #6
struct ExplanationResult: Hashable, Sendable {
    let selection: String
    let sentence: String
    let meaning: String
    let explanation: String
    let mode: String
    let phonetic: String
    let partOfSpeech: String
    let lemma: String
    let cetLevel: String
    let contextual: Bool
    var isDemo: Bool { mode == "demo" }

    /// → §1.2 formattedMeta 规则
    func formattedMeta() -> String {
        var parts: [String] = []
        if !phonetic.isEmpty {
            parts.append(phonetic)
        }
        if !partOfSpeech.isEmpty {
            parts.append(partOfSpeech)
        }
        if !cetLevel.isEmpty {
            parts.append("· \(cetLevel)")
        }
        if !lemma.isEmpty {
            parts.append("原型: \(lemma)")
        }
        if !contextual {
            parts.append("通用词典")
        }
        return parts.joined(separator: "  ")
    }
}

struct SentenceChunk: Hashable, Sendable, Identifiable {
    let index: Int
    let text: String
    let role: String
    let explanation: String
    let range: TextRange
    var id: Int { index }
}

/// POST /sentence-analyses 结果。→ §1.2 #7 / §3.4
struct SentenceAnalysis: Hashable, Sendable {
    let sentence: String
    let translation: String
    let structure: String
    let mode: String
    let chunks: [SentenceChunk]

    static func locate(
        sentence: String,
        chunks: [(text: String, role: String, explanation: String)]
    ) throws -> [SentenceChunk] {
        let nsSentence = sentence as NSString
        let totalLength = nsSentence.length
        var result: [SentenceChunk] = []
        result.reserveCapacity(chunks.count)
        var lastEnd = 0

        for (index, chunk) in chunks.enumerated() {
            let chunkText = chunk.text
            if chunkText.isEmpty || lastEnd > totalLength {
                throw ApiError.malformed
            }
            let searchRange = NSRange(location: lastEnd, length: totalLength - lastEnd)
            let found = nsSentence.range(of: chunkText, options: .literal, range: searchRange)
            guard found.location != NSNotFound else {
                throw ApiError.malformed
            }
            let end = found.location + found.length
            lastEnd = end
            result.append(
                SentenceChunk(
                    index: index,
                    text: chunkText,
                    role: chunk.role,
                    explanation: chunk.explanation,
                    range: TextRange(start: found.location, end: end)
                )
            )
        }
        return result
    }
}
