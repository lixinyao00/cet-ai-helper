import Foundation

enum TutorContextType: String, CaseIterable, Hashable, Sendable {
    case free = "FREE"
    case word = "WORD"
    case sentence = "SENTENCE"
    case translation = "TRANSLATION"
    case writing = "WRITING"

    var badgeTitle: String {
        switch self {
        case .free: return "自由问答"
        case .word: return "单词上下文"
        case .sentence: return "句法上下文"
        case .translation: return "翻译上下文"
        case .writing: return "写作上下文"
        }
    }
}

struct TutorContext: Hashable, Sendable {
    private static let maxTitle = 200
    private static let maxFocus = 2000
    private static let maxReference = 6000
    private static let maxExtra = 4000
    private static let maxSummary = 60
    private static let whitespaceRegex = try! NSRegularExpression(pattern: "\\s+", options: [])

    let type: TutorContextType
    let title: String
    let focusText: String
    let referenceText: String
    let extraNote: String

    static let free = TutorContext(
        type: .free,
        title: "",
        focusText: "",
        referenceText: "",
        extraNote: ""
    )

    var displayTitle: String {
        let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? type.badgeTitle : trimmed
    }

    func summary() -> String {
        if type == .free {
            return "自由提问四六级词汇、语法、阅读、翻译或写作问题"
        }
        let candidate: String
        if !focusText.isEmpty {
            candidate = focusText
        } else if !title.isEmpty {
            candidate = title
        } else {
            candidate = referenceText
        }
        let nsCandidate = candidate as NSString
        let collapsed = Self.whitespaceRegex.stringByReplacingMatches(
            in: candidate,
            options: [],
            range: NSRange(location: 0, length: nsCandidate.length),
            withTemplate: " "
        ).trimmingCharacters(in: .whitespacesAndNewlines)

        if collapsed.isEmpty {
            return "当前学习上下文"
        }
        if collapsed.count <= Self.maxSummary {
            return collapsed
        }
        return String(collapsed.prefix(Self.maxSummary - 1)) + "…"
    }

    func sessionKey() -> String {
        if type == .free {
            return "free"
        }
        return "\(type.rawValue):\(Self.javaHashCode(focusText))"
    }

    func quickPrompts() -> [String] {
        switch type {
        case .word:
            return [
                "这个词在四六级真题里常见搭配有哪些？",
                "它和近义词有什么区别？",
                "能再给我两个双语例句吗？",
                "它有哪些常见的词性变形？"
            ]
        case .sentence:
            return [
                "请通俗拆解这句话的从句结构",
                "这句话里有哪些四六级核心词汇或短语？",
                "如何把这句话翻译得更地道？"
            ]
        case .translation:
            return [
                "我的译文最需要先修改哪一处？",
                "能给我一个更高分的地道改写吗？",
                "这道题涉及哪些常考翻译句型？"
            ]
        case .writing:
            return [
                "如何把我的作文提升到高分档？",
                "请帮我润色其中的薄弱句子",
                "这个话题有哪些四六级亮点表达？"
            ]
        case .free:
            return [
                "四六级阅读如何快速定位答案？",
                "帮我梳理高频虚拟语气考点",
                "汉译英常用高级衔接词有哪些？",
                "作文开头段怎么写更有亮点？"
            ]
        }
    }

    func toPayloadDictionary() -> [String: String] {
        var dict: [String: String] = ["type": type.rawValue]
        if !title.isEmpty {
            dict["title"] = Self.clampUTF16(title, maxLength: Self.maxTitle)
        }
        if !focusText.isEmpty {
            dict["focusText"] = Self.clampUTF16(focusText, maxLength: Self.maxFocus)
        }
        if !referenceText.isEmpty {
            dict["referenceText"] = Self.clampUTF16(referenceText, maxLength: Self.maxReference)
        }
        if !extraNote.isEmpty {
            dict["extraNote"] = Self.clampUTF16(extraNote, maxLength: Self.maxExtra)
        }
        return dict
    }

    /// 跨进程确定性 UTF-16 多项式哈希（与 Java String.hashCode() 1:1 一致，禁止使用 String.hashValue）。
    static func javaHashCode(_ text: String) -> Int32 {
        var h: Int32 = 0
        for unit in text.utf16 {
            h = 31 &* h &+ Int32(bitPattern: UInt32(unit))
        }
        return h
    }

    static func clampUTF16(_ value: String, maxLength: Int) -> String {
        guard maxLength > 0 else { return "" }
        if value.utf16.count <= maxLength {
            return value
        }
        var result = ""
        result.reserveCapacity(min(value.utf8.count, maxLength * 3))
        var usedUnits = 0
        for scalar in value.unicodeScalars {
            let cost = scalar.utf16.count
            if usedUnits + cost > maxLength {
                break
            }
            result.unicodeScalars.append(scalar)
            usedUnits += cost
        }
        return result
    }
}

enum TutorChatRole: String, Hashable, Sendable {
    case user
    case assistant
}

struct TutorMessage: Hashable, Sendable, Identifiable {
    let id: Int64
    let serverUrl: String
    let studentId: Int64
    let sessionKey: String
    let role: TutorChatRole
    let content: String
    let contextType: String
    let contextSummary: String
    let mode: String
    let createdAt: Int64
}

enum TutorSseEvent: Equatable, Sendable {
    case meta(mode: String, contextType: String)
    case delta(text: String)
    case done(followUps: [String])
    case error(code: String?, message: String)
}

final class TutorStreamHandle: @unchecked Sendable {
    private let lock = NSLock()
    private var cancelled = false
    private var task: Task<Void, Never>?

    var isCancelled: Bool {
        lock.lock()
        defer { lock.unlock() }
        return cancelled
    }

    func attach(task newTask: Task<Void, Never>) {
        lock.lock()
        if cancelled {
            lock.unlock()
            newTask.cancel()
            return
        }
        task = newTask
        lock.unlock()
    }

    func cancel() {
        lock.lock()
        let shouldCancel = !cancelled
        cancelled = true
        let runningTask = task
        task = nil
        lock.unlock()
        if shouldCancel {
            runningTask?.cancel()
        }
    }
}
