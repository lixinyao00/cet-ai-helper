import XCTest
@testable import CetHelper

final class TutorContextAndFilterTests: XCTestCase {
    func testTutorContextSummaryQuickPromptsSessionKeyAndPayload() {
        // 1. FREE
        let free = TutorContext.free
        XCTAssertEqual(free.displayTitle, "自由问答")
        XCTAssertEqual(free.summary(), "自由提问四六级词汇、语法、阅读、翻译或写作问题")
        XCTAssertEqual(free.sessionKey(), "free")
        XCTAssertEqual(free.quickPrompts(), [
            "四六级阅读如何快速定位答案？",
            "帮我梳理高频虚拟语气考点",
            "汉译英常用高级衔接词有哪些？",
            "作文开头段怎么写更有亮点？"
        ])
        XCTAssertEqual(free.toPayloadDictionary(), ["type": "FREE"])

        // 2. WORD + Java String.hashCode() exact determinism
        // Java "abandon".hashCode() == -1207841163
        XCTAssertEqual(TutorContext.javaHashCode("abandon"), -1_207_841_163)
        XCTAssertEqual(TutorContext.javaHashCode(""), 0)
        // Verify deterministic hash for CJK UTF-16 code units: "中文" -> 0x4E2D * 31 + 0x6587 = 646838
        XCTAssertEqual(TutorContext.javaHashCode("中文"), Int32(0x4E2D) &* 31 &+ Int32(0x6587))

        let word = TutorContext(
            type: .word,
            title: "abandon",
            focusText: "abandon",
            referenceText: "They had to abandon the car.",
            extraNote: "释义：放弃"
        )
        XCTAssertEqual(word.type.badgeTitle, "单词上下文")
        XCTAssertEqual(word.displayTitle, "abandon")
        XCTAssertEqual(word.sessionKey(), "WORD:-1207841163")
        XCTAssertEqual(word.quickPrompts(), [
            "这个词在四六级真题里常见搭配有哪些？",
            "它和近义词有什么区别？",
            "能再给我两个双语例句吗？",
            "它有哪些常见的词性变形？"
        ])

        // 3. SENTENCE: summary collapses whitespace & truncates > 60 chars to 59 + "…"
        let longSentence = "   " + String(repeating: "a", count: 70) + "\n\t  tail"
        let sentence = TutorContext(
            type: .sentence,
            title: "",
            focusText: longSentence,
            referenceText: "",
            extraNote: ""
        )
        XCTAssertEqual(sentence.displayTitle, "句法上下文")
        let truncated = sentence.summary()
        XCTAssertEqual(truncated.count, 60)
        XCTAssertTrue(truncated.hasSuffix("…"))
        XCTAssertEqual(truncated, String(repeating: "a", count: 59) + "…")
        XCTAssertEqual(sentence.quickPrompts(), [
            "请通俗拆解这句话的从句结构",
            "这句话里有哪些四六级核心词汇或短语？",
            "如何把这句话翻译得更地道？"
        ])

        // 4. TRANSLATION: fallback order focusText -> title -> referenceText -> default
        let transFallback = TutorContext(
            type: .translation,
            title: "",
            focusText: "",
            referenceText: "  数字经济\n快速发展  ",
            extraNote: ""
        )
        XCTAssertEqual(transFallback.summary(), "数字经济 快速发展")
        XCTAssertEqual(transFallback.quickPrompts(), [
            "我的译文最需要先修改哪一处？",
            "能给我一个更高分的地道改写吗？",
            "这道题涉及哪些常考翻译句型？"
        ])

        let emptyWriting = TutorContext(type: .writing, title: "", focusText: "", referenceText: "", extraNote: "")
        XCTAssertEqual(emptyWriting.summary(), "当前学习上下文")
        XCTAssertEqual(emptyWriting.quickPrompts(), [
            "如何把我的作文提升到高分档？",
            "请帮我润色其中的薄弱句子",
            "这个话题有哪些四六级亮点表达？"
        ])

        // 5. Payload clamping to 200 / 2000 / 6000 / 4000
        let huge = TutorContext(
            type: .writing,
            title: String(repeating: "t", count: 300),
            focusText: String(repeating: "f", count: 2500),
            referenceText: String(repeating: "r", count: 6500),
            extraNote: String(repeating: "e", count: 4500)
        )
        let payload = huge.toPayloadDictionary()
        XCTAssertEqual(payload["title"]?.utf16.count, 200)
        XCTAssertEqual(payload["focusText"]?.utf16.count, 2000)
        XCTAssertEqual(payload["referenceText"]?.utf16.count, 6000)
        XCTAssertEqual(payload["extraNote"]?.utf16.count, 4000)
    }

