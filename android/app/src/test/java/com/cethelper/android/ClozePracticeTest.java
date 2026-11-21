package com.cethelper.android;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public final class ClozePracticeTest {
    @Test
    public void blanksOnlySecondOccurrenceInSameSentence() {
        // Test-only context that the demo explanation catalog does not cover.
        String sentence = "To decline an offer, you can decline politely.";
        ReadingArticle article = new ReadingArticle("test-repeated", "An English exercise",
                "🙂 Warm-up. " + sentence + "\n\nAnother paragraph.");
        Favorite saved = favorite(article, "decline", sentence, article.body().lastIndexOf("decline"));

        ClozeQuestion question = ClozeQuestion.from(article, saved);

        assertEquals("To decline an offer, you can ____ politely.", question.prompt());
        assertSame(saved, question.favorite());
    }

    @Test
    public void repeatedSentenceUsesTheCopyContainingTheSavedRange() {
        String sentence = "A word can be a word again.";
        ReadingArticle article = new ReadingArticle("test-copy", "Repeated sentence",
                sentence + "\n\n" + sentence);
        Favorite saved = favorite(article, "word", sentence, article.body().lastIndexOf("word"));

        assertEquals("A word can be a ____ again.", ClozeQuestion.from(article, saved).prompt());
    }

    @Test
    public void entirePhraseBecomesOneBlankAndArticleTitleCannotLeakIt() {
        String sentence = "The report attributed the decline to lower demand.";
        ReadingArticle article = new ReadingArticle("test-phrase", "On LOWER  DEMAND",
                sentence + " The report was published on Monday.");
        Favorite saved = favorite(article, "lower demand", sentence, article.body().indexOf("lower demand"));

        ClozeQuestion question = ClozeQuestion.from(article, saved);

        assertEquals("The report attributed the decline to ____.", question.prompt());
        assertEquals("On ____", question.questionTitle());
        assertTrue(question.matches(" \tLoWeR dEmAnD\u3000"));
        assertFalse(question.matches("lower  demand"));
        assertFalse(question.matches("lower"));
    }

    @Test
    public void supportsExistingSampleAndHidesItsAnswerBearingTitle() {
        ReadingArticle sample = DemoArticle.open("Two meanings of “decline”");
        String sentence = "Some students decline the invitation.";
        Favorite saved = favorite(sample, "decline", sentence, sample.body().lastIndexOf("decline"));

        ClozeQuestion question = ClozeQuestion.from(sample, saved);

        assertEquals("Two meanings of “____”", question.questionTitle());
        assertEquals("Some students ____ the invitation.", question.prompt());
        assertTrue(question.matches("  DECLINE  "));
        assertFalse(question.matches(" declines "));
    }

    @Test
    public void rejectsOffsetsOrSentenceThatDoNotMatchTheSource() {
        String sentence = "Students can decline invitations.";
        ReadingArticle article = new ReadingArticle("test-offsets", "Test article", sentence);
        int position = sentence.indexOf("decline");

        assertNull(ClozeQuestion.from(article, favorite(article, "decline", sentence, position + 1)));
        assertNull(ClozeQuestion.from(article,
                favorite(article, "decline", "Students decline the invitation.", position)));
        assertNull(ClozeQuestion.from(null, favorite(article, "decline", sentence, position)));
    }

    @Test
    public void revealedAndIncorrectQuestionsNeverEarnIndependentCredit() {
        ClozeQuestion first = question("decline");
        ClozeQuestion second = question("lower demand");
        ClozeQuestion third = question("review");
        List<ClozeQuestion> source = new ArrayList<>(List.of(first, second, third));
        ClozeRound round = new ClozeRound(source);
        source.clear();

        assertSame(first, round.current());
        round.reveal();
        round.submit("DECLINE");
        assertEquals(ClozeRound.Result.REVEALED, round.result());
        assertEquals(0, round.correct());
        round.advance();

        assertSame(second, round.current());
        round.submit("decline");
        round.submit("lower demand");
        assertEquals(ClozeRound.Result.INCORRECT, round.result());
        assertEquals(0, round.correct());
        round.advance();

        assertSame(third, round.current());
        round.submit("  REVIEW ");
        round.submit("REVIEW");
        round.reveal();
        assertEquals(ClozeRound.Result.CORRECT, round.result());
        round.advance();

        assertTrue(round.isFinished());
        assertEquals(1, round.correct());
        assertEquals(3, round.total());
    }

    @Test
    public void blankInputAndPrematureAdvanceDoNotConsumeTheQuestion() {
        ClozeQuestion question = question("decline");
        ClozeRound round = new ClozeRound(List.of(question));

        round.submit(" \t\u3000");
        round.advance();

        assertEquals(ClozeRound.Result.PENDING, round.result());
        assertEquals(1, round.number());
        assertSame(question, round.current());
    }

    private static ClozeQuestion question(String answer) {
        String sentence = "They studied " + answer + ".";
        ReadingArticle article = new ReadingArticle("test-scoring", "Study notes", sentence);
        return ClozeQuestion.from(article, favorite(article, answer, sentence, sentence.indexOf(answer)));
    }

    private static Favorite favorite(ReadingArticle article, String selection, String sentence, int start) {
        return new Favorite("https://example.test", 42, article.id(), start, start + selection.length(),
                selection, sentence, "测试释义", "测试解释", "demo", 0);
    }
}
