import XCTest
@testable import CetHelper

final class DictionaryAndClozeTests: XCTestCase {
    private var api: ApiClient!
    private let session = Session(
        serverUrl: "http://127.0.0.1:8080",
        token: "dict-token",
        student: Student(id: 1, username: "alice")
    )

    override func setUp() {
        super.setUp()
        StubURLProtocol.setHandler(nil)
        api = ApiClient(configuration: StubURLProtocol.makeConfiguration())
    }

    override func tearDown() {
        StubURLProtocol.setHandler(nil)
        api = nil
        super.tearDown()
    }

    func testDictionarySuggestAndLookupDecodingAndErrorHandling() async throws {
        StubURLProtocol.setHandler { req in
            let url = req.url!
            if url.path.hasSuffix("/dictionary/suggest") {
                let comps = URLComponents(url: url, resolvingAgainstBaseURL: false)!
                let queryMap = Dictionary(uniqueKeysWithValues: (comps.queryItems ?? []).map { ($0.name, $0.value ?? "") })
                XCTAssertEqual(queryMap["q"], "ab")
                XCTAssertEqual(queryMap["level"], "CET-4")
                XCTAssertEqual(queryMap["limit"], "5")
                XCTAssertEqual(req.value(forHTTPHeaderField: "Authorization"), "Bearer dict-token")
                return makeJSONResponse(url: url, json: [
                    [
                        "word": "abandon",
                        "phonetic": "/əˈbændən/",
                        "partOfSpeech": "v.",
                        "cetLevel": "CET-4",
                        "meaning": "放弃；抛弃"
                    ],
                    [
                        "word": "ability"
                    ]
                ])
            }
            if url.path.hasSuffix("/dictionary/lookup") {
                let body = try JSONSerialization.jsonObject(with: readBodyData(from: req)) as! [String: Any]
                let word = body["word"] as! String
                if word == "abandoned" {
                    return makeJSONResponse(url: url, json: [
                        "word": "abandoned",
                        "lemma": "abandon",
                        "phonetic": "/əˈbændən/",
                        "partOfSpeech": "v.",
                        "cetLevel": "CET-4",
                        "meaning": "放弃；抛弃",
                        "explanation": "用法说明：abandon sth",
                        "exampleSentence": "They had to abandon the car in the snow.",
                        "exampleTranslation": "他们不得不把车丢弃在雪地里。",
                        "mode": "demo"
                    ])
                }
                if word == "minimal" {
                    return makeJSONResponse(url: url, json: [
                        "word": "minimal",
                        "meaning": "最小的"
                    ])
                }
                return makeJSONResponse(url: url, status: 422, json: [
                    "code": "DEMO_UNSUPPORTED",
                    "message": "演示模式暂未收录该词，请换一个四六级常见词。"
                ])
            }
            return makeJSONResponse(url: url, status: 404, json: [:])
        }

        let suggestions = try await api.dictionarySuggest(session, query: "ab", level: "CET-4", limit: 5)
        XCTAssertEqual(suggestions.count, 2)
        XCTAssertEqual(suggestions[0].word, "abandon")
        XCTAssertEqual(suggestions[0].cetLevel, "CET-4")
        XCTAssertEqual(suggestions[1].word, "ability")
        XCTAssertEqual(suggestions[1].meaning, "")

        let entry = try await api.dictionaryLookup(session, word: "abandoned")
        XCTAssertEqual(entry.word, "abandoned")
        XCTAssertEqual(entry.lemma, "abandon")
        XCTAssertEqual(entry.displayHeading, "abandoned (abandon)")
        XCTAssertEqual(entry.canonicalWord, "abandon")
        XCTAssertTrue(entry.isDemo)

        let minimal = try await api.dictionaryLookup(session, word: "minimal")
        XCTAssertEqual(minimal.lemma, "minimal")
        XCTAssertEqual(minimal.mode, "demo")
        XCTAssertEqual(minimal.displayHeading, "minimal")

        do {
            _ = try await api.dictionaryLookup(session, word: "nonexistentword")
            XCTFail("Expected DEMO_UNSUPPORTED")
        } catch let error as ApiError {
            XCTAssertTrue(error.isDemoUnsupported)
            XCTAssertEqual(ApiErrorFormatter.generalMessage(error), "演示模式暂未收录该词，请换一个四六级常见词。")
        }
    }

