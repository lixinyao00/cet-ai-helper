import Foundation
import Observation

/// 登录成功后创建，登出/401 时整体丢弃（所有练习内存态随之清空）。
@MainActor @Observable final class SessionScope {
    let session: Session
    let home: HomeViewModel
    let dictionary: DictionaryViewModel
    let tutor: TutorViewModel
    let vocabulary: VocabularyViewModel
    let translation: TranslationViewModel
    let writing: WritingViewModel
    var activeTab: AppTab = .home
    var path: [AppScreen] = []

    @ObservationIgnored let environment: AppEnvironment
    @ObservationIgnored private let onUnauthorizedHandler: (Session) -> Void
    @ObservationIgnored private let onSignOutHandler: (Session) -> Void
    @ObservationIgnored private let onServerChangedHandler: ((Session, String) -> Void)?
    @ObservationIgnored private weak var activeReader: ReaderViewModel?

    init(
        session: Session,
        environment: AppEnvironment,
        onUnauthorized: @escaping (Session) -> Void,
        onSignOut: @escaping (Session) -> Void,
        onServerChanged: ((Session, String) -> Void)? = nil
    ) {
        self.session = session
        self.environment = environment
        self.onUnauthorizedHandler = onUnauthorized
        self.onSignOutHandler = onSignOut
        self.onServerChangedHandler = onServerChanged

        let unauthorizedCallback: @MainActor () -> Void = {
            onUnauthorized(session)
        }

        self.home = HomeViewModel(session: session, environment: environment)
        self.dictionary = DictionaryViewModel(
            session: session,
            environment: environment,
            onUnauthorized: unauthorizedCallback
        )
        self.tutor = TutorViewModel(
            session: session,
            environment: environment,
            onUnauthorized: unauthorizedCallback
        )

        var openInReaderClosure: (Favorite) -> Void = { _ in }
        var startClozeClosure: () -> Void = {}
        var openDictionaryClosure: (String) -> Void = { _ in }
        self.vocabulary = VocabularyViewModel(
            session: session,
            environment: environment,
            onOpenInReader: { favorite in openInReaderClosure(favorite) },
            onStartCloze: { startClozeClosure() },
            onOpenDictionary: { word in openDictionaryClosure(word) }
        )

        self.translation = TranslationViewModel(
            session: session,
            environment: environment,
            onUnauthorized: unauthorizedCallback
        )
        self.writing = WritingViewModel(
            session: session,
            environment: environment,
            onUnauthorized: unauthorizedCallback
        )

        openInReaderClosure = { [weak self] favorite in
            self?.push(.reader(articleId: favorite.articleId, focus: favorite.range))
        }
        startClozeClosure = { [weak self] in
            self?.push(.cloze)
        }
        openDictionaryClosure = { [weak self] word in
            self?.openDictionary(word)
        }

        self.home.onServerChanged = { [weak self] normalizedUrl in
            _ = self?.changeServer(to: normalizedUrl)
        }
        self.dictionary.onAskTutor = { [weak self] context in
            self?.openTutor(context: context)
        }
        self.translation.onPushAnswer = { [weak self] in
            self?.push(.translationAnswer)
        }
        self.translation.onAskTutor = { [weak self] context in
            self?.openTutor(context: context)
        }
        self.writing.onAskTutor = { [weak self] context in
            self?.openTutor(context: context)
        }
    }

    func selectTab(_ tab: AppTab) {
        activeTab = tab
        path.removeAll()
        translation.resetTransientState()
        writing.resetTransientState()
        switch tab {
        case .home:
            home.refresh()
        case .dictionary:
            dictionary.loadHistory()
        case .tutor:
            tutor.attach(tutor.context)
        case .vocabulary:
            vocabulary.refresh()
        }
    }

    func openDictionary(_ query: String?) {
        let trimmed = query.map { TextMetrics.trimSpace($0) }
        let normalizedQuery = (trimmed?.isEmpty == false) ? trimmed : nil
        if let normalizedQuery {
            dictionary.lookup(normalizedQuery)
        }
        path.append(.dictionary(query: normalizedQuery))
    }

    func openTutor(context: TutorContext) {
        tutor.attach(context)
        path.append(.tutor(context: context))
    }

    /// 校验并持久化新地址，成功返回 true 并触发登出回登录页提示 "服务端地址已保存，请重新登录。"；格式非法返回 false。
    @discardableResult
    func changeServer(to rawUrl: String) -> Bool {
        let normalized: String
        do {
            normalized = try ApiClient.normalizeBaseUrl(rawUrl)
        } catch {
            return false
        }
        flushAll()
        cancelAllInFlight()
        if let handler = onServerChangedHandler {
            handler(session, normalized)
        } else {
            environment.sessions.changeServer(normalized)
            onSignOutHandler(session)
        }
        return true
    }

    /// 取消 dictionary 防抖/查词任务与 tutor 在途 SSE 流（登出/401/切服前调用）。
    func cancelAllInFlight() {
        dictionary.cancelInFlight()
        tutor.cancelInFlight()
    }

    func push(_ screen: AppScreen) {
        path.append(screen)
    }

    func pop() {
        if !path.isEmpty {
            path.removeLast()
        }
    }

    func popToRoot() {
        path.removeAll()
    }

    func replaceTop(with screen: AppScreen) {
        if path.isEmpty {
            path.append(screen)
        } else {
            path[path.count - 1] = screen
        }
    }

    /// nil = 文章不存在（已删除），View 显示 "文章不存在或已删除。" 并提供返回。
    func makeReader(articleId: String, focus: TextRange?) -> ReaderViewModel? {
        let article: ReadingArticle
        if articleId == DemoArticle.id {
            article = DemoArticle.article
        } else if let imported = try? environment.database.findArticle(
            serverUrl: session.serverUrl,
            studentId: session.student.id,
            articleId: articleId
        ) {
            article = imported.readingArticle
        } else {
            return nil
        }

        let reader = ReaderViewModel(
            article: article,
            focus: focus,
            session: session,
            environment: environment,
            onUnauthorized: { [weak self] in
                self?.notifyUnauthorized()
            }
        )
        reader.onAskTutor = { [weak self] context in
            self?.openTutor(context: context)
        }
        activeReader = reader
        return reader
    }

    func makeVocabulary() -> VocabularyViewModel {
        vocabulary
    }

    func makeCloze() -> ClozeViewModel {
        ClozeViewModel(
            session: session,
            environment: environment,
            onOpenSource: { [weak self] favorite in
                if favorite.isDictionarySource {
                    self?.openDictionary(favorite.selection)
                } else {
                    self?.push(.reader(articleId: favorite.articleId, focus: favorite.range))
                }
            }
        )
    }

    func makePageImport(target: PageImportTarget, source: PageImportSource) -> PageImportViewModel {
        PageImportViewModel(
            target: target,
            initialSource: source,
            session: session,
            environment: environment,
            translation: translation,
            onPop: { [weak self] in
                self?.pop()
            },
            onReplaceTop: { [weak self] screen in
                self?.replaceTop(with: screen)
            },
            onUnauthorized: { [weak self] in
                self?.notifyUnauthorized()
            }
        )
    }

    func signOut() {
        flushAll()
        cancelAllInFlight()
        onSignOutHandler(session)
    }

    func notifyUnauthorized() {
        onUnauthorizedHandler(session)
    }

    func flushAll() {
        translation.flushDraft()
        writing.flushDraft()
        activeReader?.flushProgress()
        environment.drafts.flush()
    }
}
