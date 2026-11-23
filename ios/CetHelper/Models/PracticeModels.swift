import Foundation

enum TranslationHintType: String, CaseIterable, Hashable, Sendable {
    case keyword, phrase, structure

    var title: String {
        switch self {
        case .keyword: return "关键词"
        case .phrase: return "短语"
        case .structure: return "句子结构"
        }
    }

    var responseField: String {
        switch self {
        case .keyword: return "keywords"
        case .phrase: return "phrases"
        case .structure: return "structure"
        }
    }
}

struct TranslationHintItem: Hashable, Sendable, Identifiable {
    let id: Int
    let title: String
    let body: String
}

struct TranslationHint: Hashable, Sendable {
    let type: TranslationHintType
    let sourceText: String
    let mode: String
    let items: [TranslationHintItem]
}

enum ReviewIssueType: String, Hashable, Sendable {
    case omission = "OMISSION"
    case unnatural = "UNNATURAL"
    case grammar = "GRAMMAR"
    case wordChoice = "WORD_CHOICE"
    case mechanics = "MECHANICS"
    case clarity = "CLARITY"

    var label: String {
        switch self {
        case .omission: return "漏译"
        case .unnatural: return "表达不自然"
        case .grammar: return "语法"
        case .wordChoice: return "用词或搭配"
        case .mechanics: return "拼写、大小写或标点"
        case .clarity: return "表达不清"
        }
    }
}

/// 引文所在文本：翻译 OMISSION → .source（中文原文）；其余全部 → .draft（译文/作文）。
enum QuoteTarget: Hashable, Sendable { case source, draft }

struct ReviewIssue: Hashable, Sendable, Identifiable {
    let index: Int
    let type: ReviewIssueType
    let quote: String
    let detail: String
    let suggestion: String
    let target: QuoteTarget
    let matches: [TextRange]
    var id: Int { index }
    var candidateNotice: String? {
        matches.count > 1 ? "该引文出现 \(matches.count) 次，高亮均为候选位置。" : nil
    }
}

/// → §1.2 #9
struct TranslationFeedback: Hashable, Sendable {
    let sourceText: String
    let studentTranslation: String
    let mode: String
    let overallFeedback: String
    let issues: [ReviewIssue]
    let estimatedScore: Int
    let scoreMax: Int
    let scoreBand: String?
    let referenceTranslation: String?
    let rawJson: String
    var hasScore: Bool { estimatedScore >= 0 }
}

struct WritingExercise: Hashable, Sendable {
    let issueIndex: Int
    let instruction: String
    let item: String
}

struct WritingTask: Hashable, Sendable {
    let instruction: String
    let item: String
}

/// → §1.2 #10
struct WritingFeedback: Hashable, Sendable {
    let prompt: String
    let essay: String
    let mode: String
    let overallFeedback: String
    let issues: [ReviewIssue]
    let exercise: WritingExercise?
    let estimatedScore: Int
    let scoreMax: Int
    let scoreBand: String?
    let rawJson: String
    var hasScore: Bool { estimatedScore >= 0 }
}

enum WritingVerdict: String, Hashable, Sendable {
    case correct = "CORRECT"
    case needsRevision = "NEEDS_REVISION"
    case targetUsed = "TARGET_USED"
    case sameError = "SAME_ERROR"
    case insufficientEvidence = "INSUFFICIENT_EVIDENCE"

    var label: String {
        switch self {
        case .correct: return "正确"
        case .needsRevision: return "需要修改"
        case .targetUsed: return "已正确运用"
        case .sameError: return "同类错误再次出现"
        case .insufficientEvidence: return "证据不足"
        }
    }

    var isPositive: Bool { self == .correct || self == .targetUsed }

    var isRetest: Bool {
        switch self {
        case .correct, .needsRevision: return false
        case .targetUsed, .sameError, .insufficientEvidence: return true
        }
    }
}

/// → §1.2 #11 / #13 / §3.5
struct WritingAssessment: Hashable, Sendable {
    let verdict: WritingVerdict
    let answer: String
    let feedback: String
    let nextStep: String
    let quote: String?
    let matches: [TextRange]
    var candidateNotice: String? {
        matches.count > 1 ? "该引文出现 \(matches.count) 次，高亮均为候选位置。" : nil
    }
}

enum PracticeRecordType: String, Hashable, Sendable {
    case writing = "WRITING"
    case translation = "TRANSLATION"
}

/// practice_records 表一行。→ §2.1 表 3
struct PracticeRecord: Hashable, Sendable, Identifiable {
    let id: Int64
    let ownerKey: String
    let type: PracticeRecordType
    let promptOrSource: String
    let draft: String
    let reviewJson: String
    let estimatedScore: Int
    let createdAt: Int64

    static func ownerKey(_ owner: ArticleOwner) -> String {
        "\(owner.serverUrl)|\(owner.studentId)"
    }
}
