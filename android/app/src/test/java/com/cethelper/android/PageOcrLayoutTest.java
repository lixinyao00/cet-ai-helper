package com.cethelper.android;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class PageOcrLayoutTest {

    @Test
    public void singleColumnJoinsWrappedLinesAndMergesHyphenatedWords() {
        PageOcr.OcrBlock block1 = new PageOcr.OcrBlock(50, 100, 700, 170, List.of(
                new PageOcr.OcrLine(50, 100, 700, 130, "Reading compre-"),
                new PageOcr.OcrLine(50, 140, 700, 170, "hension requires steady")
        ));
        PageOcr.OcrBlock block2 = new PageOcr.OcrBlock(50, 180, 700, 210, List.of(
                new PageOcr.OcrLine(50, 180, 700, 210, "practice every day.")
        ));
        PageOcr.OcrBlock block3 = new PageOcr.OcrBlock(50, 270, 700, 340, List.of(
                new PageOcr.OcrLine(50, 270, 700, 300, "Students should focus on con-"),
                new PageOcr.OcrLine(50, 310, 700, 340, "textual clues rather than isolated words.")
        ));

        String formatted = PageOcr.formatBlocks(List.of(block1, block2, block3), false);

        assertEquals(
                "Reading comprehension requires steady practice every day.\n\n"
                        + "Students should focus on contextual clues rather than isolated words.",
                formatted);
    }

    @Test
    public void hyphenOnlyMergesWhenNextLineStartsWithLowercase() {
        PageOcr.OcrBlock block = new PageOcr.OcrBlock(40, 100, 600, 170, List.of(
                new PageOcr.OcrLine(40, 100, 600, 130, "Students prepare for CET-"),
                new PageOcr.OcrLine(40, 140, 600, 170, "6 exams all semester.")
        ));

        String formatted = PageOcr.formatBlocks(List.of(block), false);

        assertEquals("Students prepare for CET- 6 exams all semester.", formatted);
    }

    @Test
    public void twoColumnLayoutOrdersLeftColumnBeforeRightColumn() {
        PageOcr.OcrBlock title = PageOcr.OcrBlock.of(100, 30, 880, 68, List.of("Passage One"));
        PageOcr.OcrBlock left1 = PageOcr.OcrBlock.of(40, 110, 440, 190, List.of(
                "Modern cities face grow-",
                "ing traffic pressure."
        ));
        PageOcr.OcrBlock right1 = PageOcr.OcrBlock.of(520, 110, 920, 190, List.of(
                "Public transit offers a",
                "cleaner daily commute."
        ));
        PageOcr.OcrBlock left2 = PageOcr.OcrBlock.of(40, 240, 440, 320, List.of(
                "Planners now invest in",
                "safer bike lanes."
        ));
        PageOcr.OcrBlock right2 = PageOcr.OcrBlock.of(520, 240, 920, 320, List.of(
                "Walkable streets also",
                "boost local shops."
        ));

        // Intentionally supply blocks in interleaved row order as OCR engines often emit.
        String formatted = PageOcr.formatBlocks(List.of(right1, left1, title, right2, left2), false);

        assertEquals(
                "Passage One\n\n"
                        + "Modern cities face growing traffic pressure.\n\n"
                        + "Planners now invest in safer bike lanes.\n\n"
                        + "Public transit offers a cleaner daily commute.\n\n"
                        + "Walkable streets also boost local shops.",
                formatted);
    }

    @Test
    public void edgePageNumbersAreFilteredWithoutRemovingRealBodyLines() {
        PageOcr.OcrBlock topHeader = PageOcr.OcrBlock.of(420, 10, 520, 36, List.of("Page 3"));
        PageOcr.OcrBlock body1 = PageOcr.OcrBlock.of(60, 120, 880, 190, List.of(
                "In 2024, researchers studied 3 groups of college readers.",
                "Their findings highlighted steady vocabulary growth."
        ));
        PageOcr.OcrBlock midNumber = PageOcr.OcrBlock.of(60, 250, 240, 280, List.of("1984"));
        PageOcr.OcrBlock body2 = PageOcr.OcrBlock.of(60, 340, 880, 410, List.of(
                "Chapter 2 discusses long-term retention strategies."
        ));
        PageOcr.OcrBlock bottomFooter = PageOcr.OcrBlock.of(430, 860, 510, 888, List.of("- 2 -"));

        String formatted = PageOcr.formatBlocks(
                List.of(topHeader, body1, midNumber, body2, bottomFooter), false);

        assertFalse(formatted.contains("Page 3"));
        assertFalse(formatted.contains("- 2 -"));
        assertTrue(formatted.contains("In 2024, researchers studied 3 groups of college readers."));
        assertTrue(formatted.contains("1984"));
        assertTrue(formatted.contains("Chapter 2 discusses long-term retention strategies."));
    }

    @Test
    public void outputIsCompatibleWithReadingArticleParagraphBreakSplitting() {
        PageOcr.OcrBlock p1a = new PageOcr.OcrBlock(50, 100, 750, 130, List.of(
                new PageOcr.OcrLine(50, 100, 750, 130, "First paragraph begins with a wrap-")
        ));
        PageOcr.OcrBlock p1b = new PageOcr.OcrBlock(50, 140, 750, 170, List.of(
                new PageOcr.OcrLine(50, 140, 750, 170, "ped word across blocks.")
        ));
        PageOcr.OcrBlock p2 = new PageOcr.OcrBlock(50, 230, 750, 260, List.of(
                new PageOcr.OcrLine(50, 230, 750, 260, "Second paragraph stands on its own.")
        ));
        PageOcr.OcrBlock p3 = new PageOcr.OcrBlock(50, 320, 750, 350, List.of(
                new PageOcr.OcrLine(50, 320, 750, 350, "Third paragraph concludes the article.")
        ));

        String formatted = PageOcr.formatBlocks(List.of(p1a, p1b, p2, p3), false);
        ReadingArticle article = new ReadingArticle("ocr-sample", "Sample", formatted);
        List<ReadingArticle.Paragraph> paragraphs = article.paragraphs();

        assertEquals(3, paragraphs.size());
        assertEquals("First paragraph begins with a wrapped word across blocks.", paragraphs.get(0).text());
        assertEquals("Second paragraph stands on its own.", paragraphs.get(1).text());
        assertEquals("Third paragraph concludes the article.", paragraphs.get(2).text());
    }

    @Test
    public void chineseBlocksJoinLinesWithoutExtraSpacesAndFilterChinesePageMarkers() {
        PageOcr.OcrBlock topNoise = PageOcr.OcrBlock.of(380, 12, 500, 38, List.of("第 3 页"));
        PageOcr.OcrBlock p1 = PageOcr.OcrBlock.of(60, 120, 820, 200, List.of(
                "近年来，绿色出行逐渐成为城市居民的",
                "日常选择。"
        ));
        PageOcr.OcrBlock p2 = PageOcr.OcrBlock.of(60, 280, 820, 360, List.of(
                "越来越多的年轻人选择乘坐地铁或骑行",
                "上下班。"
        ));
        PageOcr.OcrBlock bottomNoise = PageOcr.OcrBlock.of(420, 840, 480, 868, List.of("3"));

        String formatted = PageOcr.formatBlocks(List.of(topNoise, p1, p2, bottomNoise), true);

        assertEquals(
                "近年来，绿色出行逐渐成为城市居民的日常选择。\n\n"
                        + "越来越多的年轻人选择乘坐地铁或骑行上下班。",
                formatted);
    }
}
