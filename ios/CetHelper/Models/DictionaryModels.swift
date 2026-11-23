import Foundation

enum DictionaryLevelFilter: String, CaseIterable, Hashable, Sendable, Identifiable {
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

    /// 传给 GET /api/v1/dictionary/suggest 的 level 查询参数；.all 为 nil（不限）。
    var apiValue: String? {
        switch self {
        case .all: return nil
        case .cet4: return "CET-4"
        case .cet6: return "CET-6"
        }
    }

    var accessibilityIdentifier: String {
        "dict.level.\(rawValue)"
    }
}

struct DictionarySuggestion: Hashable, Sendable, Identifiable {
    let word: String
    let phonetic: String
    let partOfSpeech: String
    let cetLevel: String
    let meaning: String

    var id: String { word }
}

struct DictionaryEntry: Hashable, Sendable {
    let word: String
    let lemma: String
    let phonetic: String
    let partOfSpeech: String
    let cetLevel: String
    let meaning: String
    let explanation: String
    let exampleSentence: String
    let exampleTranslation: String
    let mode: String

    var isDemo: Bool { mode == "demo" }

    /// 标题展示：lemma 非空且忽略大小写不等于 word 时显示 "\(word) (\(lemma))"，否则显示 word。
    var displayHeading: String {
        if !lemma.isEmpty && lemma.caseInsensitiveCompare(word) != .orderedSame {
            return "\(word) (\(lemma))"
        }
        return word
    }

    /// 用于收藏与历史主键的规范词形（lemma 非空优先，否则 word）。
    var canonicalWord: String {
        lemma.isEmpty ? word : lemma
    }

    func toHistory(serverUrl: String, studentId: Int64, now: Int64) -> DictionaryHistory {
        DictionaryHistory(
            serverUrl: serverUrl,
            studentId: studentId,
            word: canonicalWord,
            phonetic: phonetic,
            partOfSpeech: partOfSpeech,
            lemma: lemma,
            cetLevel: cetLevel,
            meaning: meaning,
            explanation: explanation,
            exampleSentence: exampleSentence,
            mode: mode.isEmpty ? "demo" : mode,
            lookedUpAt: now
        )
    }
}

struct DictionaryHistory: Hashable, Sendable, Identifiable {
    let serverUrl: String
    let studentId: Int64
    let word: String
    let phonetic: String
    let partOfSpeech: String
    let lemma: String
    let cetLevel: String
    let meaning: String
    let explanation: String
    let exampleSentence: String
    let mode: String
    let lookedUpAt: Int64

    var id: String { "\(serverUrl)|\(studentId)|\(word)" }
}
