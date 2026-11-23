import XCTest
@testable import CetHelper

/// 真实本地 Spring Boot（demo 模式）集成测试。
///
/// 运行方式：
/// ```sh
/// TEST_RUNNER_CET_E2E_BASE_URL=http://127.0.0.1:8080 xcodebuild ... test
/// ```
/// xcodebuild 会把 `TEST_RUNNER_` 前缀剥离后透传给测试进程，因此测试内读取
/// `CET_E2E_BASE_URL`；未注入时整组用例 `XCTSkip`，保证离线 `xcodebuild test` 仍为绿灯。
final class LiveServerTests: XCTestCase {

    private var baseUrl = ""
    private var client: ApiClient!
    private var session: Session!
    private var username = ""
    private let password = "Passw0rd!23"

    // MARK: - Fixtures（与 server/README.md demo 内置语料逐字一致）

    private static let demoArticle = DemoArticle.article
    private static let demoFirstSentence = "The report attributed the decline to lower demand."
    private static let demoSecondSentence = "Some students decline the invitation."

    private static let demoTranslationSource = TranslationViewModel.demoSource
    private static let demoTranslationDraft = "With the rapid development of the digital economy, programming skills are very important for students. Many companies prefer to hire young people who can think independently and have good team cooperation."

    private static let demoWritingPromise = WritingViewModel.demoPrompt
    private static let demoWritingEssay = WritingViewModel.demoEssay

    // MARK: - Lifecycle

    override func setUp() async throws {
        try await super.setUp()

        let environment = ProcessInfo.processInfo.environment
        let raw = environment["CET_E2E_BASE_URL"]
            ?? environment["TEST_RUNNER_CET_E2E_BASE_URL"]
            ?? ""
        guard !TextMetrics.trimSpace(raw).isEmpty else {
            throw XCTSkip("未注入 TEST_RUNNER_CET_E2E_BASE_URL，跳过真实服务端集成测试。")
        }
        baseUrl = try ApiClient.normalizeBaseUrl(raw)
        client = ApiClient()

        // 每个用例一个一次性账号，避免相互污染服务端 H2 数据。
        username = "e2e_\(Self.randomSuffix())"
        try await client.register(baseUrl: baseUrl, username: username, password: password)
        let token = try await client.login(baseUrl: baseUrl, username: username, password: password)
        let student = try await client.me(baseUrl: baseUrl, token: token)
        session = Session(serverUrl: baseUrl, token: token, student: student)
    }

    override func tearDown() async throws {
        if let session, let client {
            // 退出失败不影响用例结论（例如 401 用例已主动撤销 token）。
            try? await client.logout(baseUrl: session.serverUrl, token: session.token)
        }
        session = nil
        client = nil
        try await super.tearDown()
    }

    // MARK: - 1. health / 注册 / 登录 / me / logout / 401

    func testHealthReportsDemoMode() async throws {
        let health = try await client.health(baseUrl: baseUrl)
        XCTAssertEqual(health.status, "UP")
        XCTAssertEqual(health.mode, "demo")
        XCTAssertNotNil(health.version)
    }

    func testRegisterLoginMeLogoutAndRevokedToken() async throws {
        XCTAssertEqual(session.student.username, username)
        XCTAssertTrue(session.student.id > 0)

        let idBeforeLogout = session.student.id
        try await client.logout(baseUrl: baseUrl, token: session.token)

        do {
            _ = try await client.me(baseUrl: baseUrl, token: session.token)
            XCTFail("旧 token 在 logout 后仍应失效")
        } catch let error as ApiError {
            XCTAssertTrue(error.isUnauthorized, "期望 401 UNAUTHORIZED，实际 \(error)")
            guard case .http(let status, let code, let message) = error else {
                return XCTFail("期望 .http，实际 \(error)")
            }
            XCTAssertEqual(status, 401)
            XCTAssertEqual(code, "UNAUTHORIZED")
            XCTAssertFalse(TextMetrics.trimSpace(message).isEmpty)
        }
        // 防止误用：登出后 id 不应再被复用。
        XCTAssertGreaterThan(idBeforeLogout, 0)
    }

