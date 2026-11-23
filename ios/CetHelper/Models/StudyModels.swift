import Foundation

struct FavoriteKey: Hashable, Sendable {
    let serverUrl: String
    let studentId: Int64
    let articleId: String
    let startOffset: Int
    let endOffset: Int
}

enum VocabularyLevelFilter: String, CaseIterable, Hashable, Sendable, Identifiable {
    case all
    case cet4
    case cet6

    var id: String { rawValue }

    var title: String {
        switch self {
        case .all: return "全部"
        case .cet4: return "CET-4"
        case .cet6: return "CET-6"
        }
    }

    var accessibilityIdentifier: String {
        "vocab.filter.level.\(rawValue)"
    }
}

enum VocabularySourceFilter: String, CaseIterable, Hashable, Sendable, Identifiable {
    case all
    case reader
    case dictionary

    var id: String { rawValue }

    var title: String {
        switch self {
        case .all: return "全部来源"
        case .reader: return "阅读"
        case .dictionary: return "词典"
        }
    }

    var accessibilityIdentifier: String {
        "vocab.filter.source.\(rawValue)"
    }
}

enum VocabularyMasteryFilter: String, CaseIterable, Hashable, Sendable, Identifiable {
    case all
    case unreviewed
    case learning
    case mastered

    var id: String { rawValue }

    var title: String {
        switch self {
        case .all: return "全部状态"
        case .unreviewed: return "未复习"
        case .learning: return "学习中"
        case .mastered: return "已掌握"
        }
    }

    var accessibilityIdentifier: String {
        "vocab.filter.mastery.\(rawValue)"
    }
}

/// favorites 表一行（list 时附带 LEFT JOIN 的 articleTitle）。→ §2.1 表 1
struct Favorite: Hashable, Sendable, Identifiable {
    static let dictArticleId = "__dict__"
    static let sourceReader = "reader"
    static let sourceDictionary = "dictionary"

    let serverUrl: String
    let studentId: Int64
    let articleId: String
    let startOffset: Int
    let endOffset: Int
    let selection: String
    let sentence: String
    let meaning: String
    let explanation: String
    let mode: String
    let savedAt: Int64
    var phonetic: String = ""
    var partOfSpeech: String = ""
    var masteryLevel: Int = 0
    var reviewCount: Int = 0
    var lastReviewedAt: Int64 = 0
    var nextReviewAt: Int64 = 0
    var articleTitle: String? = nil
    var lemma: String = ""
    var cetLevel: String = ""
    var sourceType: String = Favorite.sourceReader

    var id: FavoriteKey {
        FavoriteKey(
            serverUrl: serverUrl,
            studentId: studentId,
            articleId: articleId,
            startOffset: startOffset,
            endOffset: endOffset
        )
    }

    var range: TextRange { TextRange(start: startOffset, end: endOffset) }
    func isDue(now: Int64) -> Bool { nextReviewAt <= now }

    var isDictionarySource: Bool {
        articleId == Self.dictArticleId || sourceType == Self.sourceDictionary
    }

    /// 生词本来源展示标题："__dict__" 返回 "词典查词"，否则返回 articleTitle ?? ""。
    var resolvedSourceTitle: String {
        isDictionarySource ? "词典查词" : (articleTitle ?? "")
    }

    static func fromDictionary(
        serverUrl: String,
        studentId: Int64,
        entry: DictionaryEntry,
        now: Int64
    ) -> Favorite {
        let sel = entry.lemma.isEmpty ? entry.word : entry.lemma
        let sent = entry.exampleSentence
        var start = 0
        var end = 0
        if !sel.isEmpty && !sent.isEmpty {
            let found = (sent as NSString).range(of: sel, options: .caseInsensitive)
            if found.location != NSNotFound {
                start = found.location
                end = found.location + found.length
            }
        }
        var expl = entry.explanation
        if !entry.exampleTranslation.isEmpty {
            expl += "\n例句翻译：" + entry.exampleTranslation
        }
        let modeValue = entry.mode.isEmpty ? "demo" : entry.mode
        return Favorite(
            serverUrl: serverUrl,
            studentId: studentId,
            articleId: Self.dictArticleId,
            startOffset: start,
            endOffset: end,
            selection: sel,
            sentence: sent,
            meaning: entry.meaning,
            explanation: expl,
            mode: modeValue,
            savedAt: now,
            phonetic: entry.phonetic,
            partOfSpeech: entry.partOfSpeech,
            masteryLevel: 0,
            reviewCount: 0,
            lastReviewedAt: 0,
            nextReviewAt: 0,
            articleTitle: nil,
            lemma: entry.lemma,
            cetLevel: entry.cetLevel,
            sourceType: Self.sourceDictionary
        )
    }
}

