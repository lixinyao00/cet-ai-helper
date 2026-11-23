import SQLite3
import XCTest
@testable import CetHelper

final class StudyDatabaseMigrationV4Tests: XCTestCase {
    private var tempDir: URL!

    override func setUpWithError() throws {
        tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("cet-db-v4-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        if let tempDir {
            try? FileManager.default.removeItem(at: tempDir)
        }
    }

    func testRealV3DatabaseMigratesLosslesslyAndIdempotentlyToV4() throws {
        let dbPath = tempDir.appendingPathComponent("legacy_v3.db").path

        // 1. Seed a genuine v3 SQLite database with the 17-column favorites table.
        var rawDb: OpaquePointer?
        XCTAssertEqual(sqlite3_open(dbPath, &rawDb), SQLITE_OK)
        guard let handle = rawDb else {
            return XCTFail("Failed to create v3 database")
        }
        let seedSql = """
        PRAGMA journal_mode = WAL;
        CREATE TABLE favorites (
            serverUrl TEXT NOT NULL,
            studentId INTEGER NOT NULL,
            articleId TEXT NOT NULL,
            startOffset INTEGER NOT NULL,
            endOffset INTEGER NOT NULL,
            selection TEXT NOT NULL,
            sentence TEXT NOT NULL,
            meaning TEXT NOT NULL,
            explanation TEXT NOT NULL,
            mode TEXT NOT NULL,
            savedAt INTEGER NOT NULL,
            phonetic TEXT NOT NULL DEFAULT '',
            part_of_speech TEXT NOT NULL DEFAULT '',
            mastery_level INTEGER NOT NULL DEFAULT 0,
            review_count INTEGER NOT NULL DEFAULT 0,
            last_reviewed_at INTEGER NOT NULL DEFAULT 0,
            next_review_at INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY (serverUrl, studentId, articleId, startOffset, endOffset)
        );
        INSERT INTO favorites (
            serverUrl, studentId, articleId, startOffset, endOffset,
            selection, sentence, meaning, explanation, mode, savedAt,
            phonetic, part_of_speech, mastery_level, review_count, last_reviewed_at, next_review_at
        ) VALUES (
            'http://127.0.0.1:8080', 7, 'decline-demo-v1', 26, 33,
            'decline', 'The report attributed the decline to lower demand.',
            '下降', '数量减少', 'demo', 123456,
            '/dɪˈklaɪn/', 'n.', 2, 3, 200000, 300000
        );
        PRAGMA user_version = 3;
        """
        XCTAssertEqual(sqlite3_exec(handle, seedSql, nil, nil, nil), SQLITE_OK)
        sqlite3_close(handle)

        // 2. Open with StudyDatabase -> migrates v3 -> v4.
        let migrated = try StudyDatabase(path: dbPath)
        XCTAssertEqual(migrated.userVersion(), 4)

        let rows = try migrated.listFavorites(serverUrl: "http://127.0.0.1:8080", studentId: 7)
        XCTAssertEqual(rows.count, 1)
        let legacy = rows[0]
        XCTAssertEqual(legacy.selection, "decline")
        XCTAssertEqual(legacy.masteryLevel, 2)
        XCTAssertEqual(legacy.reviewCount, 3)
        XCTAssertEqual(legacy.lemma, "")
        XCTAssertEqual(legacy.cetLevel, "")
        XCTAssertEqual(legacy.sourceType, Favorite.sourceReader)
        XCTAssertFalse(legacy.isDictionarySource)

        // 3. Verify newly created v4 tables work immediately on migrated DB.
        let hist = DictionaryHistory(
            serverUrl: "http://127.0.0.1:8080",
            studentId: 7,
            word: "abandon",
            phonetic: "/əˈbændən/",
            partOfSpeech: "v.",
            lemma: "abandon",
            cetLevel: "CET-4",
            meaning: "放弃",
            explanation: "用法说明",
            exampleSentence: "They had to abandon the car.",
            mode: "demo",
            lookedUpAt: 500
        )
        try migrated.upsertDictionaryHistory(hist)
        XCTAssertEqual(try migrated.recentDictionaryHistory(serverUrl: "http://127.0.0.1:8080", studentId: 7).count, 1)

        // 4. Re-open same DB -> idempotent migration keeps version 4 and all rows.
        let reopened = try StudyDatabase(path: dbPath)
        XCTAssertEqual(reopened.userVersion(), 4)
        XCTAssertEqual(try reopened.listFavorites(serverUrl: "http://127.0.0.1:8080", studentId: 7).count, 1)
        XCTAssertEqual(try reopened.recentDictionaryHistory(serverUrl: "http://127.0.0.1:8080", studentId: 7).count, 1)
    }

