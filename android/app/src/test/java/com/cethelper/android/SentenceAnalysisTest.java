package com.cethelper.android;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public final class SentenceAnalysisTest {
    @Test
    public void findsDuplicateChunksInOrderAndPreservesOriginalGaps() {
        String original = "They said  that practice matters and that patience helps.";
        List<SentenceAnalysis.Fragment> fragments = SentenceAnalysis.locate(original, List.of(
                chunk("They said"), chunk("that"), chunk("practice matters"),
                chunk("and"), chunk("that"), chunk("patience helps")));

        assertEquals(6, fragments.size());
        assertEquals(original.indexOf("that"), fragments.get(1).start());
        assertEquals(original.lastIndexOf("that"), fragments.get(4).start());
        assertEquals(fragments.get(4).start() + 4, fragments.get(4).end());
        assertEquals("  ", original.substring(fragments.get(0).end(), fragments.get(1).start()));
        assertEquals(".", original.substring(fragments.get(5).end()));
    }

    @Test
    public void rejectsARepeatedResponseWithNoSecondMatch() {
        assertThrows(IllegalArgumentException.class,
                () -> SentenceAnalysis.locate("They said that practice matters.",
                        List.of(chunk("that"), chunk("that"))));
    }

    private static SentenceAnalysis.Chunk chunk(String text) {
        return new SentenceAnalysis.Chunk(text, "role", "explanation");
    }
}