/// articles 表一行。articleId 由 Neon 生成：UUID().uuidString.lowercased()。→ §2.1 表 2
struct ImportedArticle: Hashable, Sendable, Identifiable {
    let serverUrl: String
    let studentId: Int64
    let articleId: String
    let title: String
    let body: String
    let createdAt: Int64
    var lastReadAt: Int64 = 0
    var lastScrollY: Int = 0
    var id: String { articleId }
    var readingArticle: ReadingArticle { ReadingArticle(id: articleId, title: title, body: body) }
}

struct ArticleSummary: Hashable, Sendable, Identifiable {
    let articleId: String
    let title: String
    let createdAt: Int64
    let lastReadAt: Int64
    let lastScrollY: Int
    var id: String { articleId }
}

/// → §3.3
struct ClozeQuestion: Hashable, Sendable, Identifiable {
    static let blank = "____"
    private static let spacesPattern = "[\\s\\p{Zs}]+"
    private static let spacesRegex = try! NSRegularExpression(pattern: spacesPattern, options: [])

    let favorite: Favorite
    let articleTitle: String
    let maskedTitle: String
    let maskedSentence: String
    let hint: String
    var id: FavoriteKey { favorite.id }
    var answer: String { favorite.selection }

    static func from(article: ReadingArticle?, favorite: Favorite?) -> ClozeQuestion? {
        guard let favorite else {
            return nil
        }
        if favorite.articleId == Favorite.dictArticleId {
            return fromDictionaryFavorite(favorite)
        }
        guard let article, article.id == favorite.articleId else {
            return nil
        }

        let nsBody = article.body as NSString
        let bodyLength = nsBody.length
        let start = favorite.startOffset
        let end = favorite.endOffset
        let selectionLength = (favorite.selection as NSString).length

        guard start >= 0,
              start < end,
              end <= bodyLength,
              selectionLength == end - start else {
            return nil
        }

        let bodySelection = nsBody.substring(with: NSRange(location: start, length: end - start))
        guard bodySelection == favorite.selection else {
            return nil
        }

        let nsSentence = favorite.sentence as NSString
        let sentenceLength = nsSentence.length
        let earliest = max(0, end - sentenceLength)
        let latest = min(start, bodyLength - sentenceLength)
        guard earliest <= latest else {
            return nil
        }

        let firstRange = nsBody.range(
            of: favorite.sentence,
            options: .literal,
            range: NSRange(location: earliest, length: bodyLength - earliest)
        )
        guard firstRange.location != NSNotFound, firstRange.location <= latest else {
            return nil
        }
        let sentenceStart = firstRange.location

        if sentenceStart + 1 <= bodyLength {
            let secondRange = nsBody.range(
                of: favorite.sentence,
                options: .literal,
                range: NSRange(location: sentenceStart + 1, length: bodyLength - (sentenceStart + 1))
            )
            if secondRange.location != NSNotFound && secondRange.location <= latest {
                return nil
            }
        }

        let localStart = start - sentenceStart
        let localEnd = end - sentenceStart
        let prefix = nsSentence.substring(to: localStart)
        let suffix = nsSentence.substring(from: localEnd)
        let maskedSentence = prefix + blank + suffix

        return ClozeQuestion(
            favorite: favorite,
            articleTitle: article.title,
            maskedTitle: maskTitle(article.title, selection: favorite.selection),
            maskedSentence: maskedSentence,
            hint: buildHint(favorite.selection)
        )
    }

