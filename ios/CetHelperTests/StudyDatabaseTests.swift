import XCTest
@testable import CetHelper

final class StudyDatabaseTests: XCTestCase {
    private var tempDir: URL!
    private var db: StudyDatabase!

    override func setUpWithError() throws {
        tempDir = FileManager.default.temporaryDirectory.appendingPathComponent("cet-db-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        db = try StudyDatabase(path: tempDir.appendingPathComponent("study.db").path)
    }

    override func tearDownWithError() throws {
        db = nil
        if let tempDir {
            try? FileManager.default.removeItem(at: tempDir)
        }
    }

    func testSchemaPragmasFavoritesArticlesAndRecordsWithIsolation() throws {
        XCTAssertEqual(db.userVersion(), 4)
        XCTAssertEqual(db.journalMode(), "wal")

        let s1 = "http://127.0.0.1:8080"
        let s2 = "http://192.168.1.2:8080"

        let art1 = ImportedArticle(serverUrl: s1, studentId: 1, articleId: "a1", title: "Title One", body: "Body One", createdAt: 100)
        let art2 = ImportedArticle(serverUrl: s1, studentId: 1, articleId: "a2", title: "Title Two", body: "Body Two", createdAt: 200)
        let otherStudentArt = ImportedArticle(serverUrl: s1, studentId: 2, articleId: "a1", title: "Other Student", body: "Body", createdAt: 300)
        let otherServerArt = ImportedArticle(serverUrl: s2, studentId: 1, articleId: "a1", title: "Other Server", body: "Body", createdAt: 400)

        try db.insertArticle(art1)
        try db.insertArticle(art1) // INSERT OR IGNORE
        try db.insertArticle(art2)
        try db.insertArticle(otherStudentArt)
        try db.insertArticle(otherServerArt)

        XCTAssertEqual(try db.countArticles(serverUrl: s1, studentId: 1), 2)
        let listedArts = try db.listArticles(serverUrl: s1, studentId: 1)
        XCTAssertEqual(listedArts.map(\.articleId), ["a2", "a1"])

        try db.updateArticleProgress(serverUrl: s1, studentId: 1, articleId: "a1", lastReadAt: 5000, lastScrollY: 320)
        let found = try db.findArticle(serverUrl: s1, studentId: 1, articleId: "a1")
        XCTAssertEqual(found?.lastReadAt, 5000)
        XCTAssertEqual(found?.lastScrollY, 320)

        let fav1 = makeSampleFavorite(serverUrl: s1, studentId: 1, articleId: "a1", startOffset: 5, endOffset: 10, savedAt: 100, nextReviewAt: 0)
        let fav2 = makeSampleFavorite(serverUrl: s1, studentId: 1, articleId: "a1", startOffset: 0, endOffset: 4, savedAt: 200, nextReviewAt: 5000)
        let favOther = makeSampleFavorite(serverUrl: s2, studentId: 1, articleId: "a1", startOffset: 0, endOffset: 4, savedAt: 300, nextReviewAt: 0)

        try db.insertFavorite(fav1)
        try db.insertFavorite(fav1) // INSERT OR IGNORE
        try db.insertFavorite(fav2)
        try db.insertFavorite(favOther)

        XCTAssertEqual(try db.countAllFavorites(serverUrl: s1, studentId: 1), 2)
        XCTAssertEqual(try db.countDueFavorites(serverUrl: s1, studentId: 1, nowMs: 1000), 1)

        let listedFavs = try db.listFavorites(serverUrl: s1, studentId: 1)
        XCTAssertEqual(listedFavs.map(\.startOffset), [0, 5]) // savedAt DESC
        XCTAssertEqual(listedFavs[0].articleTitle, "Title One")

        let forArticle = try db.listFavoritesForArticle(serverUrl: s1, studentId: 1, articleId: "a1")
        XCTAssertEqual(forArticle.map(\.startOffset), [0, 5]) // startOffset ASC

        try db.recordReview(
            serverUrl: s1,
            studentId: 1,
            articleId: "a1",
            startOffset: 5,
            endOffset: 10,
            masteryLevel: 1,
            reviewedAt: 2000,
            nextReviewAt: 2000 + Srs.dayMs
        )
        let updated = try db.listFavoritesForArticle(serverUrl: s1, studentId: 1, articleId: "a1")[1]
        XCTAssertEqual(updated.masteryLevel, 1)
        XCTAssertEqual(updated.reviewCount, 1)
        XCTAssertEqual(updated.lastReviewedAt, 2000)
        XCTAssertEqual(try db.countDueFavorites(serverUrl: s1, studentId: 1, nowMs: 1000), 0)

        try db.deleteFavorite(serverUrl: s1, studentId: 1, articleId: "a1", startOffset: 0, endOffset: 4)
        XCTAssertEqual(try db.countAllFavorites(serverUrl: s1, studentId: 1), 1)

        try db.deleteArticle(serverUrl: s1, studentId: 1, articleId: "a2")
        XCTAssertEqual(try db.countArticles(serverUrl: s1, studentId: 1), 1)

        // Practice records: limit 20, DESC, isolation
        let owner1 = PracticeRecord.ownerKey(ArticleOwner(serverUrl: s1, studentId: 1))
        let owner2 = PracticeRecord.ownerKey(ArticleOwner(serverUrl: s2, studentId: 1))
        for i in 1...25 {
            _ = try db.insertPracticeRecord(
                ownerKey: owner1,
                type: .translation,
                promptOrSource: "src-\(i)",
                draft: "draft-\(i)",
                reviewJson: "{}",
                estimatedScore: i % 15,
                createdAt: Int64(i * 100)
            )
        }
        let writingId = try db.insertPracticeRecord(
            ownerKey: owner1,
            type: .writing,
            promptOrSource: "prompt",
            draft: "essay",
            reviewJson: "{}",
            estimatedScore: 12,
            createdAt: 9999
        )
        _ = try db.insertPracticeRecord(
            ownerKey: owner2,
            type: .translation,
            promptOrSource: "other",
            draft: "other",
            reviewJson: "{}",
            estimatedScore: -1,
            createdAt: 10000
        )

        let topTrans = try db.listPracticeRecords(ownerKey: owner1, type: .translation, limit: 20)
        XCTAssertEqual(topTrans.count, 20)
        XCTAssertEqual(topTrans.first?.promptOrSource, "src-25")

        let recent = try db.listRecentPracticeRecords(ownerKey: owner1, limit: 5)
        XCTAssertEqual(recent.first?.id, writingId)

        try db.deletePracticeRecord(id: writingId)
        XCTAssertEqual(try db.listPracticeRecords(ownerKey: owner1, type: .writing, limit: 20).count, 0)
    }
}
