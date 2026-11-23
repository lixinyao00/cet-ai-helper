import Foundation
import SQLite3

let sqliteTransient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

enum StudyDatabaseError: Error {
    case openFailed(String)
    case executeFailed(String)
    case prepareFailed(String)
    case stepFailed(String)
}

final class StudyDatabase {
    var db: OpaquePointer?
    let lock = NSRecursiveLock()
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

    // MARK: - Low-level SQLite Helpers

    func execute(_ sql: String) throws {
        var errorPtr: UnsafeMutablePointer<CChar>?
        let code = sqlite3_exec(db, sql, nil, nil, &errorPtr)
        if code != SQLITE_OK {
            let msg = errorPtr.map { String(cString: $0) } ?? lastErrorMessage()
            sqlite3_free(errorPtr)
            throw StudyDatabaseError.executeFailed(msg)
        }
    }

    func prepare(_ sql: String) throws -> OpaquePointer {
        var stmt: OpaquePointer?
        let code = sqlite3_prepare_v2(db, sql, -1, &stmt, nil)
        guard code == SQLITE_OK, let prepared = stmt else {
            throw StudyDatabaseError.prepareFailed(lastErrorMessage())
        }
        return prepared
    }

    func stepDone(_ stmt: OpaquePointer) throws {
        let code = sqlite3_step(stmt)
        if code != SQLITE_DONE {
            throw StudyDatabaseError.stepFailed(lastErrorMessage())
        }
    }

    func stepInt(_ stmt: OpaquePointer) throws -> Int {
        let code = sqlite3_step(stmt)
        if code == SQLITE_ROW {
            return Int(sqlite3_column_int(stmt, 0))
        }
        throw StudyDatabaseError.stepFailed(lastErrorMessage())
    }

    func queryInt(_ sql: String) throws -> Int {
        let stmt = try prepare(sql)
        defer { sqlite3_finalize(stmt) }
        return try stepInt(stmt)
    }

    func bindText(_ stmt: OpaquePointer, _ index: Int32, _ value: String) {
        sqlite3_bind_text(stmt, index, value, -1, sqliteTransient)
    }

    func columnText(_ stmt: OpaquePointer, _ index: Int32) -> String {
        guard let ptr = sqlite3_column_text(stmt, index) else { return "" }
        return String(cString: ptr)
    }

    func columnOptionalText(_ stmt: OpaquePointer, _ index: Int32) -> String? {
        if sqlite3_column_type(stmt, index) == SQLITE_NULL {
            return nil
        }
        return columnText(stmt, index)
    }

    func readFavorite(_ stmt: OpaquePointer, includesTitle: Bool) -> Favorite {
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

    func readPracticeRecords(_ stmt: OpaquePointer) throws -> [PracticeRecord] {
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

    func lastErrorMessage() -> String {
        guard let db else { return "Database closed" }
        return String(cString: sqlite3_errmsg(db))
    }
}
