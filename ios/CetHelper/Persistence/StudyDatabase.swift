import Foundation
import SQLite3

private let sqliteTransient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

enum StudyDatabaseError: Error {
    case openFailed(String)
    case executeFailed(String)
    case prepareFailed(String)
    case stepFailed(String)
}

final class StudyDatabase {
    private var db: OpaquePointer?
    private let lock = NSRecursiveLock()
    let path: String

    init(path: String) throws {
        self.path = path
        if path != ":memory:" {
            let parent = (path as NSString).deletingLastPathComponent
            if !parent.isEmpty {
                try FileManager.default.createDirectory(
                    atPath: parent,
                    withIntermediateDirectories: true,
                    attributes: nil
                )
            }
        }

        var handle: OpaquePointer?
        let flags = SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX
        let code = sqlite3_open_v2(path, &handle, flags, nil)
        guard code == SQLITE_OK, let opened = handle else {
            let msg = handle.flatMap { String(cString: sqlite3_errmsg($0)) } ?? "sqlite3_open_v2 failed (\(code))"
            if let handle { sqlite3_close(handle) }
            throw StudyDatabaseError.openFailed(msg)
        }
        self.db = opened
        try configureAndMigrate()
    }

    deinit {
        if let db {
            sqlite3_close(db)
        }
    }

    private func configureAndMigrate() throws {
        try execute("PRAGMA journal_mode = WAL;")
        try execute("PRAGMA foreign_keys = OFF;")
        try execute("""
        CREATE TABLE IF NOT EXISTS favorites (
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
            lemma TEXT NOT NULL DEFAULT '',
            cet_level TEXT NOT NULL DEFAULT '',
            source_type TEXT NOT NULL DEFAULT 'reader',
            PRIMARY KEY (serverUrl, studentId, articleId, startOffset, endOffset)
        );
        """)

        // Idempotent v3 -> v4 column migration for existing favorites tables.
        let favoriteColumns = try tableColumns("favorites")
        if !favoriteColumns.contains("lemma") {
            try execute("ALTER TABLE favorites ADD COLUMN lemma TEXT NOT NULL DEFAULT '';")
        }
        if !favoriteColumns.contains("cet_level") {
            try execute("ALTER TABLE favorites ADD COLUMN cet_level TEXT NOT NULL DEFAULT '';")
        }
        if !favoriteColumns.contains("source_type") {
            try execute("ALTER TABLE favorites ADD COLUMN source_type TEXT NOT NULL DEFAULT 'reader';")
        }

        try execute("""
        CREATE TABLE IF NOT EXISTS articles (
            serverUrl TEXT NOT NULL,
            studentId INTEGER NOT NULL,
            articleId TEXT NOT NULL,
            title TEXT NOT NULL,
            body TEXT NOT NULL,
            createdAt INTEGER NOT NULL,
            last_read_at INTEGER NOT NULL DEFAULT 0,
            last_scroll_y INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY (serverUrl, studentId, articleId)
        );
        """)
        try execute("""
        CREATE TABLE IF NOT EXISTS practice_records (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            student_id TEXT NOT NULL,
            type TEXT NOT NULL,
            prompt_or_source TEXT NOT NULL,
            draft TEXT NOT NULL,
            review_json TEXT NOT NULL,
            estimated_score INTEGER NOT NULL DEFAULT -1,
            created_at INTEGER NOT NULL
        );
        """)
        try execute("""
        CREATE INDEX IF NOT EXISTS index_practice_records_student_id_created_at
            ON practice_records (student_id, created_at);
        """)
        try execute("""
        CREATE TABLE IF NOT EXISTS dictionary_history (
            server_url TEXT NOT NULL,
            student_id INTEGER NOT NULL,
            word TEXT NOT NULL,
            phonetic TEXT NOT NULL DEFAULT '',
            part_of_speech TEXT NOT NULL DEFAULT '',
            lemma TEXT NOT NULL DEFAULT '',
            cet_level TEXT NOT NULL DEFAULT '',
            meaning TEXT NOT NULL,
            explanation TEXT NOT NULL,
            example_sentence TEXT NOT NULL DEFAULT '',
            mode TEXT NOT NULL DEFAULT 'demo',
            looked_up_at INTEGER NOT NULL,
            PRIMARY KEY (server_url, student_id, word)
        );
        """)
        try execute("""
        CREATE INDEX IF NOT EXISTS index_dictionary_history_server_url_student_id_looked_up_at
            ON dictionary_history (server_url, student_id, looked_up_at);
        """)
        try execute("""
        CREATE TABLE IF NOT EXISTS tutor_messages (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            server_url TEXT NOT NULL,
            student_id INTEGER NOT NULL,
            session_key TEXT NOT NULL,
            role TEXT NOT NULL,
            content TEXT NOT NULL,
            context_type TEXT NOT NULL DEFAULT '',
            context_summary TEXT NOT NULL DEFAULT '',
            mode TEXT NOT NULL DEFAULT '',
            created_at INTEGER NOT NULL
        );
        """)
        try execute("""
        CREATE INDEX IF NOT EXISTS index_tutor_messages_server_url_student_id_session_key_created_at
            ON tutor_messages (server_url, student_id, session_key, created_at);
        """)
        try execute("PRAGMA user_version = 4;")
    }