    func testDuplicateRegisterReturnsUsernameTaken() async throws {
        do {
            try await client.register(baseUrl: baseUrl, username: username, password: password)
            XCTFail("重复注册应返回 409 USERNAME_TAKEN")
        } catch let error as ApiError {
            guard case .http(let status, let code, let message) = error else {
                return XCTFail("期望 .http，实际 \(error)")
            }
            XCTAssertEqual(status, 409)
            XCTAssertEqual(code, "USERNAME_TAKEN")
            XCTAssertFalse(TextMetrics.trimSpace(message).isEmpty)
        }
    }

    // MARK: - 2. 查词 / 整句分析

    func testDemoArticleLookup() async throws {
        let result = try await client.explain(
            session,
            selection: "decline",
            sentence: Self.demoFirstSentence
        )
        XCTAssertEqual(result.mode, "demo")
        XCTAssertEqual(result.selection, "decline")
        XCTAssertEqual(result.sentence, Self.demoFirstSentence)
        XCTAssertFalse(TextMetrics.trimSpace(result.meaning).isEmpty)
        XCTAssertFalse(TextMetrics.trimSpace(result.explanation).isEmpty)
        XCTAssertTrue(result.contextual)

        let verb = try await client.explain(
            session,
            selection: "decline",
            sentence: Self.demoSecondSentence
        )
        XCTAssertEqual(verb.mode, "demo")
        XCTAssertNotEqual(verb.meaning, result.meaning, "两句 decline 的 demo 释义应不同")

        let phrase = try await client.explain(
            session,
            selection: "lower demand",
            sentence: Self.demoFirstSentence
        )
        XCTAssertEqual(phrase.mode, "demo")
        XCTAssertTrue(phrase.formattedMeta().contains("·"))
    }

    func testDemoSentenceAnalysisChunksLocate() async throws {
        let analysis = try await client.analyzeSentence(session, sentence: Self.demoFirstSentence)
        XCTAssertEqual(analysis.sentence, Self.demoFirstSentence)
        XCTAssertEqual(analysis.mode, "demo")
        XCTAssertFalse(TextMetrics.trimSpace(analysis.translation).isEmpty)
        XCTAssertFalse(TextMetrics.trimSpace(analysis.structure).isEmpty)
        XCTAssertEqual(analysis.chunks.count, 4)
        XCTAssertEqual(analysis.chunks.first?.text, "The report")

        // 片段必须按原句顺序不重叠地落到原句上。
        var lastEnd = 0
        for chunk in analysis.chunks {
            XCTAssertGreaterThanOrEqual(chunk.range.start, lastEnd)
            let ns = Self.demoFirstSentence as NSString
            XCTAssertEqual(ns.substring(with: chunk.range.nsRange), chunk.text)
            lastEnd = chunk.range.end
        }
    }

    // MARK: - 3. 汉译英提示 / 批改

    func testThreeTranslationHints() async throws {
        let keyword = try await client.translationHint(
            session,
            sourceText: Self.demoTranslationSource,
            type: .keyword
        )
        XCTAssertEqual(keyword.type, .keyword)
        XCTAssertEqual(keyword.mode, "demo")
        XCTAssertFalse(keyword.items.isEmpty)
        XCTAssertTrue(keyword.items.allSatisfy { !TextMetrics.trimSpace($0.title).isEmpty })
        XCTAssertTrue(keyword.items.allSatisfy { $0.body.contains("\n") })

        let phrase = try await client.translationHint(
            session,
            sourceText: Self.demoTranslationSource,
            type: .phrase
        )
        XCTAssertEqual(phrase.mode, "demo")
        XCTAssertFalse(phrase.items.isEmpty)

        let structure = try await client.translationHint(
            session,
            sourceText: Self.demoTranslationSource,
            type: .structure
        )
        XCTAssertEqual(structure.mode, "demo")
        XCTAssertGreaterThan(structure.items.count, 1)
        XCTAssertEqual(structure.items.first?.title, "主干")
    }

