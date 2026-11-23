import Foundation
import SQLite3

extension StudyDatabase {
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

}