    func testDictionaryFavoriteFindAndDeleteCaseInsensitiveAndScoped() throws {
        let db = try StudyDatabase(path: tempDir.appendingPathComponent("dict_fav.db").path)
        let s1 = "http://127.0.0.1:8080"
        let s2 = "http://10.0.0.2:8080"

        let readerFav = Favorite(
            serverUrl: s1,
            studentId: 1,
            articleId: DemoArticle.id,
            startOffset: 0,
            endOffset: 7,
            selection: "abandon",
            sentence: "abandon ship",
            meaning: "放弃",
            explanation: "阅读来源",
            mode: "demo",
            savedAt: 100,
            lemma: "abandon",
            cetLevel: "CET-4",
            sourceType: Favorite.sourceReader
        )
        let entry = DictionaryEntry(
            word: "abandoned",
            lemma: "abandon",
            phonetic: "/əˈbændən/",
            partOfSpeech: "v.",
            cetLevel: "CET-4",
            meaning: "放弃；抛弃",
            explanation: "用法说明",
            exampleSentence: "They had to abandon the car in the snow.",
            exampleTranslation: "他们不得不把车丢弃在雪地里。",
            mode: "demo"
        )
        let dictFav1 = Favorite.fromDictionary(serverUrl: s1, studentId: 1, entry: entry, now: 200)
        let dictFavOtherAccount = Favorite.fromDictionary(serverUrl: s1, studentId: 2, entry: entry, now: 201)
        let dictFavOtherServer = Favorite.fromDictionary(serverUrl: s2, studentId: 1, entry: entry, now: 202)

        try db.insertFavorite(readerFav)
        try db.insertFavorite(dictFav1)
        try db.insertFavorite(dictFavOtherAccount)
        try db.insertFavorite(dictFavOtherServer)

        // Case-insensitive lookup finds only __dict__ row for (s1, 1).
        let found = try db.findDictionaryFavorite(serverUrl: s1, studentId: 1, selection: "ABANDON")
        XCTAssertNotNil(found)
        XCTAssertEqual(found?.articleId, Favorite.dictArticleId)
        XCTAssertEqual(found?.lemma, "abandon")
        XCTAssertEqual(found?.cetLevel, "CET-4")
        XCTAssertEqual(found?.sourceType, Favorite.sourceDictionary)

        // Deleting dictionary favorite must not remove reader favorite or other accounts/servers.
        try db.deleteDictionaryFavorite(serverUrl: s1, studentId: 1, selection: "AbAnDoN")
        XCTAssertNil(try db.findDictionaryFavorite(serverUrl: s1, studentId: 1, selection: "abandon"))
        XCTAssertEqual(try db.listFavorites(serverUrl: s1, studentId: 1).count, 1)
        XCTAssertEqual(try db.listFavorites(serverUrl: s1, studentId: 1).first?.articleId, DemoArticle.id)
        XCTAssertNotNil(try db.findDictionaryFavorite(serverUrl: s1, studentId: 2, selection: "abandon"))
        XCTAssertNotNil(try db.findDictionaryFavorite(serverUrl: s2, studentId: 1, selection: "abandon"))
    }

