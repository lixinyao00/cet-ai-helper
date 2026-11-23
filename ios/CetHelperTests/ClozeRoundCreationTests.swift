import XCTest
@testable import CetHelper

final class ClozeRoundCreationTests: XCTestCase {
    func testSortForReviewFiveLevelPriority() {
        let now: Int64 = 10_000

        // 1) due vs not due
        let dueLate = makeSampleFavorite(articleId: "a", startOffset: 0, endOffset: 1, savedAt: 100, masteryLevel: 2, nextReviewAt: 9_000)
        let dueEarly = makeSampleFavorite(articleId: "a", startOffset: 1, endOffset: 2, savedAt: 100, masteryLevel: 2, nextReviewAt: 5_000)
        let notDue = makeSampleFavorite(articleId: "a", startOffset: 2, endOffset: 3, savedAt: 500, masteryLevel: 0, nextReviewAt: 12_000)

        // 2) same nextReviewAt -> masteryLevel ASC
        let lowerMastery = makeSampleFavorite(articleId: "a", startOffset: 3, endOffset: 4, savedAt: 100, masteryLevel: 1, nextReviewAt: 9_000)

        // 3) same masteryLevel -> savedAt DESC
        let newerSaved = makeSampleFavorite(articleId: "a", startOffset: 4, endOffset: 5, savedAt: 200, masteryLevel: 1, nextReviewAt: 9_000)

        // 4) same savedAt -> articleId ASC, startOffset ASC, endOffset ASC
        let tieA0 = makeSampleFavorite(articleId: "a", startOffset: 10, endOffset: 12, savedAt: 200, masteryLevel: 1, nextReviewAt: 9_000)
        let tieA1 = makeSampleFavorite(articleId: "a", startOffset: 10, endOffset: 15, savedAt: 200, masteryLevel: 1, nextReviewAt: 9_000)
        let tieB = makeSampleFavorite(articleId: "b", startOffset: 0, endOffset: 2, savedAt: 200, masteryLevel: 1, nextReviewAt: 9_000)

        let input = [notDue, tieB, tieA1, dueLate, tieA0, lowerMastery, newerSaved, dueEarly]
        let sorted = ClozeRound.sortForReview(input, now: now)

        XCTAssertEqual(sorted, [dueEarly, newerSaved, tieA0, tieA1, tieB, lowerMastery, dueLate, notDue])
    }

    func testDirtyDataSkippedAndLimitDoesNotCountOverflowAsSkipped() {
        let sentence = "Alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu."
        let article = ReadingArticle(id: "art-1", title: "Alpha Beta Study", body: sentence)
        let nsBody = sentence as NSString

        let words = ["Alpha", "beta", "gamma", "delta", "epsilon", "zeta", "eta", "theta", "iota", "kappa", "lambda", "mu"]
        var favorites: [Favorite?] = []
        for (i, w) in words.enumerated() {
            let r = nsBody.range(of: w)
            favorites.append(makeSampleFavorite(
                articleId: "art-1",
                startOffset: r.location,
                endOffset: r.location + r.length,
                selection: w,
                sentence: sentence,
                savedAt: Int64(1000 - i)
            ))
        }

        // Insert dirty entries with earliest nextReviewAt so they are inspected before limit is reached.
        let wrongArticle = makeSampleFavorite(articleId: "missing", startOffset: 0, endOffset: 5, selection: "Alpha", sentence: sentence, nextReviewAt: -100)
        let outOfBounds = makeSampleFavorite(articleId: "art-1", startOffset: 0, endOffset: 999, selection: "Alpha", sentence: sentence, nextReviewAt: -99)
        let mismatchContent = makeSampleFavorite(articleId: "art-1", startOffset: 0, endOffset: 5, selection: "Wrong", sentence: sentence, nextReviewAt: -98)
        let mismatchLength = makeSampleFavorite(articleId: "art-1", startOffset: 0, endOffset: 5, selection: "Al", sentence: sentence, nextReviewAt: -97)
        let impossibleSentence = makeSampleFavorite(articleId: "art-1", startOffset: 0, endOffset: 5, selection: "Alpha", sentence: "Not in body at all", nextReviewAt: -96)

        // Ambiguous overlapping sentence window: "aa aa" in "aa aa aa" at [3,5).
        let ambigArticle = ReadingArticle(id: "ambig", title: "Ambig", body: "aa aa aa")
        let ambigFav = makeSampleFavorite(
            articleId: "ambig",
            startOffset: 3,
            endOffset: 5,
            selection: "aa",
            sentence: "aa aa",
            nextReviewAt: -95
        )

        favorites.append(contentsOf: [nil, wrongArticle, outOfBounds, mismatchContent, mismatchLength, impossibleSentence, ambigFav])

        let creation = ClozeRound.create(
            favorites: favorites,
            articles: ["art-1": article, "ambig": ambigArticle],
            now: 1000,
            limit: 10
        )
        XCTAssertNotNil(creation.round)
        XCTAssertEqual(creation.round?.questions.count, 10)
        // 1 nil + 6 dirty = 7 skipped; the 2 extra valid words beyond limit=10 are NOT skipped!
        XCTAssertEqual(creation.skippedCount, 7)
    }

    func testBlankReplacesOnlySelectedOccurrenceAndMaskTitle() {
        let sentence = "We decline to accept a sharp decline in quality."
        let article = ReadingArticle(id: "dup", title: "Understanding LOWER\u{3000}DEMAND & Decline", body: sentence)
        let nsSentence = sentence as NSString
        let firstRange = nsSentence.range(of: "decline")
        let secondRange = nsSentence.range(
            of: "decline",
            options: .literal,
            range: NSRange(location: firstRange.location + 1, length: nsSentence.length - firstRange.location - 1)
        )

        let fav = makeSampleFavorite(
            articleId: "dup",
            startOffset: secondRange.location,
            endOffset: secondRange.location + secondRange.length,
            selection: "decline",
            sentence: sentence
        )
        let q = ClozeQuestion.from(article: article, favorite: fav)!
        XCTAssertEqual(q.maskedSentence, "We decline to accept a sharp ____ in quality.")
        XCTAssertEqual(q.maskedTitle, "Understanding LOWER\u{3000}DEMAND & ____")
        XCTAssertEqual(ClozeQuestion.maskTitle("Understanding LOWER\u{3000}DEMAND", selection: "lower demand"), "Understanding ____")
    }

    func testZeroValidQuestionsReturnsNilRound() {
        let bad = makeSampleFavorite(articleId: "nope", startOffset: 0, endOffset: 3, selection: "foo", sentence: "foo")
        let creation = ClozeRound.create(favorites: [bad], articles: [:], now: 1000)
        XCTAssertNil(creation.round)
        XCTAssertEqual(creation.skippedCount, 1)
    }
}
