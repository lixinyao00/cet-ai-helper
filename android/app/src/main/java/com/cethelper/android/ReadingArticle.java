package com.cethelper.android;

import android.icu.text.BreakIterator;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

record ReadingArticle(String id, String title, String body) {
    private static final Pattern PARAGRAPH_BREAK = Pattern.compile("\\n(?:[^\\S\\n]*\\n)+");
    private static final Locale SENTENCE_LOCALE = Locale.forLanguageTag("en-u-ss-standard");

    record Paragraph(int start, String text) {
        int end() {
            return start + text.length();
        }

        // Return the selected text and its original sentence, retaining the punctuation and spacing.
        Selection select(int from, int to) {
            from = skipSpace(text, from, to);
            to = trimEnd(text, from, to);
            if (from == to) {
                return null;
            }
            BreakIterator sentences = BreakIterator.getSentenceInstance(SENTENCE_LOCALE);
            sentences.setText(text.replace('\n', ' '));
            int sentenceStart = sentences.preceding(from + 1);
            int sentenceEnd = sentences.following(from);
            String sentence = to <= sentenceEnd
                    ? trimSpace(text.substring(sentenceStart, sentenceEnd)) : null;
            return new Selection(start + from, start + to, text.substring(from, to), sentence);
        }
    }

    record Selection(int start, int end, String text, String sentence) {}

    List<Paragraph> paragraphs() {
        List<Paragraph> paragraphs = new ArrayList<>();
        Matcher breaks = PARAGRAPH_BREAK.matcher(body);
        int start = 0;
        while (breaks.find()) {
            addParagraph(paragraphs, start, breaks.start());
            start = breaks.end();
        }
        addParagraph(paragraphs, start, body.length());
        return paragraphs;
    }

    private void addParagraph(List<Paragraph> paragraphs, int from, int to) {
        if (skipSpace(body, from, to) != to) {
            paragraphs.add(new Paragraph(from, body.substring(from, to)));
        }
    }

    static String trimSpace(String text) {
        int start = skipSpace(text, 0, text.length());
        return text.substring(start, trimEnd(text, start, text.length()));
    }

    private static int skipSpace(String text, int from, int to) {
        while (from < to && isSpace(text.charAt(from))) {
            from++;
        }
        return from;
    }

    private static int trimEnd(String text, int from, int to) {
        while (to > from && isSpace(text.charAt(to - 1))) {
            to--;
        }
        return to;
    }

    private static boolean isSpace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    }
}