    private static func fromDictionaryFavorite(_ favorite: Favorite) -> ClozeQuestion? {
        let sentence = favorite.sentence
        let selection = favorite.selection
        guard !TextMetrics.trimSpace(sentence).isEmpty,
              !TextMetrics.trimSpace(selection).isEmpty else {
            return nil
        }

        let nsSentence = sentence as NSString
        let sentenceLength = nsSentence.length
        var start = favorite.startOffset
        var end = favorite.endOffset

        let hasValidOffsets = start >= 0
            && start < end
            && end <= sentenceLength
            && nsSentence.substring(with: NSRange(location: start, length: end - start))
                .caseInsensitiveCompare(selection) == .orderedSame

        if !hasValidOffsets {
            let found = nsSentence.range(of: selection, options: .caseInsensitive)
            guard found.location != NSNotFound else {
                return nil
            }
            start = found.location
            end = found.location + found.length
        }

        let prefix = nsSentence.substring(to: start)
        let suffix = nsSentence.substring(from: end)
        let maskedSentence = prefix + blank + suffix

        return ClozeQuestion(
            favorite: favorite,
            articleTitle: "词典查词",
            maskedTitle: "词典查词",
            maskedSentence: maskedSentence,
            hint: buildHint(selection)
        )
    }

    static func buildHint(_ selection: String) -> String {
        let trimmed = TextMetrics.trimSpace(selection)
        guard !trimmed.isEmpty else { return "" }
        let parts = splitBySpaces(trimmed)
        guard !parts.isEmpty else { return "" }

        var maskedWords: [String] = []
        var lengths: [String] = []
        for part in parts {
            let scalars = Array(part.unicodeScalars)
            guard !scalars.isEmpty else { continue }
            var word = String(scalars[0])
            if scalars.count > 1 {
                for _ in 1..<scalars.count {
                    word += " _"
                }
            }
            maskedWords.append(word)
            lengths.append(String(scalars.count))
        }
        guard !maskedWords.isEmpty else { return "" }
        return "\(maskedWords.joined(separator: "   ")) (\(lengths.joined(separator: "+")))"
    }

    static func maskTitle(_ title: String, selection: String) -> String {
        let parts = splitBySpaces(selection)
        guard !parts.isEmpty else { return title }
        let pattern = parts
            .map { NSRegularExpression.escapedPattern(for: $0) }
            .joined(separator: spacesPattern)
        guard let regex = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]) else {
            return title
        }
        let nsTitle = title as NSString
        return regex.stringByReplacingMatches(
            in: title,
            options: [],
            range: NSRange(location: 0, length: nsTitle.length),
            withTemplate: NSRegularExpression.escapedTemplate(for: blank)
        )
    }

    func matches(_ input: String) -> Bool {
        TextMetrics.trimSpace(input).lowercased() == favorite.selection.lowercased()
    }

    private static func splitBySpaces(_ text: String) -> [String] {
        let nsText = text as NSString
        let matches = spacesRegex.matches(in: text, options: [], range: NSRange(location: 0, length: nsText.length))
        var parts: [String] = []
        var cursor = 0
        for match in matches {
            if match.range.location > cursor {
                parts.append(nsText.substring(with: NSRange(location: cursor, length: match.range.location - cursor)))
            }
            cursor = match.range.location + match.range.length
        }
        if cursor < nsText.length {
            parts.append(nsText.substring(from: cursor))
        }
        return parts.filter { !$0.isEmpty }
    }
}

/// 题目状态：答错不改变状态（仍 .pending，可继续作答，但已记入 wrongIndices 且不再计分）。
enum ClozeQuestionState: Hashable, Sendable { case pending, correct, revealed }

enum ClozeSubmitResult: Hashable, Sendable {
    case empty
    case correct
    case wrong
    case ignored
}

struct ClozeRoundCreation: Hashable, Sendable {
    let round: ClozeRound?
    let skippedCount: Int
}

/// 纯值类型的一局填空。→ §3.3
struct ClozeRound: Hashable, Sendable {
    static let maxRoundSize = 10
    let questions: [ClozeQuestion]
    let skippedCount: Int
    private(set) var states: [ClozeQuestionState]
    private(set) var currentIndex: Int = 0
    private(set) var correctCount: Int = 0
    private(set) var wrongIndices: [Int] = []

    init(questions: [ClozeQuestion], skippedCount: Int) {
        self.questions = questions
        self.skippedCount = skippedCount
        self.states = Array(repeating: .pending, count: questions.count)
    }

