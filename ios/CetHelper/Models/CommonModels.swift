import Foundation

/// UTF-16 左闭右开区间 [start, end)。→ §3.1 / D8
struct TextRange: Hashable, Sendable {
    let start: Int
    let end: Int
    init(start: Int, end: Int) { self.start = start; self.end = end }
    var length: Int { end - start }
    var nsRange: NSRange { NSRange(location: start, length: end - start) }
}

/// 场景生命周期（Hydrogen 将 ScenePhase 映射后传入）。
enum AppLifecycleEvent: Hashable, Sendable { case active, inactive, background }

/// 二次确认弹窗（删除材料/生词/历史、替换草稿等）。
struct ConfirmationRequest: Identifiable {
    let id = UUID()
    let title: String
    let message: String
    let confirmTitle: String
    let isDestructive: Bool
    let onConfirm: @MainActor () -> Void
}

/// 可被"定位引文"内联高亮的输入区域。
enum PracticeField: Hashable, Sendable { case translationSource, translationDraft, writingPrompt, writingEssay }

/// 内联高亮指令。→ §3.4
struct InlineHighlight: Hashable, Sendable {
    let field: PracticeField
    let ranges: [TextRange]
    let token: UUID
}

/// "定位引文"只读快照弹窗（sheet(item:)）。→ §3.4
struct QuoteSnapshot: Identifiable, Hashable {
    let id: UUID
    let title: String
    let text: String
    let highlights: [TextRange]
}
