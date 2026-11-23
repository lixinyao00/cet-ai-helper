import Foundation

final class ReaderPreferences {
    static let fontTierKey = "font_tier"

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    var fontTier: ReaderFontTier {
        get {
            let raw = defaults.integer(forKey: Self.fontTierKey)
            return ReaderFontTier(rawValue: raw) ?? .standard
        }
        set {
            defaults.set(newValue.rawValue, forKey: Self.fontTierKey)
        }
    }

    func demoScrollY(for owner: ArticleOwner) -> Int {
        defaults.integer(forKey: Self.demoScrollKey(for: owner))
    }

    func setDemoScrollY(_ scrollY: Int, for owner: ArticleOwner) {
        defaults.set(max(0, scrollY), forKey: Self.demoScrollKey(for: owner))
    }

    static func demoScrollKey(for owner: ArticleOwner) -> String {
        "demo_scroll|\(owner.serverUrl)|\(owner.studentId)"
    }

    func articleProgress(for owner: ArticleOwner, articleId: String) -> Double? {
        let key = Self.articleProgressKey(for: owner, articleId: articleId)
        guard defaults.object(forKey: key) != nil else { return nil }
        return defaults.double(forKey: key)
    }

    func setArticleProgress(_ progress: Double, for owner: ArticleOwner, articleId: String) {
        let key = Self.articleProgressKey(for: owner, articleId: articleId)
        defaults.set(min(1.0, max(0.0, progress)), forKey: key)
    }

    func clearArticleProgress(for owner: ArticleOwner, articleId: String) {
        defaults.removeObject(forKey: Self.articleProgressKey(for: owner, articleId: articleId))
    }

    static func articleProgressKey(for owner: ArticleOwner, articleId: String) -> String {
        "article_progress|\(owner.serverUrl)|\(owner.studentId)|\(articleId)"
    }
}