    private func tableColumns(_ table: String) throws -> Set<String> {
        let stmt = try prepare("PRAGMA table_info(\(table));")
        defer { sqlite3_finalize(stmt) }
        var cols: Set<String> = []
        while true {
            let step = sqlite3_step(stmt)
            if step == SQLITE_ROW {
                cols.insert(columnText(stmt, 1))
            } else if step == SQLITE_DONE {
                break
            } else {
                throw StudyDatabaseError.stepFailed(lastErrorMessage())
            }
        }
        return cols
    }

    // MARK: - PRAGMA Helpers

    func userVersion() -> Int {
        lock.lock()
        defer { lock.unlock() }
        return (try? queryInt("PRAGMA user_version;")) ?? 0
    }

    func journalMode() -> String {
        lock.lock()
        defer { lock.unlock() }
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, "PRAGMA journal_mode;", -1, &stmt, nil) == SQLITE_OK else {
            return ""
        }
        defer { sqlite3_finalize(stmt) }
        if sqlite3_step(stmt) == SQLITE_ROW, let ptr = sqlite3_column_text(stmt, 0) {
            return String(cString: ptr).lowercased()
        }
        return ""
    }

    // MARK: - Favorites DAO (§2.1 表 1)

    func insertFavorite(_ favorite: Favorite) throws {
        lock.lock()
        defer { lock.unlock() }
        let sql = """
        INSERT OR IGNORE INTO favorites (
            serverUrl, studentId, articleId, startOffset, endOffset,
            selection, sentence, meaning, explanation, mode, savedAt,
            phonetic, part_of_speech, mastery_level, review_count, last_reviewed_at, next_review_at,
            lemma, cet_level, source_type
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
        """
        let stmt = try prepare(sql)
        defer { sqlite3_finalize(stmt) }
        bindText(stmt, 1, favorite.serverUrl)
        sqlite3_bind_int64(stmt, 2, favorite.studentId)
        bindText(stmt, 3, favorite.articleId)
        sqlite3_bind_int(stmt, 4, Int32(favorite.startOffset))
        sqlite3_bind_int(stmt, 5, Int32(favorite.endOffset))
        bindText(stmt, 6, favorite.selection)
        bindText(stmt, 7, favorite.sentence)
        bindText(stmt, 8, favorite.meaning)
        bindText(stmt, 9, favorite.explanation)
        bindText(stmt, 10, favorite.mode)
        sqlite3_bind_int64(stmt, 11, favorite.savedAt)
        bindText(stmt, 12, favorite.phonetic)
        bindText(stmt, 13, favorite.partOfSpeech)
        sqlite3_bind_int(stmt, 14, Int32(favorite.masteryLevel))
        sqlite3_bind_int(stmt, 15, Int32(favorite.reviewCount))
        sqlite3_bind_int64(stmt, 16, favorite.lastReviewedAt)
        sqlite3_bind_int64(stmt, 17, favorite.nextReviewAt)
        bindText(stmt, 18, favorite.lemma)
        bindText(stmt, 19, favorite.cetLevel)
        bindText(stmt, 20, favorite.sourceType)
        try stepDone(stmt)
    }

    func listFavorites(serverUrl: String, studentId: Int64) throws -> [Favorite] {
        lock.lock()
        defer { lock.unlock() }
        let sql = """
        SELECT favorites.serverUrl, favorites.studentId, favorites.articleId,
               favorites.startOffset, favorites.endOffset, favorites.selection,
               favorites.sentence, favorites.meaning, favorites.explanation,
               favorites.mode, favorites.savedAt, favorites.phonetic,
               favorites.part_of_speech, favorites.mastery_level, favorites.review_count,
               favorites.last_reviewed_at, favorites.next_review_at,
               favorites.lemma, favorites.cet_level, favorites.source_type,
               articles.title AS articleTitle
        FROM favorites
        LEFT JOIN articles
          ON favorites.serverUrl = articles.serverUrl
         AND favorites.studentId = articles.studentId
         AND favorites.articleId = articles.articleId
        WHERE favorites.serverUrl = ? AND favorites.studentId = ?
        ORDER BY favorites.savedAt DESC, favorites.articleId, favorites.startOffset, favorites.endOffset;
        """
        let stmt = try prepare(sql)
        defer { sqlite3_finalize(stmt) }
        bindText(stmt, 1, serverUrl)
        sqlite3_bind_int64(stmt, 2, studentId)

        var rows: [Favorite] = []
        while true {
            let step = sqlite3_step(stmt)
            if step == SQLITE_ROW {
                rows.append(readFavorite(stmt, includesTitle: true))
            } else if step == SQLITE_DONE {
                break
            } else {
                throw StudyDatabaseError.stepFailed(lastErrorMessage())
            }
        }
        return rows
    }

    func listFavoritesForArticle(serverUrl: String, studentId: Int64, articleId: String) throws -> [Favorite] {
        lock.lock()
        defer { lock.unlock() }
        let sql = """
        SELECT serverUrl, studentId, articleId, startOffset, endOffset,
               selection, sentence, meaning, explanation, mode, savedAt,
               phonetic, part_of_speech, mastery_level, review_count,
               last_reviewed_at, next_review_at, lemma, cet_level, source_type
        FROM favorites
        WHERE serverUrl = ? AND studentId = ? AND articleId = ?
        ORDER BY startOffset ASC;
        """
        let stmt = try prepare(sql)
        defer { sqlite3_finalize(stmt) }
        bindText(stmt, 1, serverUrl)
        sqlite3_bind_int64(stmt, 2, studentId)
        bindText(stmt, 3, articleId)

        var rows: [Favorite] = []
        while true {
            let step = sqlite3_step(stmt)
            if step == SQLITE_ROW {
                rows.append(readFavorite(stmt, includesTitle: false))
            } else if step == SQLITE_DONE {
                break
            } else {
                throw StudyDatabaseError.stepFailed(lastErrorMessage())
            }
        }
        return rows
    }

    func findDictionaryFavorite(serverUrl: String, studentId: Int64, selection: String) throws -> Favorite? {
        lock.lock()
        defer { lock.unlock() }
        let sql = """
        SELECT serverUrl, studentId, articleId, startOffset, endOffset,
               selection, sentence, meaning, explanation, mode, savedAt,
               phonetic, part_of_speech, mastery_level, review_count,
               last_reviewed_at, next_review_at, lemma, cet_level, source_type
        FROM favorites
        WHERE serverUrl = ? AND studentId = ? AND articleId = '\(Favorite.dictArticleId)' AND LOWER(selection) = LOWER(?)
        LIMIT 1;
        """
        let stmt = try prepare(sql)
        defer { sqlite3_finalize(stmt) }
        bindText(stmt, 1, serverUrl)
        sqlite3_bind_int64(stmt, 2, studentId)
        bindText(stmt, 3, selection)

        let step = sqlite3_step(stmt)
        if step == SQLITE_ROW {
            return readFavorite(stmt, includesTitle: false)
        } else if step == SQLITE_DONE {
            return nil
        } else {
            throw StudyDatabaseError.stepFailed(lastErrorMessage())
        }
    }

    func deleteDictionaryFavorite(serverUrl: String, studentId: Int64, selection: String) throws {
        lock.lock()
        defer { lock.unlock() }
        let sql = """
        DELETE FROM favorites
        WHERE serverUrl = ? AND studentId = ? AND articleId = '\(Favorite.dictArticleId)' AND LOWER(selection) = LOWER(?);
        """
        let stmt = try prepare(sql)
        defer { sqlite3_finalize(stmt) }
        bindText(stmt, 1, serverUrl)
        sqlite3_bind_int64(stmt, 2, studentId)
        bindText(stmt, 3, selection)
        try stepDone(stmt)
    }

    func deleteFavorite(
        serverUrl: String,
        studentId: Int64,
        articleId: String,
        startOffset: Int,
        endOffset: Int
    ) throws {
        lock.lock()
        defer { lock.unlock() }
        let sql = """
        DELETE FROM favorites
        WHERE serverUrl = ? AND studentId = ? AND articleId = ? AND startOffset = ? AND endOffset = ?;
        """
        let stmt = try prepare(sql)
        defer { sqlite3_finalize(stmt) }
        bindText(stmt, 1, serverUrl)
        sqlite3_bind_int64(stmt, 2, studentId)
        bindText(stmt, 3, articleId)
        sqlite3_bind_int(stmt, 4, Int32(startOffset))
        sqlite3_bind_int(stmt, 5, Int32(endOffset))
        try stepDone(stmt)
    }

    func recordReview(
        serverUrl: String,
        studentId: Int64,
        articleId: String,
        startOffset: Int,
        endOffset: Int,
        masteryLevel: Int,
        reviewedAt: Int64,
        nextReviewAt: Int64
    ) throws {
        lock.lock()
        defer { lock.unlock() }
        let sql = """
        UPDATE favorites
        SET mastery_level = ?,
            review_count = review_count + 1,
            last_reviewed_at = ?,
            next_review_at = ?
        WHERE serverUrl = ? AND studentId = ? AND articleId = ? AND startOffset = ? AND endOffset = ?;
        """
        let stmt = try prepare(sql)
        defer { sqlite3_finalize(stmt) }
        sqlite3_bind_int(stmt, 1, Int32(masteryLevel))
        sqlite3_bind_int64(stmt, 2, reviewedAt)
        sqlite3_bind_int64(stmt, 3, nextReviewAt)
        bindText(stmt, 4, serverUrl)
        sqlite3_bind_int64(stmt, 5, studentId)
        bindText(stmt, 6, articleId)
        sqlite3_bind_int(stmt, 7, Int32(startOffset))
        sqlite3_bind_int(stmt, 8, Int32(endOffset))
        try stepDone(stmt)
    }

    func countAllFavorites(serverUrl: String, studentId: Int64) throws -> Int {
        lock.lock()
        defer { lock.unlock() }
        let sql = "SELECT COUNT(*) FROM favorites WHERE serverUrl = ? AND studentId = ?;"
        let stmt = try prepare(sql)
        defer { sqlite3_finalize(stmt) }
        bindText(stmt, 1, serverUrl)
        sqlite3_bind_int64(stmt, 2, studentId)
        return try stepInt(stmt)
    }

    func countDueFavorites(serverUrl: String, studentId: Int64, nowMs: Int64) throws -> Int {
        lock.lock()
        defer { lock.unlock() }
        let sql = "SELECT COUNT(*) FROM favorites WHERE serverUrl = ? AND studentId = ? AND next_review_at <= ?;"
        let stmt = try prepare(sql)
        defer { sqlite3_finalize(stmt) }
        bindText(stmt, 1, serverUrl)
        sqlite3_bind_int64(stmt, 2, studentId)
        sqlite3_bind_int64(stmt, 3, nowMs)
        return try stepInt(stmt)
    }

    // MARK: - Articles DAO (§2.1 表 2)

    func insertArticle(_ article: ImportedArticle) throws {
        lock.lock()
        defer { lock.unlock() }
        let sql = """
        INSERT OR IGNORE INTO articles (
            serverUrl, studentId, articleId, title, body, createdAt, last_read_at, last_scroll_y
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?);
        """
        let stmt = try prepare(sql)
        defer { sqlite3_finalize(stmt) }
        bindText(stmt, 1, article.serverUrl)
        sqlite3_bind_int64(stmt, 2, article.studentId)
        bindText(stmt, 3, article.articleId)
        bindText(stmt, 4, article.title)
        bindText(stmt, 5, article.body)
        sqlite3_bind_int64(stmt, 6, article.createdAt)
        sqlite3_bind_int64(stmt, 7, article.lastReadAt)
        sqlite3_bind_int(stmt, 8, Int32(article.lastScrollY))
        try stepDone(stmt)
    }

    func listArticles(serverUrl: String, studentId: Int64) throws -> [ArticleSummary] {
        lock.lock()
        defer { lock.unlock() }
        let sql = """
        SELECT articleId, title, createdAt, last_read_at, last_scroll_y
        FROM articles
        WHERE serverUrl = ? AND studentId = ?
        ORDER BY createdAt DESC, articleId;
        """
        let stmt = try prepare(sql)
        defer { sqlite3_finalize(stmt) }
        bindText(stmt, 1, serverUrl)
        sqlite3_bind_int64(stmt, 2, studentId)

        var rows: [ArticleSummary] = []
        while true {
            let step = sqlite3_step(stmt)
            if step == SQLITE_ROW {
                rows.append(
                    ArticleSummary(
                        articleId: columnText(stmt, 0),
                        title: columnText(stmt, 1),
                        createdAt: sqlite3_column_int64(stmt, 2),
                        lastReadAt: sqlite3_column_int64(stmt, 3),
                        lastScrollY: Int(sqlite3_column_int(stmt, 4))
                    )
                )
            } else if step == SQLITE_DONE {
                break
            } else {
                throw StudyDatabaseError.stepFailed(lastErrorMessage())
            }
        }
        return rows
    }

    func findArticle(serverUrl: String, studentId: Int64, articleId: String) throws -> ImportedArticle? {
        lock.lock()
        defer { lock.unlock() }
        let sql = """
        SELECT serverUrl, studentId, articleId, title, body, createdAt, last_read_at, last_scroll_y
        FROM articles
        WHERE serverUrl = ? AND studentId = ? AND articleId = ?;
        """
        let stmt = try prepare(sql)
        defer { sqlite3_finalize(stmt) }
        bindText(stmt, 1, serverUrl)
        sqlite3_bind_int64(stmt, 2, studentId)
        bindText(stmt, 3, articleId)

        let step = sqlite3_step(stmt)
        if step == SQLITE_ROW {
            return ImportedArticle(
                serverUrl: columnText(stmt, 0),
                studentId: sqlite3_column_int64(stmt, 1),
                articleId: columnText(stmt, 2),
                title: columnText(stmt, 3),
                body: columnText(stmt, 4),
                createdAt: sqlite3_column_int64(stmt, 5),
                lastReadAt: sqlite3_column_int64(stmt, 6),
                lastScrollY: Int(sqlite3_column_int(stmt, 7))
            )
        } else if step == SQLITE_DONE {
            return nil
        } else {
            throw StudyDatabaseError.stepFailed(lastErrorMessage())
        }
    }

    func deleteArticle(serverUrl: String, studentId: Int64, articleId: String) throws {
        lock.lock()
        defer { lock.unlock() }
        let sql = "DELETE FROM articles WHERE serverUrl = ? AND studentId = ? AND articleId = ?;"
        let stmt = try prepare(sql)
        defer { sqlite3_finalize(stmt) }
        bindText(stmt, 1, serverUrl)
        sqlite3_bind_int64(stmt, 2, studentId)
        bindText(stmt, 3, articleId)
        try stepDone(stmt)
    }

    func updateArticleProgress(
        serverUrl: String,
        studentId: Int64,
        articleId: String,
        lastReadAt: Int64,
        lastScrollY: Int
    ) throws {
        lock.lock()
        defer { lock.unlock() }
        let sql = """
        UPDATE articles
        SET last_read_at = ?, last_scroll_y = ?
        WHERE serverUrl = ? AND studentId = ? AND articleId = ?;
        """
        let stmt = try prepare(sql)
        defer { sqlite3_finalize(stmt) }
        sqlite3_bind_int64(stmt, 1, lastReadAt)
        sqlite3_bind_int(stmt, 2, Int32(lastScrollY))
        bindText(stmt, 3, serverUrl)
        sqlite3_bind_int64(stmt, 4, studentId)
        bindText(stmt, 5, articleId)
        try stepDone(stmt)
    }

    func countArticles(serverUrl: String, studentId: Int64) throws -> Int {
        lock.lock()
        defer { lock.unlock() }
        let sql = "SELECT COUNT(*) FROM articles WHERE serverUrl = ? AND studentId = ?;"
        let stmt = try prepare(sql)
        defer { sqlite3_finalize(stmt) }
        bindText(stmt, 1, serverUrl)
        sqlite3_bind_int64(stmt, 2, studentId)
        return try stepInt(stmt)
    }

    // MARK: - Practice Records DAO (§2.1 表 3)

    @discardableResult
    func insertPracticeRecord(
        ownerKey: String,
        type: PracticeRecordType,
        promptOrSource: String,
        draft: String,
        reviewJson: String,
        estimatedScore: Int,
        createdAt: Int64
    ) throws -> Int64 {
        lock.lock()
        defer { lock.unlock() }
        let sql = """
        INSERT INTO practice_records (
            student_id, type, prompt_or_source, draft, review_json, estimated_score, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?);
        """
        let stmt = try prepare(sql)
        defer { sqlite3_finalize(stmt) }
        bindText(stmt, 1, ownerKey)
        bindText(stmt, 2, type.rawValue)
        bindText(stmt, 3, promptOrSource)
        bindText(stmt, 4, draft)
        bindText(stmt, 5, reviewJson)
        sqlite3_bind_int(stmt, 6, Int32(estimatedScore))
        sqlite3_bind_int64(stmt, 7, createdAt)
        try stepDone(stmt)
        return sqlite3_last_insert_rowid(db)
    }

    func listPracticeRecords(
        ownerKey: String,
        type: PracticeRecordType,
        limit: Int = 20
    ) throws -> [PracticeRecord] {
        lock.lock()
        defer { lock.unlock() }
        let sql = """
        SELECT id, student_id, type, prompt_or_source, draft, review_json, estimated_score, created_at
        FROM practice_records
        WHERE student_id = ? AND type = ?
        ORDER BY created_at DESC, id DESC
        LIMIT ?;
        """
        let stmt = try prepare(sql)
        defer { sqlite3_finalize(stmt) }
        bindText(stmt, 1, ownerKey)
        bindText(stmt, 2, type.rawValue)
        sqlite3_bind_int(stmt, 3, Int32(limit))
        return try readPracticeRecords(stmt)
    }

    func listRecentPracticeRecords(ownerKey: String, limit: Int = 20) throws -> [PracticeRecord] {
        lock.lock()
        defer { lock.unlock() }
        let sql = """
        SELECT id, student_id, type, prompt_or_source, draft, review_json, estimated_score, created_at
        FROM practice_records
        WHERE student_id = ?
        ORDER BY created_at DESC, id DESC
        LIMIT ?;
        """
        let stmt = try prepare(sql)
        defer { sqlite3_finalize(stmt) }
        bindText(stmt, 1, ownerKey)
        sqlite3_bind_int(stmt, 2, Int32(limit))
        return try readPracticeRecords(stmt)
    }

    func deletePracticeRecord(id: Int64) throws {
        lock.lock()
        defer { lock.unlock() }
        let sql = "DELETE FROM practice_records WHERE id = ?;"
        let stmt = try prepare(sql)
        defer { sqlite3_finalize(stmt) }
        sqlite3_bind_int64(stmt, 1, id)
        try stepDone(stmt)
    }

    // MARK: - Dictionary History DAO (v4)

    func upsertDictionaryHistory(_ history: DictionaryHistory) throws {
        lock.lock()
        defer { lock.unlock() }
        let sql = """
        INSERT OR REPLACE INTO dictionary_history (
            server_url, student_id, word, phonetic, part_of_speech,
            lemma, cet_level, meaning, explanation, example_sentence, mode, looked_up_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
        """
        let stmt = try prepare(sql)
        defer { sqlite3_finalize(stmt) }
        bindText(stmt, 1, history.serverUrl)
        sqlite3_bind_int64(stmt, 2, history.studentId)
        bindText(stmt, 3, history.word)
        bindText(stmt, 4, history.phonetic)
        bindText(stmt, 5, history.partOfSpeech)
        bindText(stmt, 6, history.lemma)
        bindText(stmt, 7, history.cetLevel)
        bindText(stmt, 8, history.meaning)
        bindText(stmt, 9, history.explanation)
        bindText(stmt, 10, history.exampleSentence)
        bindText(stmt, 11, history.mode)
        sqlite3_bind_int64(stmt, 12, history.lookedUpAt)
        try stepDone(stmt)
    }

    func recentDictionaryHistory(serverUrl: String, studentId: Int64, limit: Int = 20) throws -> [DictionaryHistory] {
        lock.lock()
        defer { lock.unlock() }
        let sql = """
        SELECT server_url, student_id, word, phonetic, part_of_speech,
               lemma, cet_level, meaning, explanation, example_sentence, mode, looked_up_at
        FROM dictionary_history
        WHERE server_url = ? AND student_id = ?
        ORDER BY looked_up_at DESC
        LIMIT ?;
        """
        let stmt = try prepare(sql)
        defer { sqlite3_finalize(stmt) }
        bindText(stmt, 1, serverUrl)
        sqlite3_bind_int64(stmt, 2, studentId)
        sqlite3_bind_int(stmt, 3, Int32(limit))

        var rows: [DictionaryHistory] = []
        while true {
            let step = sqlite3_step(stmt)
            if step == SQLITE_ROW {
                rows.append(
                    DictionaryHistory(
                        serverUrl: columnText(stmt, 0),
                        studentId: sqlite3_column_int64(stmt, 1),
                        word: columnText(stmt, 2),
                        phonetic: columnText(stmt, 3),
                        partOfSpeech: columnText(stmt, 4),
                        lemma: columnText(stmt, 5),
                        cetLevel: columnText(stmt, 6),
                        meaning: columnText(stmt, 7),
                        explanation: columnText(stmt, 8),
                        exampleSentence: columnText(stmt, 9),
                        mode: columnText(stmt, 10),
                        lookedUpAt: sqlite3_column_int64(stmt, 11)
                    )
                )
            } else if step == SQLITE_DONE {
                break
            } else {
                throw StudyDatabaseError.stepFailed(lastErrorMessage())
            }
        }
        return rows
    }

    func deleteDictionaryHistory(serverUrl: String, studentId: Int64, word: String) throws {
        lock.lock()
        defer { lock.unlock() }
        let sql = "DELETE FROM dictionary_history WHERE server_url = ? AND student_id = ? AND word = ?;"
        let stmt = try prepare(sql)
        defer { sqlite3_finalize(stmt) }
        bindText(stmt, 1, serverUrl)
        sqlite3_bind_int64(stmt, 2, studentId)
        bindText(stmt, 3, word)
        try stepDone(stmt)
    }

    func clearDictionaryHistory(serverUrl: String, studentId: Int64) throws {
        lock.lock()
        defer { lock.unlock() }
        let sql = "DELETE FROM dictionary_history WHERE server_url = ? AND student_id = ?;"
        let stmt = try prepare(sql)
        defer { sqlite3_finalize(stmt) }
        bindText(stmt, 1, serverUrl)
        sqlite3_bind_int64(stmt, 2, studentId)
        try stepDone(stmt)
    }

    // MARK: - Tutor Messages DAO (v4)

    @discardableResult
    func insertTutorMessage(_ message: TutorMessage) throws -> Int64 {
        lock.lock()
        defer { lock.unlock() }
        let sql = """
        INSERT INTO tutor_messages (
            server_url, student_id, session_key, role, content,
            context_type, context_summary, mode, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?);
        """
        let stmt = try prepare(sql)
        defer { sqlite3_finalize(stmt) }
        bindText(stmt, 1, message.serverUrl)
        sqlite3_bind_int64(stmt, 2, message.studentId)
        bindText(stmt, 3, message.sessionKey)
        bindText(stmt, 4, message.role.rawValue)
        bindText(stmt, 5, message.content)
        bindText(stmt, 6, message.contextType)
        bindText(stmt, 7, message.contextSummary)
        bindText(stmt, 8, message.mode)
        sqlite3_bind_int64(stmt, 9, message.createdAt)
        try stepDone(stmt)
        return sqlite3_last_insert_rowid(db)
    }

    func listTutorMessages(serverUrl: String, studentId: Int64, sessionKey: String) throws -> [TutorMessage] {
        lock.lock()
        defer { lock.unlock() }
        let sql = """
        SELECT id, server_url, student_id, session_key, role, content,
               context_type, context_summary, mode, created_at
        FROM tutor_messages
        WHERE server_url = ? AND student_id = ? AND session_key = ?
        ORDER BY created_at ASC, id ASC;
        """
        let stmt = try prepare(sql)
        defer { sqlite3_finalize(stmt) }
        bindText(stmt, 1, serverUrl)
        sqlite3_bind_int64(stmt, 2, studentId)
        bindText(stmt, 3, sessionKey)

        var rows: [TutorMessage] = []
        while true {
            let step = sqlite3_step(stmt)
            if step == SQLITE_ROW {
                let rawRole = columnText(stmt, 4)
                let role = TutorChatRole(rawValue: rawRole) ?? .assistant
                rows.append(
                    TutorMessage(
                        id: sqlite3_column_int64(stmt, 0),
                        serverUrl: columnText(stmt, 1),
                        studentId: sqlite3_column_int64(stmt, 2),
                        sessionKey: columnText(stmt, 3),
                        role: role,
                        content: columnText(stmt, 5),
                        contextType: columnText(stmt, 6),
                        contextSummary: columnText(stmt, 7),
                        mode: columnText(stmt, 8),
                        createdAt: sqlite3_column_int64(stmt, 9)
                    )
                )
            } else if step == SQLITE_DONE {
                break
            } else {
                throw StudyDatabaseError.stepFailed(lastErrorMessage())
            }
        }
        return rows
    }

    func clearTutorSession(serverUrl: String, studentId: Int64, sessionKey: String) throws {
        lock.lock()
        defer { lock.unlock() }
        let sql = "DELETE FROM tutor_messages WHERE server_url = ? AND student_id = ? AND session_key = ?;"
        let stmt = try prepare(sql)
        defer { sqlite3_finalize(stmt) }
        bindText(stmt, 1, serverUrl)
        sqlite3_bind_int64(stmt, 2, studentId)
        bindText(stmt, 3, sessionKey)
        try stepDone(stmt)
    }

    // MARK: - Low-level SQLite Helpers

    private func execute(_ sql: String) throws {
        var errorPtr: UnsafeMutablePointer<CChar>?
        let code = sqlite3_exec(db, sql, nil, nil, &errorPtr)
        if code != SQLITE_OK {
            let msg = errorPtr.map { String(cString: $0) } ?? lastErrorMessage()
            sqlite3_free(errorPtr)
            throw StudyDatabaseError.executeFailed(msg)
        }
    }

    private func prepare(_ sql: String) throws -> OpaquePointer {
        var stmt: OpaquePointer?
        let code = sqlite3_prepare_v2(db, sql, -1, &stmt, nil)
        guard code == SQLITE_OK, let prepared = stmt else {
            throw StudyDatabaseError.prepareFailed(lastErrorMessage())
        }
        return prepared
    }

    private func stepDone(_ stmt: OpaquePointer) throws {
        let code = sqlite3_step(stmt)
        if code != SQLITE_DONE {
            throw StudyDatabaseError.stepFailed(lastErrorMessage())
        }
    }

    private func stepInt(_ stmt: OpaquePointer) throws -> Int {
        let code = sqlite3_step(stmt)
        if code == SQLITE_ROW {
            return Int(sqlite3_column_int(stmt, 0))
        }
        throw StudyDatabaseError.stepFailed(lastErrorMessage())
    }

    private func queryInt(_ sql: String) throws -> Int {
        let stmt = try prepare(sql)
        defer { sqlite3_finalize(stmt) }
        return try stepInt(stmt)
    }

    private func bindText(_ stmt: OpaquePointer, _ index: Int32, _ value: String) {
        sqlite3_bind_text(stmt, index, value, -1, sqliteTransient)
    }

    private func columnText(_ stmt: OpaquePointer, _ index: Int32) -> String {
        guard let ptr = sqlite3_column_text(stmt, index) else { return "" }
        return String(cString: ptr)
    }

    private func columnOptionalText(_ stmt: OpaquePointer, _ index: Int32) -> String? {
        if sqlite3_column_type(stmt, index) == SQLITE_NULL {
            return nil
        }
        return columnText(stmt, index)
    }

    private func readFavorite(_ stmt: OpaquePointer, includesTitle: Bool) -> Favorite {
        Favorite(
            serverUrl: columnText(stmt, 0),
            studentId: sqlite3_column_int64(stmt, 1),
            articleId: columnText(stmt, 2),
            startOffset: Int(sqlite3_column_int(stmt, 3)),
            endOffset: Int(sqlite3_column_int(stmt, 4)),
            selection: columnText(stmt, 5),
            sentence: columnText(stmt, 6),
            meaning: columnText(stmt, 7),
            explanation: columnText(stmt, 8),
            mode: columnText(stmt, 9),
            savedAt: sqlite3_column_int64(stmt, 10),
            phonetic: columnText(stmt, 11),
            partOfSpeech: columnText(stmt, 12),
            masteryLevel: Int(sqlite3_column_int(stmt, 13)),
            reviewCount: Int(sqlite3_column_int(stmt, 14)),
            lastReviewedAt: sqlite3_column_int64(stmt, 15),
            nextReviewAt: sqlite3_column_int64(stmt, 16),
            articleTitle: includesTitle ? columnOptionalText(stmt, 20) : nil,
            lemma: columnText(stmt, 17),
            cetLevel: columnText(stmt, 18),
            sourceType: columnText(stmt, 19)
        )
    }

    private func readPracticeRecords(_ stmt: OpaquePointer) throws -> [PracticeRecord] {
        var rows: [PracticeRecord] = []
        while true {
            let step = sqlite3_step(stmt)
            if step == SQLITE_ROW {
                let rawType = columnText(stmt, 2)
                if let type = PracticeRecordType(rawValue: rawType) {
                    rows.append(
                        PracticeRecord(
                            id: sqlite3_column_int64(stmt, 0),
                            ownerKey: columnText(stmt, 1),
                            type: type,
                            promptOrSource: columnText(stmt, 3),
                            draft: columnText(stmt, 4),
                            reviewJson: columnText(stmt, 5),
                            estimatedScore: Int(sqlite3_column_int(stmt, 6)),
                            createdAt: sqlite3_column_int64(stmt, 7)
                        )
                    )
                }
            } else if step == SQLITE_DONE {
                break
            } else {
                throw StudyDatabaseError.stepFailed(lastErrorMessage())
            }
        }
        return rows
    }

    private func lastErrorMessage() -> String {
        guard let db else { return "Database closed" }
        return String(cString: sqlite3_errmsg(db))
    }
}