    func testTranslationReviewReturnsScoreAndLocatableIssues() async throws {
        let feedback = try await client.reviewTranslation(
            session,
            sourceText: Self.demoTranslationSource,
            studentTranslation: Self.demoTranslationDraft
        )
        XCTAssertEqual(feedback.sourceText, Self.demoTranslationSource)
        XCTAssertEqual(feedback.studentTranslation, Self.demoTranslationDraft)
        XCTAssertEqual(feedback.mode, "demo")
        XCTAssertFalse(TextMetrics.trimSpace(feedback.overallFeedback).isEmpty)
        XCTAssertFalse(feedback.issues.isEmpty)
        XCTAssertTrue(feedback.hasScore)
        XCTAssertEqual(feedback.scoreMax, 15)
        XCTAssertGreaterThanOrEqual(feedback.estimatedScore, 0)
        XCTAssertLessThanOrEqual(feedback.estimatedScore, feedback.scoreMax)
        XCTAssertNotNil(feedback.scoreBand)
        XCTAssertFalse(TextMetrics.trimSpace(feedback.rawJson).isEmpty)

        let original = Self.demoTranslationSource as NSString
        let draft = Self.demoTranslationDraft as NSString
        for issue in feedback.issues {
            XCTAssertFalse(TextMetrics.trimSpace(issue.quote).isEmpty)
            XCTAssertFalse(issue.matches.isEmpty, "引文 \(issue.quote) 必须能在快照中定位")
            let snapshot = issue.target == .source ? original : draft
            XCTAssertEqual(snapshot.substring(with: issue.matches[0].nsRange), issue.quote)
        }
    }

    // MARK: - 4. 写作批改 → 小练习 → 换题复测 → 复测判答

    func testWritingReviewExerciseAndRetestChain() async throws {
        let feedback = try await client.reviewWriting(
            session,
            prompt: Self.demoWritingPromise,
            essay: Self.demoWritingEssay
        )
        XCTAssertEqual(feedback.mode, "demo")
        XCTAssertEqual(feedback.prompt, Self.demoWritingPromise)
        XCTAssertEqual(feedback.essay, Self.demoWritingEssay)
        XCTAssertFalse(feedback.issues.isEmpty)
        XCTAssertTrue(feedback.hasScore)
        XCTAssertEqual(feedback.scoreMax, 15)

        guard let exercise = feedback.exercise else {
            return XCTFail("demo 写作批改应返回针对性练习")
        }
        XCTAssertTrue((0..<feedback.issues.count).contains(exercise.issueIndex))
        let issue = feedback.issues[exercise.issueIndex]
        XCTAssertFalse(TextMetrics.trimSpace(exercise.instruction).isEmpty)
        XCTAssertFalse(TextMetrics.trimSpace(exercise.item).isEmpty)

        let attemptAnswer = "The volunteers at the animal shelter feed the dogs every morning."
        let assessment = try await client.attemptWritingExercise(
            session,
            issue: issue,
            exercise: exercise,
            answer: attemptAnswer
        )
        XCTAssertEqual(assessment.answer, attemptAnswer)
        XCTAssertEqual(assessment.verdict, .correct)
        XCTAssertFalse(TextMetrics.trimSpace(assessment.feedback).isEmpty)
        XCTAssertFalse(TextMetrics.trimSpace(assessment.nextStep).isEmpty)

        let task = try await client.requestWritingRetest(session, issue: issue, exercise: exercise)
        XCTAssertFalse(TextMetrics.trimSpace(task.instruction).isEmpty)
        XCTAssertFalse(TextMetrics.trimSpace(task.item).isEmpty)

        let retestAnswer = "The student hosts check the microphones every Friday."
        let retest = try await client.attemptWritingRetest(
            session,
            issue: issue,
            exercise: exercise,
            task: task,
            answer: retestAnswer
        )
        XCTAssertEqual(retest.answer, retestAnswer)
        XCTAssertEqual(retest.verdict, .targetUsed)
        XCTAssertFalse(TextMetrics.trimSpace(retest.feedback).isEmpty)
        XCTAssertFalse(TextMetrics.trimSpace(retest.nextStep).isEmpty)
        XCTAssertNotNil(retest.quote)
        XCTAssertFalse(retest.matches.isEmpty)
    }

    // MARK: - 5. 自选材料 → 422 DEMO_UNSUPPORTED

