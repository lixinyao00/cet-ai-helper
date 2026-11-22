package com.cethelper.android;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Ignore;

import java.util.Locale;

@Entity(tableName = "favorites",
        primaryKeys = {"serverUrl", "studentId", "articleId", "startOffset", "endOffset"})
public final class Favorite {
    public static final String DICT_ARTICLE_ID = "__dict__";
    public static final String SOURCE_READER = "reader";
    public static final String SOURCE_DICTIONARY = "dictionary";

    @NonNull public final String serverUrl;
    public final long studentId;
    @NonNull public final String articleId;
    public final int startOffset;
    public final int endOffset;
    @NonNull public final String selection;
    @NonNull public final String sentence;
    @NonNull public final String meaning;
    @NonNull public final String explanation;
    @NonNull public final String mode;
    public final long savedAt;

    @NonNull
    @ColumnInfo(name = "phonetic", defaultValue = "")
    public final String phonetic;

    @NonNull
    @ColumnInfo(name = "part_of_speech", defaultValue = "")
    public final String partOfSpeech;

    @ColumnInfo(name = "mastery_level", defaultValue = "0")
    public final int masteryLevel;

    @ColumnInfo(name = "review_count", defaultValue = "0")
    public final int reviewCount;

    @ColumnInfo(name = "last_reviewed_at", defaultValue = "0")
    public final long lastReviewedAt;

    @ColumnInfo(name = "next_review_at", defaultValue = "0")
    public final long nextReviewAt;

    @NonNull
    @ColumnInfo(name = "lemma", defaultValue = "")
    public final String lemma;

    @NonNull
    @ColumnInfo(name = "cet_level", defaultValue = "")
    public final String cetLevel;

    @NonNull
    @ColumnInfo(name = "source_type", defaultValue = "reader")
    public final String sourceType;

    public Favorite(@NonNull String serverUrl, long studentId, @NonNull String articleId,
                    int startOffset, int endOffset, @NonNull String selection,
                    @NonNull String sentence, @NonNull String meaning, @NonNull String explanation,
                    @NonNull String mode, long savedAt,
                    @NonNull String phonetic, @NonNull String partOfSpeech,
                    int masteryLevel, int reviewCount, long lastReviewedAt, long nextReviewAt,
                    @NonNull String lemma, @NonNull String cetLevel, @NonNull String sourceType) {
        this.serverUrl = serverUrl;
        this.studentId = studentId;
        this.articleId = articleId;
        this.startOffset = startOffset;
        this.endOffset = endOffset;
        this.selection = selection;
        this.sentence = sentence;
        this.meaning = meaning;
        this.explanation = explanation;
        this.mode = mode;
        this.savedAt = savedAt;
        this.phonetic = phonetic;
        this.partOfSpeech = partOfSpeech;
        this.masteryLevel = masteryLevel;
        this.reviewCount = reviewCount;
        this.lastReviewedAt = lastReviewedAt;
        this.nextReviewAt = nextReviewAt;
        this.lemma = lemma;
        this.cetLevel = cetLevel;
        this.sourceType = sourceType;
    }

    @Ignore
    public Favorite(@NonNull String serverUrl, long studentId, @NonNull String articleId,
                    int startOffset, int endOffset, @NonNull String selection,
                    @NonNull String sentence, @NonNull String meaning, @NonNull String explanation,
                    @NonNull String mode, long savedAt,
                    @NonNull String phonetic, @NonNull String partOfSpeech,
                    int masteryLevel, int reviewCount, long lastReviewedAt, long nextReviewAt) {
        this(serverUrl, studentId, articleId, startOffset, endOffset, selection, sentence,
                meaning, explanation, mode, savedAt, phonetic, partOfSpeech,
                masteryLevel, reviewCount, lastReviewedAt, nextReviewAt,
                "", "", SOURCE_READER);
    }

    @Ignore
    public Favorite(@NonNull String serverUrl, long studentId, @NonNull String articleId,
                    int startOffset, int endOffset, @NonNull String selection,
                    @NonNull String sentence, @NonNull String meaning, @NonNull String explanation,
                    @NonNull String mode, long savedAt) {
        this(serverUrl, studentId, articleId, startOffset, endOffset, selection, sentence,
                meaning, explanation, mode, savedAt, "", "", 0, 0, 0L, 0L,
                "", "", SOURCE_READER);
    }

    @Ignore
    public Favorite(@NonNull String serverUrl, long studentId, @NonNull String articleId,
                    int startOffset, int endOffset, @NonNull String selection,
                    @NonNull String sentence, @NonNull String meaning, @NonNull String explanation,
                    @NonNull String mode, long savedAt,
                    @NonNull String phonetic, @NonNull String partOfSpeech) {
        this(serverUrl, studentId, articleId, startOffset, endOffset, selection, sentence,
                meaning, explanation, mode, savedAt, phonetic, partOfSpeech, 0, 0, 0L, 0L,
                "", "", SOURCE_READER);
    }

    @Ignore
    public Favorite(@NonNull String serverUrl, long studentId, @NonNull String articleId,
                    int startOffset, int endOffset, @NonNull String selection,
                    @NonNull String sentence, @NonNull String meaning, @NonNull String explanation,
                    @NonNull String mode, long savedAt,
                    @NonNull String phonetic, @NonNull String partOfSpeech,
                    @NonNull String lemma, @NonNull String cetLevel, @NonNull String sourceType) {
        this(serverUrl, studentId, articleId, startOffset, endOffset, selection, sentence,
                meaning, explanation, mode, savedAt, phonetic, partOfSpeech, 0, 0, 0L, 0L,
                lemma, cetLevel, sourceType);
    }

    static Favorite fromDictionary(String serverUrl, long studentId, DictionaryEntry entry, long now) {
        String sel = (entry.lemma != null && !entry.lemma.isEmpty()) ? entry.lemma : entry.word;
        if (sel == null) {
            sel = "";
        }
        String sent = entry.exampleSentence == null ? "" : entry.exampleSentence;
        int start = 0;
        int end = 0;
        if (!sel.isEmpty() && !sent.isEmpty()) {
            int idx = indexOfIgnoreCase(sent, sel);
            if (idx >= 0) {
                start = idx;
                end = idx + sel.length();
            }
        }
        String expl = entry.explanation == null ? "" : entry.explanation;
        String trans = entry.exampleTranslation == null ? "" : entry.exampleTranslation;
        if (!trans.isEmpty()) {
            expl = expl + "\n例句翻译：" + trans;
        }
        String modeValue = (entry.mode == null || entry.mode.isEmpty()) ? "demo" : entry.mode;
        return new Favorite(
                serverUrl,
                studentId,
                DICT_ARTICLE_ID,
                start,
                end,
                sel,
                sent,
                entry.meaning == null ? "" : entry.meaning,
                expl,
                modeValue,
                now,
                entry.phonetic == null ? "" : entry.phonetic,
                entry.partOfSpeech == null ? "" : entry.partOfSpeech,
                0,
                0,
                0L,
                0L,
                entry.lemma == null ? "" : entry.lemma,
                entry.cetLevel == null ? "" : entry.cetLevel,
                SOURCE_DICTIONARY
        );
    }

    static int indexOfIgnoreCase(String haystack, String needle) {
        if (haystack == null || needle == null || needle.isEmpty()) {
            return -1;
        }
        return haystack.toLowerCase(Locale.ROOT).indexOf(needle.toLowerCase(Locale.ROOT));
    }
}
