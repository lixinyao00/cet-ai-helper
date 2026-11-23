import Foundation
import SQLite3

extension StudyDatabase {
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

}