    var current: ClozeQuestion? { currentIndex < questions.count ? questions[currentIndex] : nil }
    var currentState: ClozeQuestionState? { currentIndex < states.count ? states[currentIndex] : nil }
    var isFinished: Bool { currentIndex >= questions.count }
    var hasWrongQuestions: Bool { !wrongIndices.isEmpty }

    static func sortForReview(_ favorites: [Favorite], now: Int64) -> [Favorite] {
        favorites.sorted { lhs, rhs in
            let lhsNotDue = lhs.nextReviewAt > now
            let rhsNotDue = rhs.nextReviewAt > now
            if lhsNotDue != rhsNotDue {
                return !lhsNotDue && rhsNotDue
            }
            if lhs.nextReviewAt != rhs.nextReviewAt {
                return lhs.nextReviewAt < rhs.nextReviewAt
            }
            if lhs.masteryLevel != rhs.masteryLevel {
                return lhs.masteryLevel < rhs.masteryLevel
            }
            if lhs.savedAt != rhs.savedAt {
                return lhs.savedAt > rhs.savedAt
            }
            if lhs.articleId != rhs.articleId {
                return lhs.articleId < rhs.articleId
            }
            if lhs.startOffset != rhs.startOffset {
                return lhs.startOffset < rhs.startOffset
            }
            return lhs.endOffset < rhs.endOffset
        }
    }

    static func create(
        favorites: [Favorite?],
        articles: [String: ReadingArticle],
        now: Int64,
        limit: Int = ClozeRound.maxRoundSize
    ) -> ClozeRoundCreation {
        guard !favorites.isEmpty else {
            return ClozeRoundCreation(round: nil, skippedCount: 0)
        }
        let effectiveLimit = limit > 0 ? limit : maxRoundSize
        let nilCount = favorites.reduce(0) { $0 + ($1 == nil ? 1 : 0) }
        let ordered = sortForReview(favorites.compactMap { $0 }, now: now)

        var valid: [ClozeQuestion] = []
        var skipped = nilCount

        for favorite in ordered {
            let article = articles[favorite.articleId]
            guard let question = ClozeQuestion.from(article: article, favorite: favorite) else {
                skipped += 1
                continue
            }
            valid.append(question)
            if valid.count >= effectiveLimit {
                break
            }
        }

        if valid.isEmpty {
            return ClozeRoundCreation(round: nil, skippedCount: skipped)
        }
        return ClozeRoundCreation(
            round: ClozeRound(questions: valid, skippedCount: skipped),
            skippedCount: skipped
        )
    }

    mutating func submit(_ input: String) -> ClozeSubmitResult {
        guard !isFinished, let state = currentState, state == .pending, let question = current else {
            return .ignored
        }
        if TextMetrics.trimSpace(input).isEmpty {
            return .empty
        }
        if question.matches(input) {
            states[currentIndex] = .correct
            if !wrongIndices.contains(currentIndex) {
                correctCount += 1
            }
            return .correct
        } else {
            recordWrong(currentIndex)
            return .wrong
        }
    }

    mutating func reveal() -> Bool {
        guard !isFinished, let state = currentState, state == .pending else {
            return false
        }
        states[currentIndex] = .revealed
        recordWrong(currentIndex)
        return true
    }

    mutating func advance() {
        guard !isFinished, let state = currentState, state != .pending else {
            return
        }
        currentIndex += 1
    }

    func retryWrongRound() -> ClozeRound? {
        let wrongQuestions = wrongIndices.compactMap { index -> ClozeQuestion? in
            guard index >= 0 && index < questions.count else { return nil }
            return questions[index]
        }
        guard !wrongQuestions.isEmpty else { return nil }
        return ClozeRound(questions: wrongQuestions, skippedCount: 0)
    }

    private mutating func recordWrong(_ index: Int) {
        if !wrongIndices.contains(index) {
            wrongIndices.append(index)
        }
    }
}

/// SRS 公式。→ §3.3
enum Srs {
    static let dayMs: Int64 = 86_400_000

    static func nextMastery(current: Int, correct: Bool) -> Int {
        guard correct else { return 0 }
        return min(3, max(0, current) + 1)
    }

    static func nextReviewAt(mastery: Int, now: Int64) -> Int64 {
        switch mastery {
        case 1: return now + dayMs
        case 2: return now + 3 * dayMs
        case 3: return now + 7 * dayMs
        default: return now
        }
    }
}