    func testCustomMaterialReturnsDemoUnsupported() async throws {
        do {
            _ = try await client.explain(
                session,
                selection: "xylophone",
                sentence: "The xylophone sounds bright in the hall."
            )
            XCTFail("未收录选词应返回 422 DEMO_UNSUPPORTED")
        } catch let error as ApiError {
            XCTAssertTrue(error.isDemoUnsupported)
        }

        do {
            _ = try await client.analyzeSentence(session, sentence: "A custom sentence the demo corpus never covered.")
            XCTFail("未收录句子应返回 422 DEMO_UNSUPPORTED")
        } catch let error as ApiError {
            XCTAssertTrue(error.isDemoUnsupported)
            let formatted = ApiErrorFormatter.sentenceAnalysisFormat(error)
            XCTAssertEqual(formatted.message, "演示模式暂未收录该句子。")
            XCTAssertFalse(formatted.canRetry)
        }

        do {
            _ = try await client.translationHint(
                session,
                sourceText: "这是一个不包含在演示材料里的中文句子。",
                type: .keyword
            )
            XCTFail("未收录的中文材料应返回 422 DEMO_UNSUPPORTED")
        } catch let error as ApiError {
            XCTAssertTrue(error.isDemoUnsupported)
            let formatted = ApiErrorFormatter.translationHintFormat(error, typeTitle: "关键词")
            XCTAssertFalse(formatted.canRetry)
        }

        do {
            _ = try await client.reviewTranslation(
                session,
                sourceText: "这是一个不包含在演示材料里的中文句子。",
                studentTranslation: "This is a sentence outside the demo corpus."
            )
            XCTFail("未收录的译文应返回 422 DEMO_UNSUPPORTED")
        } catch let error as ApiError {
            XCTAssertTrue(error.isDemoUnsupported)
            let formatted = ApiErrorFormatter.translationReviewFormat(error)
            XCTAssertFalse(formatted.canRetry)
        }
    }

    // MARK: - 6. 词典联想（v4）

    func testDictionarySuggestFilterAndLimit() async throws {
        // 服务端内置词表中 abandon 为 CET-6，因此按 CET-6 过滤应命中且排首位。
        let cet6 = try await client.dictionarySuggest(session, query: "ab", level: "CET-6", limit: 5)
        XCTAssertFalse(cet6.isEmpty, "q=ab&level=CET-6 应命中内置词表")
        XCTAssertEqual(cet6.first?.word, "abandon", "前缀命中应排在最前")
        XCTAssertLessThanOrEqual(cet6.count, 5, "limit=5 必须生效")
        XCTAssertTrue(cet6.allSatisfy { $0.cetLevel == "CET-6" }, "级别过滤后只应保留 CET-6")
        XCTAssertTrue(cet6.allSatisfy { !TextMetrics.trimSpace($0.word).isEmpty })
        XCTAssertEqual(Set(cet6.map(\.word)).count, cet6.count, "联想结果按 lemma 去重")

        // 前缀 rank 0 优先于子串 rank 2：abandon/absorb/abstract 均以 ab 开头。
        let prefixWords = cet6.filter { $0.word.lowercased().hasPrefix("ab") }
        XCTAssertFalse(prefixWords.isEmpty)

        // CET-4 过滤不应包含 CET-6 的 abandon，且每条都必须是 CET-4。
        let cet4 = try await client.dictionarySuggest(session, query: "ab", level: "CET-4", limit: 5)
        XCTAssertTrue(cet4.allSatisfy { $0.cetLevel == "CET-4" })
        XCTAssertFalse(cet4.contains { $0.word == "abandon" }, "CET-4 过滤不应返回 CET-6 词条")

        // limit 上限：> 20 服务端截断为 20。
        let capped = try await client.dictionarySuggest(session, query: "a", level: nil, limit: 50)
        XCTAssertLessThanOrEqual(capped.count, 20, "limit 超过 20 时应截断为 20")

        // 非法 level -> 400 INVALID_INPUT。
        do {
            _ = try await client.dictionarySuggest(session, query: "ab", level: "CET-8", limit: 5)
            XCTFail("非法 level 应返回 400 INVALID_INPUT")
        } catch let error as ApiError {
            guard case .http(let status, let code, let message) = error else {
                return XCTFail("期望 .http，实际 \(error)")
            }
            XCTAssertEqual(status, 400)
            XCTAssertEqual(code, "INVALID_INPUT")
            XCTAssertFalse(TextMetrics.trimSpace(message).isEmpty)
        }
    }