    func testFavoriteFromDictionaryOffsetsAndExplanationFormatting() {
        // Case 1: case-insensitive match with UTF-16 emoji prefix + exampleTranslation present
        let hitEntry = DictionaryEntry(
            word: "abandoned",
            lemma: "abandon",
            phonetic: "/əˈbændən/",
            partOfSpeech: "v.",
            cetLevel: "CET-4",
            meaning: "放弃；抛弃",
            explanation: "用法说明",
            exampleSentence: "😀 Never ABANDON your dream.",
            exampleTranslation: "永远不要放弃梦想。",
            mode: "live"
        )
        let hitFav = Favorite.fromDictionary(serverUrl: session.serverUrl, studentId: 1, entry: hitEntry, now: 1000)
        XCTAssertEqual(hitFav.articleId, Favorite.dictArticleId)
        XCTAssertEqual(hitFav.sourceType, Favorite.sourceDictionary)
        XCTAssertTrue(hitFav.isDictionarySource)
        XCTAssertEqual(hitFav.resolvedSourceTitle, "词典查词")
        XCTAssertEqual(hitFav.selection, "abandon")
        // "😀 " is 3 UTF-16 units (2 for surrogate pair + 1 space), "Never " is 6 -> start = 9, length = 7 -> end = 16
        XCTAssertEqual(hitFav.startOffset, 9)
        XCTAssertEqual(hitFav.endOffset, 16)
        XCTAssertEqual(hitFav.explanation, "用法说明\n例句翻译：永远不要放弃梦想。")
        XCTAssertEqual(hitFav.mode, "live")

        // Case 2: word not in example sentence -> 0/0, no exampleTranslation -> no suffix
        let missEntry = DictionaryEntry(
            word: "resilient",
            lemma: "",
            phonetic: "",
            partOfSpeech: "adj.",
            cetLevel: "CET-6",
            meaning: "有韧性的",
            explanation: "高频词",
            exampleSentence: "The team recovered quickly.",
            exampleTranslation: "",
            mode: ""
        )
        let missFav = Favorite.fromDictionary(serverUrl: session.serverUrl, studentId: 1, entry: missEntry, now: 2000)
        XCTAssertEqual(missFav.selection, "resilient")
        XCTAssertEqual(missFav.startOffset, 0)
        XCTAssertEqual(missFav.endOffset, 0)
        XCTAssertEqual(missFav.explanation, "高频词")
        XCTAssertEqual(missFav.mode, "demo")
    }

    func testClozeQuestionAndRoundSupportDictionaryFavorites() {
        let validEntry = DictionaryEntry(
            word: "abandon",
            lemma: "abandon",
            phonetic: "/əˈbændən/",
            partOfSpeech: "v.",
            cetLevel: "CET-4",
            meaning: "放弃",
            explanation: "exp",
            exampleSentence: "They had to Abandon the car in the snow.",
            exampleTranslation: "他们不得不弃车。",
            mode: "demo"
        )
        let validDictFav = Favorite.fromDictionary(serverUrl: session.serverUrl, studentId: 1, entry: validEntry, now: 500)

        let dictQuestion = ClozeQuestion.from(article: nil, favorite: validDictFav)
        XCTAssertNotNil(dictQuestion)
        XCTAssertEqual(dictQuestion?.articleTitle, "词典查词")
        XCTAssertEqual(dictQuestion?.maskedTitle, "词典查词")
        XCTAssertEqual(dictQuestion?.maskedSentence, "They had to ____ the car in the snow.")
        XCTAssertEqual(dictQuestion?.hint, "a _ _ _ _ _ _ (7)")

        // Stale offsets still fall back to case-insensitive search in sentence
        let staleOffsetFav = Favorite(
            serverUrl: session.serverUrl,
            studentId: 1,
            articleId: Favorite.dictArticleId,
            startOffset: 0,
            endOffset: 0,
            selection: "abandon",
            sentence: "Do not ABANDON hope.",
            meaning: "放弃",
            explanation: "exp",
            mode: "demo",
            savedAt: 400,
            lemma: "abandon",
            cetLevel: "CET-4",
            sourceType: Favorite.sourceDictionary
        )
        let fallbackQ = ClozeQuestion.from(article: nil, favorite: staleOffsetFav)
        XCTAssertEqual(fallbackQ?.maskedSentence, "Do not ____ hope.")

        // No example sentence -> nil (cannot form cloze question)
        let noSentenceFav = Favorite(
            serverUrl: session.serverUrl,
            studentId: 1,
            articleId: Favorite.dictArticleId,
            startOffset: 0,
            endOffset: 0,
            selection: "ephemeral",
            sentence: "   ",
            meaning: "短暂的",
            explanation: "exp",
            mode: "demo",
            savedAt: 300,
            sourceType: Favorite.sourceDictionary
        )
        XCTAssertNil(ClozeQuestion.from(article: nil, favorite: noSentenceFav))

        // Mixed ClozeRound with reader favorite + dictionary favorite + invalid dictionary favorite + missing article
        let article = DemoArticle.article
        let range = (article.body as NSString).range(of: "decline")
        let readerFav = makeSampleFavorite(
            articleId: article.id,
            startOffset: range.location,
            endOffset: range.location + range.length,
            selection: "decline",
            sentence: "The report attributed the decline to lower demand.",
            savedAt: 600
        )
        let missingArticleFav = makeSampleFavorite(
            articleId: "missing-article",
            startOffset: 0,
            endOffset: 7,
            selection: "decline",
            sentence: "The report attributed the decline to lower demand.",
            savedAt: 700
        )

        let creation = ClozeRound.create(
            favorites: [readerFav, validDictFav, noSentenceFav, missingArticleFav],
            articles: [article.id: article],
            now: 10_000
        )
        XCTAssertNotNil(creation.round)
        XCTAssertEqual(creation.round?.questions.count, 2)
        XCTAssertEqual(creation.skippedCount, 2)
        let selections = Set(creation.round?.questions.map(\.answer) ?? [])
        XCTAssertEqual(selections, ["decline", "abandon"])
    }

