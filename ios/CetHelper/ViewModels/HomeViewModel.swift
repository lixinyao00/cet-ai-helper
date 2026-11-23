import Foundation
import Observation

@MainActor @Observable final class HomeViewModel {
    let username: String
    let demoArticle: ReadingArticle
    private(set) var stats: HomeStats = HomeStats(articleCount: 0, favoriteCount: 0, dueCount: 0)
    private(set) var articles: [ArticleSummary] = []
    private(set) var serverMode: String? = nil
    var confirmation: ConfirmationRequest? = nil

    // —— 服务器状态栏与切换 Sheet 状态 ——
    var serverUrl: String { session.serverUrl }
    private(set) var isServerReachable: Bool = false
    /// "Demo 演示模式" / "Live 真实模型" / 自定义 mode / "在线" / "离线"
    var serverStatusLabel: String {
        if let mode = serverMode {
            let trimmed = TextMetrics.trimSpace(mode)
            if !trimmed.isEmpty {
                switch trimmed.lowercased() {
                case "demo":
                    return "Demo 演示模式"
                case "live":
                    return "Live 真实模型"
                default:
                    return trimmed
                }
            }
        }
        return isServerReachable ? "在线" : "离线"
    }
    private(set) var isProbingHealth: Bool = false
    private(set) var probeMessage: String? = nil

    var isServerSheetPresented: Bool = false
    var serverDraft: String = ""
    private(set) var serverDraftError: String? = nil
    private(set) var healthCheck: HealthCheckState = .idle

    @ObservationIgnored var onServerChanged: ((String) -> Void)?
    @ObservationIgnored private let session: Session
    @ObservationIgnored private let environment: AppEnvironment
    @ObservationIgnored private var healthTask: Task<Void, Never>?
    @ObservationIgnored private var sheetHealthTask: Task<Void, Never>?

    init(session: Session, environment: AppEnvironment) {
        self.session = session
        self.environment = environment
        self.username = session.student.username
        self.demoArticle = DemoArticle.article
    }

    func refresh() {
        loadLocalData()
        probeHealth()
    }

    func requestDelete(_ article: ArticleSummary) {
        confirmation = ConfirmationRequest(
            title: "删除材料",
            message: "确定要删除「\(article.title)」吗？删除后无法恢复。",
            confirmTitle: "删除",
            isDestructive: true,
            onConfirm: { [weak self] in
                self?.deleteArticle(article)
            }
        )
    }

    private static let metaDateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "MM-dd HH:mm"
        return formatter
    }()

    func formattedMeta(for article: ArticleSummary) -> String {
        if article.lastReadAt > 0 {
            let date = Date(timeIntervalSince1970: Double(article.lastReadAt) / 1000.0)
            return "上次阅读 \(Self.metaDateFormatter.string(from: date))"
        }
        let date = Date(timeIntervalSince1970: Double(article.createdAt) / 1000.0)
        return "导入于 \(Self.metaDateFormatter.string(from: date))"
    }

    /// 0...1，用于书架卡片进度条；nil = 未读过。
    func readingProgress(for article: ArticleSummary) -> Double? {
        guard article.lastReadAt > 0 || article.lastScrollY > 0 else {
            return nil
        }
        if let saved = environment.readerPrefs.articleProgress(for: session.owner, articleId: article.articleId) {
            return min(1.0, max(0.0, saved))
        }
        if article.lastScrollY <= 0 {
            return 0.05
        }
        return min(1.0, max(0.05, Double(article.lastScrollY) / 1200.0))
    }

    private func loadLocalData() {
        let serverUrl = session.serverUrl
        let studentId = session.student.id
        let now = environment.nowMs()

        let db = environment.database
        let loadedArticles = (try? db.listArticles(serverUrl: serverUrl, studentId: studentId)) ?? []
        let articleCount = (try? db.countArticles(serverUrl: serverUrl, studentId: studentId)) ?? loadedArticles.count
        let favoriteCount = (try? db.countAllFavorites(serverUrl: serverUrl, studentId: studentId)) ?? 0
        let dueCount = (try? db.countDueFavorites(serverUrl: serverUrl, studentId: studentId, nowMs: now)) ?? 0

        articles = loadedArticles
        stats = HomeStats(
            articleCount: articleCount,
            favoriteCount: favoriteCount,
            dueCount: dueCount
        )
    }

    private func deleteArticle(_ article: ArticleSummary) {
        try? environment.database.deleteArticle(
            serverUrl: session.serverUrl,
            studentId: session.student.id,
            articleId: article.articleId
        )
        environment.readerPrefs.clearArticleProgress(for: session.owner, articleId: article.articleId)
        loadLocalData()
    }

    func probeHealth() {
        healthTask?.cancel()
        let currentServerUrl = session.serverUrl
        isProbingHealth = true
        probeMessage = nil
        healthTask = Task { @MainActor [weak self] in
            guard let self else { return }
            defer { self.isProbingHealth = false }
            do {
                let info = try await self.environment.api.health(baseUrl: currentServerUrl)
                guard !Task.isCancelled else { return }
                self.isServerReachable = true
                self.serverMode = info.mode
                self.probeMessage = "已连接 · \(self.serverStatusLabel)"
            } catch is CancellationError {
                return
            } catch let apiError as ApiError where apiError == .cancelled {
                return
            } catch {
                guard !Task.isCancelled else { return }
                self.isServerReachable = false
                self.serverMode = nil
                self.probeMessage = ApiErrorFormatter.generalMessage(error)
            }
        }
    }

    func presentServerSettings() {
        serverDraft = session.serverUrl
        serverDraftError = nil
        healthCheck = .idle
        isServerSheetPresented = true
    }

    func testConnection() {
        serverDraftError = nil
        let normalized: String
        do {
            normalized = try ApiClient.normalizeBaseUrl(serverDraft)
        } catch {
            serverDraftError = BaseUrlError.invalid.localizedDescription
            healthCheck = .idle
            return
        }

        healthCheck = .checking
        sheetHealthTask?.cancel()
        sheetHealthTask = Task { @MainActor [weak self] in
            guard let self else { return }
            do {
                let info = try await self.environment.api.health(baseUrl: normalized)
                guard !Task.isCancelled else { return }
                self.healthCheck = .ok(info)
            } catch is CancellationError {
                return
            } catch let apiError as ApiError where apiError == .cancelled {
                return
            } catch {
                guard !Task.isCancelled else { return }
                self.healthCheck = .failed(message: ApiErrorFormatter.generalMessage(error))
            }
        }
    }

    func saveServer() {
        serverDraftError = nil
        let normalized: String
        do {
            normalized = try ApiClient.normalizeBaseUrl(serverDraft)
        } catch {
            serverDraftError = BaseUrlError.invalid.localizedDescription
            return
        }
        sheetHealthTask?.cancel()
        sheetHealthTask = nil
        isServerSheetPresented = false
        onServerChanged?(normalized)
    }
}
