import XCTest
@testable import CetHelper

final class ClozePracticeTests: XCTestCase {
    func testBuildHintSingleMultiAndUnicodeScalars() {
        XCTAssertEqual(ClozeQuestion.buildHint("decline"), "d _ _ _ _ _ _ (7)")
        XCTAssertEqual(ClozeQuestion.buildHint("lower demand"), "l _ _ _ _   d _ _ _ _ _ (5+6)")
        XCTAssertEqual(ClozeQuestion.buildHint("  lower\u{3000}demand  "), "l _ _ _ _   d _ _ _ _ _ (5+6)")
        XCTAssertEqual(ClozeQuestion.buildHint("😀ab"), "😀 _ _ (3)")
        XCTAssertEqual(ClozeQuestion.buildHint("   "), "")
    }

    func testMatchesTrimsAndIgnoresCaseButPreservesInnerSpaces() {
        let article = DemoArticle.article
        let nsBody = article.body as NSString
        let range = nsBody.range(of: "lower demand")
        let fav = makeSampleFavorite(
            startOffset: range.location,
            endOffset: range.location + range.length,
            selection: "lower demand"
        )
        let question = ClozeQuestion.from(article: article, favorite: fav)!

        XCTAssertTrue(question.matches("  LoWeR dEmAnD\u{3000}"))
        XCTAssertFalse(question.matches("lower  demand"))
        XCTAssertFalse(question.matches("lower"))
    }

    func testRoundSubmitRevealScoringAndRetryWrong() {
        let article = DemoArticle.article
        let nsBody = article.body as NSString
        let r1 = nsBody.range(of: "decline")
        let r2 = nsBody.range(of: "lower demand")
        let r3 = nsBody.range(of: "invitation")

        let q1 = ClozeQuestion.from(
            article: article,
            favorite: makeSampleFavorite(
                startOffset: r1.location,
                endOffset: r1.location + r1.length,
                selection: "decline",
                sentence: "The report attributed the decline to lower demand."
            )
        )!
        let q2 = ClozeQuestion.from(
            article: article,
            favorite: makeSampleFavorite(
                startOffset: r2.location,
                endOffset: r2.location + r2.length,
                selection: "lower demand",
                sentence: "The report attributed the decline to lower demand."
            )
        )!
        let q3 = ClozeQuestion.from(
            article: article,
            favorite: makeSampleFavorite(
                startOffset: r3.location,
                endOffset: r3.location + r3.length,
                selection: "invitation",
                sentence: "Some students decline the invitation."
            )
        )!

        var round = ClozeRound(questions: [q1, q2, q3], skippedCount: 0)

        // Q1: empty does not advance or count wrong; first-try correct scores +1.
        XCTAssertEqual(round.submit("  \u{3000} "), .empty)
        XCTAssertEqual(round.currentState, .pending)
        XCTAssertEqual(round.wrongIndices, [])

        XCTAssertEqual(round.submit("DECLINE"), .correct)
        XCTAssertEqual(round.currentState, .correct)
        XCTAssertEqual(round.correctCount, 1)
        XCTAssertEqual(round.submit("decline"), .ignored)
        XCTAssertFalse(round.reveal())

        round.advance()
        XCTAssertEqual(round.currentIndex, 1)

        // Q2: wrong twice (deduped in wrongIndices), then right -> no score increment.
        XCTAssertEqual(round.submit("wrong1"), .wrong)
        XCTAssertEqual(round.currentState, .pending)
        XCTAssertEqual(round.submit("wrong2"), .wrong)
        XCTAssertEqual(round.wrongIndices, [1])
        XCTAssertEqual(round.submit("lower demand"), .correct)
        XCTAssertEqual(round.correctCount, 1)
        XCTAssertEqual(round.wrongIndices, [1])

        round.advance()
        XCTAssertEqual(round.currentIndex, 2)

        // Q3: reveal marks wrong and sets state to .revealed.
        XCTAssertTrue(round.reveal())
        XCTAssertEqual(round.currentState, .revealed)
        XCTAssertEqual(round.submit("invitation"), .ignored)
        XCTAssertEqual(round.wrongIndices, [1, 2])

        round.advance()
        XCTAssertTrue(round.isFinished)
        XCTAssertTrue(round.hasWrongQuestions)

        let retry = round.retryWrongRound()
        XCTAssertNotNil(retry)
        XCTAssertEqual(retry?.questions.map(\.answer), ["lower demand", "invitation"])
        XCTAssertEqual(retry?.correctCount, 0)
        XCTAssertEqual(retry?.wrongIndices, [])
    }

    func testSrsMasteryAndNextReviewIntervals() {
        XCTAssertEqual(Srs.nextMastery(current: 0, correct: true), 1)
        XCTAssertEqual(Srs.nextMastery(current: 1, correct: true), 2)
        XCTAssertEqual(Srs.nextMastery(current: 2, correct: true), 3)
        XCTAssertEqual(Srs.nextMastery(current: 3, correct: true), 3)
        XCTAssertEqual(Srs.nextMastery(current: -2, correct: true), 1)
        XCTAssertEqual(Srs.nextMastery(current: 3, correct: false), 0)

        let now: Int64 = 1_700_000_000_000
        XCTAssertEqual(Srs.nextReviewAt(mastery: 0, now: now), now)
        XCTAssertEqual(Srs.nextReviewAt(mastery: 1, now: now), now + Srs.dayMs)
        XCTAssertEqual(Srs.nextReviewAt(mastery: 2, now: now), now + 3 * Srs.dayMs)
        XCTAssertEqual(Srs.nextReviewAt(mastery: 3, now: now), now + 7 * Srs.dayMs)
    }
}