    @MainActor
    func testDictionaryViewModelLookupFavoriteAndClozeRouting() async throws {
        let tempDir = FileManager.default.temporaryDirectory.appendingPathComponent("cet-dictvm-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }

        let suiteName = "com.cethelper.tests.dictvm.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defer { defaults.removePersistentDomain(forName: suiteName) }

        let db = try StudyDatabase(path: tempDir.appendingPathComponent("study.db").path)
        let env = AppEnvironment(
            database: db,
            sessions: SessionStore(defaults: defaults),
            drafts: DraftStore(defaults: defaults),
            readerPrefs: ReaderPreferences(defaults: defaults),
            api: api
        )

        StubURLProtocol.setHandler { req in
            let url = req.url!
            if url.path.hasSuffix("/dictionary/lookup") {
                return makeJSONResponse(url: url, json: [
                    "word": "abandon",
                    "lemma": "abandon",
                    "phonetic": "/əˈbændən/",
                    "partOfSpeech": "v.",
                    "cetLevel": "CET-4",
                    "meaning": "放弃",
                    "explanation": "用法说明",
                    "exampleSentence": "They had to abandon the car.",
                    "exampleTranslation": "他们不得不弃车。",
                    "mode": "demo"
                ])
            }
            return makeJSONResponse(url: url, json: [])
        }

        var askedContext: TutorContext?
        let vm = DictionaryViewModel(
            session: session,
            environment: env,
            onUnauthorized: {},
            onAskTutor: { askedContext = $0 }
        )

        vm.lookup("abandon")
        let deadline = Date().addingTimeInterval(2.0)
        while vm.current == nil && Date() < deadline {
            try? await Task.sleep(for: .milliseconds(20))
        }

        XCTAssertEqual(vm.current?.word, "abandon")
        XCTAssertFalse(vm.isFavorite)
        XCTAssertEqual(vm.history.map(\.word), ["abandon"])

        vm.toggleFavorite()
        XCTAssertTrue(vm.isFavorite)
        XCTAssertNotNil(try db.findDictionaryFavorite(serverUrl: session.serverUrl, studentId: 1, selection: "abandon"))

        vm.askTutor()
        XCTAssertEqual(askedContext?.type, .word)
        XCTAssertEqual(askedContext?.title, "abandon")
        XCTAssertEqual(askedContext?.focusText, "abandon")
        XCTAssertEqual(askedContext?.referenceText, "They had to abandon the car.")
        XCTAssertTrue(askedContext?.extraNote.contains("释义：放弃") == true)

        // ClozeViewModel starts with the saved dictionary favorite and routes to dictionary detail
        var openedFav: Favorite?
        let clozeVm = ClozeViewModel(
            session: session,
            environment: env,
            onOpenSource: { openedFav = $0 }
        )
        clozeVm.start()
        XCTAssertTrue(clozeVm.isCurrentDictionarySource)
        XCTAssertEqual(clozeVm.currentSourceButtonTitle, "词典详情")
        clozeVm.openCurrentSource()
        XCTAssertEqual(openedFav?.articleId, Favorite.dictArticleId)
        XCTAssertEqual(openedFav?.selection, "abandon")
    }
}