    // MARK: - 7. 独立查词（v4）

    func testDictionaryLookupDemoAndUnsupported() async throws {
        let entry = try await client.dictionaryLookup(session, word: "abandoned")
        XCTAssertEqual(entry.word, "abandoned")
        XCTAssertEqual(entry.lemma, "abandon", "应还原词形到原形 lemma")
        XCTAssertEqual(entry.mode, "demo")
        XCTAssertFalse(TextMetrics.trimSpace(entry.meaning).isEmpty)
        XCTAssertFalse(TextMetrics.trimSpace(entry.phonetic).isEmpty)
        XCTAssertFalse(TextMetrics.trimSpace(entry.partOfSpeech).isEmpty)
        XCTAssertFalse(TextMetrics.trimSpace(entry.cetLevel).isEmpty)
        XCTAssertFalse(TextMetrics.trimSpace(entry.exampleSentence).isEmpty, "demo 词条应含英文例句")
        XCTAssertFalse(TextMetrics.trimSpace(entry.exampleTranslation).isEmpty, "demo 词条应含双语例句翻译")
        XCTAssertEqual(entry.canonicalWord, "abandon")

        // 未收录词 -> 422 DEMO_UNSUPPORTED，并带温和提示文案。
        do {
            _ = try await client.dictionaryLookup(session, word: "xylophone")
            XCTFail("未收录词应返回 422 DEMO_UNSUPPORTED")
        } catch let error as ApiError {
            XCTAssertTrue(error.isDemoUnsupported)
            guard case .http(let status, let code, let message) = error else {
                return XCTFail("期望 .http，实际 \(error)")
            }
            XCTAssertEqual(status, 422)
            XCTAssertEqual(code, "DEMO_UNSUPPORTED")
            XCTAssertFalse(TextMetrics.trimSpace(message).isEmpty)
        }

        // 非法字符 -> 400 INVALID_INPUT。
        do {
            _ = try await client.dictionaryLookup(session, word: "bad word!")
            XCTFail("非法词形应返回 400 INVALID_INPUT")
        } catch let error as ApiError {
            guard case .http(let status, let code, _) = error else {
                return XCTFail("期望 .http，实际 \(error)")
            }
            XCTAssertEqual(status, 400)
            XCTAssertEqual(code, "INVALID_INPUT")
        }
    }

    // MARK: - 8. AI 助教 SSE 流式（v4）

