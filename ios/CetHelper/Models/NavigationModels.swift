import Foundation

enum PageImportTarget: String, Hashable, Sendable { case reading, translation }
enum PageImportSource: Hashable, Sendable { case camera, photoLibrary }

/// → §3.2
enum PageImportStage: Hashable, Sendable {
    case external
    case recognizing
    case review
    case saving
    case error(message: String)
}

enum AppTab: String, CaseIterable, Hashable, Sendable, Identifiable {
    case home
    case dictionary
    case tutor
    case vocabulary

    var id: String { rawValue }

    var title: String {
        switch self {
        case .home: return "首页"
        case .dictionary: return "查词"
        case .tutor: return "AI 助教"
        case .vocabulary: return "生词本"
        }
    }

    var systemImage: String {
        switch self {
        case .home: return "house.fill"
        case .dictionary: return "magnifyingglass"
        case .tutor: return "sparkles"
        case .vocabulary: return "book.closed.fill"
        }
    }

    var accessibilityIdentifier: String {
        "tab.\(rawValue)"
    }
}

enum AppScreen: Hashable {
    case dictionary(query: String?)
    case tutor(context: TutorContext)
    case reader(articleId: String, focus: TextRange?)
    case vocabulary
    case cloze
    case pageImport(target: PageImportTarget, source: PageImportSource)
    case translationSource
    case translationAnswer
    case writing
}

enum AppPhase {
    case restoring
    case restoreFailed(message: String)
    case signedOut
    case signedIn(SessionScope)
}

enum LoginMode: Hashable, Sendable { case signIn, register }

enum HealthCheckState: Hashable, Sendable {
    case idle
    case checking
    case ok(HealthInfo)
    case failed(message: String)
}

struct HomeStats: Hashable, Sendable {
    let articleCount: Int
    let favoriteCount: Int
    let dueCount: Int
}

enum ReaderPanel: Hashable, Sendable { case none, lookup, analysis }

enum ReaderFontTier: Int, CaseIterable, Hashable, Sendable {
    case standard = 0, large = 1, extraLarge = 2

    var pointSize: Double {
        switch self {
        case .standard: return 18
        case .large: return 21
        case .extraLarge: return 25
        }
    }

    var buttonTitle: String {
        switch self {
        case .standard: return "字号·标准"
        case .large: return "字号·大"
        case .extraLarge: return "字号·超大"
        }
    }

    var next: ReaderFontTier {
        ReaderFontTier(rawValue: (rawValue + 1) % 3) ?? .standard
    }
}