    func testDictionaryHistoryCrudAndAccountIsolation() throws {
        let db = try StudyDatabase(path: tempDir.appendingPathComponent("dict_hist.db").path)
        let s1 = "http://127.0.0.1:8080"
        let s2 = "http://10.0.0.2:8080"

        func makeHistory(_ server: String, _ student: Int64, _ word: String, at ts: Int64, meaning: String = "释义") -> DictionaryHistory {
            DictionaryHistory(
                serverUrl: server,
                studentId: student,
                word: word,
                phonetic: "/p/",
                partOfSpeech: "v.",
                lemma: word,
                cetLevel: "CET-4",
                meaning: meaning,
                explanation: "exp",
                exampleSentence: "ex",
                mode: "demo",
                lookedUpAt: ts
            )
        }

        try db.upsertDictionaryHistory(makeHistory(s1, 1, "alpha", at: 100))
        try db.upsertDictionaryHistory(makeHistory(s1, 1, "beta", at: 200))
        try db.upsertDictionaryHistory(makeHistory(s1, 1, "gamma", at: 300))
        // Upsert existing word bumps timestamp and updates meaning
        try db.upsertDictionaryHistory(makeHistory(s1, 1, "alpha", at: 400, meaning: "新释义"))
        // Other account / other server
        try db.upsertDictionaryHistory(makeHistory(s1, 2, "other-student", at: 500))
        try db.upsertDictionaryHistory(makeHistory(s2, 1, "other-server", at: 600))

        let top2 = try db.recentDictionaryHistory(serverUrl: s1, studentId: 1, limit: 2)
        XCTAssertEqual(top2.map(\.word), ["alpha", "gamma"])
        XCTAssertEqual(top2[0].meaning, "新释义")

        try db.deleteDictionaryHistory(serverUrl: s1, studentId: 1, word: "gamma")
        XCTAssertEqual(try db.recentDictionaryHistory(serverUrl: s1, studentId: 1).map(\.word), ["alpha", "beta"])

        try db.clearDictionaryHistory(serverUrl: s1, studentId: 1)
        XCTAssertTrue(try db.recentDictionaryHistory(serverUrl: s1, studentId: 1).isEmpty)
        XCTAssertEqual(try db.recentDictionaryHistory(serverUrl: s1, studentId: 2).map(\.word), ["other-student"])
        XCTAssertEqual(try db.recentDictionaryHistory(serverUrl: s2, studentId: 1).map(\.word), ["other-server"])
    }

    func testTutorMessagesCrudOrderingAndIsolation() throws {
        let db = try StudyDatabase(path: tempDir.appendingPathComponent("tutor_msg.db").path)
        let s1 = "http://127.0.0.1:8080"
        let s2 = "http://10.0.0.2:8080"

        func makeMsg(_ server: String, _ student: Int64, _ key: String, _ role: TutorChatRole, _ content: String, at ts: Int64) -> TutorMessage {
            TutorMessage(
                id: 0,
                serverUrl: server,
                studentId: student,
                sessionKey: key,
                role: role,
                content: content,
                contextType: "FREE",
                contextSummary: "summary",
                mode: role == .assistant ? "demo" : "",
                createdAt: ts
            )
        }

        let id1 = try db.insertTutorMessage(makeMsg(s1, 1, "free", .user, "Q1", at: 1000))
        let id2 = try db.insertTutorMessage(makeMsg(s1, 1, "free", .assistant, "A1", at: 1000))
        let id3 = try db.insertTutorMessage(makeMsg(s1, 1, "free", .user, "Q2", at: 2000))
        XCTAssertTrue(id1 > 0 && id2 > id1 && id3 > id2)

        _ = try db.insertTutorMessage(makeMsg(s1, 1, "WORD:123", .user, "Word Q", at: 1500))
        _ = try db.insertTutorMessage(makeMsg(s1, 2, "free", .user, "Student 2", at: 1500))
        _ = try db.insertTutorMessage(makeMsg(s2, 1, "free", .user, "Server 2", at: 1500))

        let freeMsgs = try db.listTutorMessages(serverUrl: s1, studentId: 1, sessionKey: "free")
        XCTAssertEqual(freeMsgs.map(\.content), ["Q1", "A1", "Q2"])
        XCTAssertEqual(freeMsgs.map(\.role), [.user, .assistant, .user])
        XCTAssertEqual(freeMsgs[1].mode, "demo")

        try db.clearTutorSession(serverUrl: s1, studentId: 1, sessionKey: "free")
        XCTAssertTrue(try db.listTutorMessages(serverUrl: s1, studentId: 1, sessionKey: "free").isEmpty)
        XCTAssertEqual(try db.listTutorMessages(serverUrl: s1, studentId: 1, sessionKey: "WORD:123").count, 1)
        XCTAssertEqual(try db.listTutorMessages(serverUrl: s1, studentId: 2, sessionKey: "free").count, 1)
        XCTAssertEqual(try db.listTutorMessages(serverUrl: s2, studentId: 1, sessionKey: "free").count, 1)
    }
}