    @MainActor
    func testTutorChatStreamSseOrderAndValidation() async throws {
        let context = TutorContext(
            type: .word,
            title: "abandon",
            focusText: "abandon",
            referenceText: "They had to abandon the car in the snow.",
            extraNote: "释义：放弃；抛弃"
        )
        let message = TutorMessage(
            id: 0,
            serverUrl: session.serverUrl,
            studentId: session.student.id,
            sessionKey: context.sessionKey(),
            role: .user,
            content: "这个词在四六级真题里常见搭配有哪些？",
            contextType: context.type.rawValue,
            contextSummary: context.summary(),
            mode: "",
            createdAt: Int64(Date().timeIntervalSince1970)
        )

        var events: [TutorSseEvent] = []
        let finished = expectation(description: "tutor-sse-terminal")
        var streamError: Error?
        let handle = client.streamTutorChat(
            session,
            messages: [message],
            context: context,
            onEvent: { event in
                events.append(event)
                if case .done = event { finished.fulfill() }
                if case .error = event { finished.fulfill() }
            },
            onError: { error in
                streamError = error
                finished.fulfill()
            }
        )
        await fulfillment(of: [finished], timeout: 60)
        XCTAssertNil(streamError, "SSE 流不应报错：\(String(describing: streamError))")
        XCTAssertFalse(handle.isCancelled)

        // 严格顺序：meta ×1 -> delta ×N -> done ×1。
        XCTAssertFalse(events.isEmpty)
        guard case .meta(let mode, let contextType) = events.first else {
            return XCTFail("首个事件必须是 meta，实际 \(String(describing: events.first))")
        }
        XCTAssertEqual(mode, "demo")
        XCTAssertEqual(contextType, "WORD")

        guard case .done(let followUps) = events.last else {
            return XCTFail("末个事件必须是 done，实际 \(String(describing: events.last))")
        }
        XCTAssertFalse(followUps.isEmpty, "done 应带非空 followUps")
        XCTAssertTrue(followUps.allSatisfy { !TextMetrics.trimSpace($0).isEmpty })

        let deltaTexts: [String] = events.compactMap {
            if case .delta(let text) = $0 { return text }
            return nil
        }
        XCTAssertFalse(deltaTexts.isEmpty, "至少应收到一个 delta")
        XCTAssertFalse(TextMetrics.trimSpace(deltaTexts.joined()).isEmpty)

        // meta 只出现一次、done 之前不得出现 error。
        let metaCount = events.filter { if case .meta = $0 { return true } else { return false } }.count
        XCTAssertEqual(metaCount, 1)
        let doneIndex = events.count - 1
        for (index, event) in events.enumerated() {
            if case .error = event {
                XCTFail("事件 \(index) 不应是 error")
            }
            if case .done = event {
                XCTAssertEqual(index, doneIndex, "done 必须是最后一个事件")
            }
        }

        // 空 messages 同步校验 -> 400 INVALID_INPUT（JSON 错误，而非 SSE）。
        let validationDone = expectation(description: "empty-messages-400")
        var validationError: Error?
        _ = client.streamTutorChat(
            session,
            messages: [],
            context: .free,
            onEvent: { _ in },
            onError: { error in
                validationError = error
                validationDone.fulfill()
            }
        )
        await fulfillment(of: [validationDone], timeout: 30)
        guard let apiError = validationError as? ApiError else {
            return XCTFail("空 messages 应触发 ApiError，实际 \(String(describing: validationError))")
        }
        guard case .http(let status, let code, let text) = apiError else {
            return XCTFail("期望 .http，实际 \(apiError)")
        }
        XCTAssertEqual(status, 400)
        XCTAssertEqual(code, "INVALID_INPUT")
        XCTAssertFalse(TextMetrics.trimSpace(text).isEmpty)
    }

    // MARK: - 9. OCR 文章自动整理（v5）

    func testArticleOrganizeDemoReadingTranslationAndImageOnlyUnsupported() async throws {
        let rawEnglish = """
        Green Urban Commuting

        12
        Directions: Read the following passage carefully.
        Modern cities face grow-
        ing traffic pressure.
        A) Option noise
        """
        let reading = try await client.organizeArticle(
            session,
            rawText: rawEnglish,
            imageBase64: nil,
            imageMimeType: nil,
            target: .reading
        )
        XCTAssertEqual(reading.mode, "demo")
        XCTAssertEqual(reading.title, "Green Urban Commuting")
        XCTAssertEqual(reading.body, "Modern cities face growing traffic pressure.")
        XCTAssertFalse(TextMetrics.trimSpace(reading.summary).isEmpty)

        let rawChinese = """
        12
        近年来，绿色出行逐渐成为
        城市居民的日常选择。
        """
        let translation = try await client.organizeArticle(
            session,
            rawText: rawChinese,
            imageBase64: nil,
            imageMimeType: nil,
            target: .translation
        )
        XCTAssertEqual(translation.mode, "demo")
        XCTAssertEqual(translation.body, "近年来，绿色出行逐渐成为城市居民的日常选择。")

        // Demo 模式仅传图片无文本 -> 422 DEMO_UNSUPPORTED
        let tinyPngBase64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
        do {
            _ = try await client.organizeArticle(
                session,
                rawText: nil,
                imageBase64: tinyPngBase64,
                imageMimeType: "image/png",
                target: .reading
            )
            XCTFail("demo 模式纯图整理应返回 422 DEMO_UNSUPPORTED")
        } catch let error as ApiError {
            XCTAssertTrue(error.isDemoUnsupported, "期望 DEMO_UNSUPPORTED，实际 \(error)")
        }
    }

    // MARK: - Helpers

    private static func randomSuffix() -> String {
        let letters = Array("abcdefghijklmnopqrstuvwxyz0123456789")
        return String((0..<10).map { _ in letters.randomElement()! })
    }
}