    @MainActor
    func testVocabularyThreeLevelFiltersSearchAndRouting() throws {
        let tempDir = FileManager.default.temporaryDirectory.appendingPathComponent("cet-vocab-v4-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }

        let suiteName = "com.cethelper.tests.vocabv4.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defer { defaults.removePersistentDomain(forName: suiteName) }

        let db = try StudyDatabase(path: tempDir.appendingPathComponent("study.db").path)
        let session = Session(serverUrl: "http://127.0.0.1:8080", token: "tok", student: Student(id: 1, username: "alice"))
        let env = AppEnvironment(
            database: db,
            sessions: SessionStore(defaults: defaults),
            drafts: DraftStore(defaults: defaults),
            readerPrefs: ReaderPreferences(defaults: defaults),
            api: ApiClient(configuration: StubURLProtocol.makeConfiguration())
        )

        // Seed 4 favorites covering all combinations of level, source, mastery, lemma
        let f1 = Favorite(
            serverUrl: session.serverUrl,
            studentId: 1,
            articleId: DemoArticle.id,
            startOffset: 0,
            endOffset: 7,
            selection: "declined",
            sentence: "Sales declined sharply.",
            meaning: "下降",
            explanation: "exp",
            mode: "demo",
            savedAt: 100,
            masteryLevel: 0,
            lemma: "decline",
            cetLevel: "CET-4",
            sourceType: Favorite.sourceReader
        )
        let f2 = Favorite(
            serverUrl: session.serverUrl,
            studentId: 1,
            articleId: Favorite.dictArticleId,
            startOffset: 12,
            endOffset: 19,
            selection: "abandon",
            sentence: "They had to abandon the car.",
            meaning: "放弃",
            explanation: "exp",
            mode: "demo",
            savedAt: 200,
            masteryLevel: 2,
            lemma: "abandon",
            cetLevel: "cet-4",
            sourceType: Favorite.sourceDictionary
        )
        let f3 = Favorite(
            serverUrl: session.serverUrl,
            studentId: 1,
            articleId: Favorite.dictArticleId,
            startOffset: 0,
            endOffset: 9,
            selection: "resilient",
            sentence: "Resilient communities recover.",
            meaning: "有韧性的",
            explanation: "exp",
            mode: "demo",
            savedAt: 300,
            masteryLevel: 3,
            lemma: "resilient",
            cetLevel: "CET-6",
            sourceType: Favorite.sourceDictionary
        )
        let f4 = Favorite(
            serverUrl: session.serverUrl,
            studentId: 1,
            articleId: DemoArticle.id,
            startOffset: 10,
            endOffset: 20,
            selection: "invitation",
            sentence: "Some students decline the invitation.",
            meaning: "邀请",
            explanation: "exp",
            mode: "demo",
            savedAt: 400,
            masteryLevel: 1,
            lemma: "invite",
            cetLevel: "CET-6",
            sourceType: Favorite.sourceReader
        )
        for fav in [f1, f2, f3, f4] {
            try db.insertFavorite(fav)
        }

        var openedReaderFav: Favorite?
        var openedDictWord: String?
        let vm = VocabularyViewModel(
            session: session,
            environment: env,
            onOpenInReader: { openedReaderFav = $0 },
            onStartCloze: {},
            onOpenDictionary: { openedDictWord = $0 }
        )
        vm.refresh()
        XCTAssertEqual(vm.filteredFavorites.count, 4)

        // Level filter (case-insensitive CET-4)
        vm.levelFilter = .cet4
        XCTAssertEqual(Set(vm.filteredFavorites.map(\.selection)), ["declined", "abandon"])

        // Source filter combined with level
        vm.sourceFilter = .dictionary
        XCTAssertEqual(vm.filteredFavorites.map(\.selection), ["abandon"])

        // Mastery filter
        vm.resetFilters()
        vm.masteryFilter = .unreviewed
        XCTAssertEqual(vm.filteredFavorites.map(\.selection), ["declined"])
        vm.masteryFilter = .learning
        XCTAssertEqual(Set(vm.filteredFavorites.map(\.selection)), ["abandon", "invitation"])
        vm.masteryFilter = .mastered
        XCTAssertEqual(vm.filteredFavorites.map(\.selection), ["resilient"])

        // Search by lemma and resolvedSourceTitle ("词典查词")
        vm.resetFilters()
        vm.query = "invite"
        XCTAssertEqual(vm.filteredFavorites.map(\.selection), ["invitation"])
        vm.query = "词典查词"
        XCTAssertEqual(Set(vm.filteredFavorites.map(\.selection)), ["abandon", "resilient"])

        // Routing: dictionary favorite -> openDictionary, reader favorite -> openInReader
        vm.openFavorite(f2)
        XCTAssertEqual(openedDictWord, "abandon")
        vm.openFavorite(f1)
        XCTAssertEqual(openedReaderFav?.selection, "declined")
    }

    @MainActor
    func testSessionScopeNavigationPreserveStateAndServerChange() async throws {
        let tempDir = FileManager.default.temporaryDirectory.appendingPathComponent("cet-scope-v4-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }

        let suiteName = "com.cethelper.tests.scopev4.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defer { defaults.removePersistentDomain(forName: suiteName) }

        let db = try StudyDatabase(path: tempDir.appendingPathComponent("study.db").path)
        let env = AppEnvironment(
            database: db,
            sessions: SessionStore(defaults: defaults),
            drafts: DraftStore(defaults: defaults),
            readerPrefs: ReaderPreferences(defaults: defaults),
            api: ApiClient(configuration: StubURLProtocol.makeConfiguration())
        )
        let session = Session(serverUrl: "http://127.0.0.1:8080", token: "tok", student: Student(id: 1, username: "alice"))
        env.sessions.save(session)

        let appVm = AppViewModel(environment: env)
        // Sign in via handleServerChanged test using a live SessionScope attached to AppViewModel
        var changedTo: String?
        let scope = SessionScope(
            session: session,
            environment: env,
            onUnauthorized: { _ in },
            onSignOut: { _ in },
            onServerChanged: { _, normalized in changedTo = normalized }
        )

        // Translation draft + review preserved across L2 push to tutor
        scope.translation.source = "随着数字经济的快速发展。"
        scope.translation.draft = "With the rapid development of the digital economy."
        let transFeedback = TranslationFeedback(
            sourceText: scope.translation.source,
            studentTranslation: scope.translation.draft,
            mode: "demo",
            overallFeedback: "整体流畅",
            issues: [],
            estimatedScore: 13,
            scoreMax: 15,
            scoreBand: "良好",
            referenceTranslation: "With the rapid development of the digital economy.",
            rawJson: "{}"
        )
        scope.translation.review.setValue(transFeedback)
        XCTAssertTrue(scope.translation.canAskTutor)
        scope.translation.askTutor()
        XCTAssertEqual(scope.path.count, 1)
        if case .tutor(let ctx) = scope.path.last {
            XCTAssertEqual(ctx.type, .translation)
            XCTAssertEqual(ctx.title, "翻译练习")
            XCTAssertTrue(ctx.extraNote.contains("预估分：13/15分（良好）"))
        } else {
            XCTFail("Expected .tutor on path")
        }
        // Review & draft still intact while tutor is pushed
        XCTAssertNotNil(scope.translation.review.value)
        XCTAssertEqual(scope.translation.draft, "With the rapid development of the digital economy.")

        // Switching L1 tab clears path and transient review state while keeping draft
        scope.selectTab(.vocabulary)
        XCTAssertEqual(scope.activeTab, .vocabulary)
        XCTAssertTrue(scope.path.isEmpty)
        XCTAssertNil(scope.translation.review.value)
        XCTAssertEqual(scope.translation.draft, "With the rapid development of the digital economy.")

        // Invalid server URL rejected; valid URL normalized and forwarded
        XCTAssertFalse(scope.changeServer(to: "not a url"))
        XCTAssertNil(changedTo)
        XCTAssertTrue(scope.changeServer(to: "https://Example.COM:443/api/"))
        XCTAssertEqual(changedTo, "https://example.com/api")

        // WritingViewModel tutor context + resetTransientState preserves essay draft
        scope.writing.prompt = WritingViewModel.demoPrompt
        scope.writing.essay = WritingViewModel.demoEssay
        let writingFeedback = WritingFeedback(
            prompt: scope.writing.prompt,
            essay: scope.writing.essay,
            mode: "demo",
            overallFeedback: "结构清晰",
            issues: [],
            exercise: nil,
            estimatedScore: 12,
            scoreMax: 15,
            scoreBand: "中等",
            rawJson: "{}"
        )
        scope.writing.review.setValue(writingFeedback)
        let writingCtx = scope.writing.buildTutorContext()
        XCTAssertEqual(writingCtx?.type, .writing)
        XCTAssertEqual(writingCtx?.title.count, 40)
        XCTAssertTrue(writingCtx?.extraNote.contains("预估分：12/15分（中等）") == true)
        scope.writing.resetTransientState()
        XCTAssertNil(scope.writing.review.value)
        XCTAssertEqual(scope.writing.essay, WritingViewModel.demoEssay)

        // Verify AppViewModel.handleServerChanged persists URL and sets exact notice
        StubURLProtocol.setHandler { req in
            makeJSONResponse(url: req.url!, json: ["id": 1, "username": "alice"])
        }
        appVm.start()
        let deadline = Date().addingTimeInterval(2.0)
        while Date() < deadline {
            if case .signedIn = appVm.phase { break }
            try? await Task.sleep(for: .milliseconds(20))
        }
        guard case .signedIn(let signedInScope) = appVm.phase else {
            return XCTFail("Expected signedIn")
        }
        XCTAssertTrue(signedInScope.changeServer(to: "http://192.168.1.10:8080/"))
        guard case .signedOut = appVm.phase else {
            return XCTFail("Expected signedOut after server change")
        }
        XCTAssertEqual(env.sessions.serverUrl, "http://192.168.1.10:8080")
        XCTAssertEqual(appVm.login.serverUrl, "http://192.168.1.10:8080")
        XCTAssertEqual(appVm.login.noticeMessage, "服务端地址已保存，请重新登录。")
        StubURLProtocol.setHandler(nil)
    }
}
