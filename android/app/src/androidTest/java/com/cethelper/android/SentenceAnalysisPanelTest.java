package com.cethelper.android;

import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class SentenceAnalysisPanelTest {
    @Test
    public void clickingIdenticalChunksHighlightsEachOccurrenceInTheOriginal() {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.runOnMainSync(() -> {
            var context = new ContextThemeWrapper(instrumentation.getTargetContext(), R.style.Theme_CetStudy);
            ScrollView scroll = (ScrollView) LayoutInflater.from(context)
                    .inflate(R.layout.panel_sentence_analysis, null);
            SentenceAnalysisPanel panel = new SentenceAnalysisPanel(scroll);
            String original = "They said that practice matters and that patience helps.";
            // Fixed response for a sentence outside the demo catalog.
            var chunks = List.of(chunk("They said"), chunk("that"), chunk("practice matters"),
                    chunk("and"), new SentenceAnalysis.Chunk("that", "第二个引导词", "耐心有帮助"),
                    chunk("patience helps"));
            panel.loading(original);
            panel.show(new SentenceAnalysis(original, "他们说练习很重要，也说耐心有帮助。",
                    "They said 引导两个并列宾语从句。", "live", SentenceAnalysis.locate(original, chunks)));

            LinearLayout rows = scroll.findViewById(R.id.analysis_fragments);
            TextView sentence = scroll.findViewById(R.id.analysis_original);
            assertEquals(6, rows.getChildCount());
            assertTrue(rows.getChildAt(1).performClick());
            assertHighlight(sentence, original, original.indexOf("that"));
            assertTrue(rows.getChildAt(4).performClick());
            assertHighlight(sentence, original, original.lastIndexOf("that"));
        });
    }

    private static void assertHighlight(TextView sentence, String original, int start) {
        Spanned text = (Spanned) sentence.getText();
        BackgroundColorSpan[] spans = text.getSpans(0, text.length(), BackgroundColorSpan.class);
        assertEquals(original, text.toString());
        assertEquals(1, spans.length);
        assertEquals(start, text.getSpanStart(spans[0]));
        assertEquals(start + 4, text.getSpanEnd(spans[0]));
    }

    private static SentenceAnalysis.Chunk chunk(String text) {
        return new SentenceAnalysis.Chunk(text, "测试语法作用", "测试说明");
    }
}
