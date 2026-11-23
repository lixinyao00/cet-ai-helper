import Foundation
import SQLite3

extension StudyDatabase {
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

}
