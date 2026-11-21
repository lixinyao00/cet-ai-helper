package com.cethelper.android;

import java.util.StringJoiner;
import java.util.regex.Pattern;

record ClozeQuestion(ReadingArticle article, Favorite favorite, String prompt, String questionTitle) {
    private static final String BLANK = "____";
    private static final String SPACES = "[\\s\\p{Zs}]+";

    static ClozeQuestion from(ReadingArticle article, Favorite favorite) {
        if (article == null || !article.id().equals(favorite.articleId)) {
            return null;
        }

        String body = article.body();
        String sentence = favorite.sentence;
        int start = favorite.startOffset;
        int end = favorite.endOffset;
        if (start < 0 || start >= end || end > body.length()
                || favorite.selection.length() != end - start
                || !body.regionMatches(start, favorite.selection, 0, end - start)) {
            return null;
        }

        // Locate the saved sentence around the saved selection. A match elsewhere in the
        // article, or the first occurrence of the word within the sentence, is irrelevant.
        int earliest = Math.max(0, end - sentence.length());
        int latest = Math.min(start, body.length() - sentence.length());
        if (earliest > latest) {
            return null;
        }
        int sentenceStart = body.indexOf(sentence, earliest);
        if (sentenceStart < 0 || sentenceStart > latest) {
            return null;
        }
        int otherMatch = body.indexOf(sentence, sentenceStart + 1);
        if (otherMatch >= 0 && otherMatch <= latest) {
            return null;
        }

        String prompt = sentence.substring(0, start - sentenceStart)
                + BLANK + sentence.substring(end - sentenceStart);
        return new ClozeQuestion(article, favorite, prompt, maskTitle(article.title(), favorite.selection));
    }

    private static String maskTitle(String title, String answer) {
        // Example and imported titles may contain the answer themselves.
        StringJoiner pattern = new StringJoiner(SPACES);
        for (String part : answer.split(SPACES)) {
            pattern.add(Pattern.quote(part));
        }
        return Pattern.compile(pattern.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                .matcher(title)
                .replaceAll(BLANK);
    }

    boolean matches(String input) {
        return ReadingArticle.trimSpace(input).equalsIgnoreCase(favorite.selection);
    }
}
