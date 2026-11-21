package com.cethelper.android;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Ignore;

@Entity(tableName = "favorites",
        primaryKeys = {"serverUrl", "studentId", "articleId", "startOffset", "endOffset"})
public final class Favorite {
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

    public Favorite(@NonNull String serverUrl, long studentId, @NonNull String articleId,
                    int startOffset, int endOffset, @NonNull String selection,
                    @NonNull String sentence, @NonNull String meaning, @NonNull String explanation,
                    @NonNull String mode, long savedAt,
                    @NonNull String phonetic, @NonNull String partOfSpeech,
                    int masteryLevel, int reviewCount, long lastReviewedAt, long nextReviewAt) {
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
    }

    @Ignore
    public Favorite(@NonNull String serverUrl, long studentId, @NonNull String articleId,
                    int startOffset, int endOffset, @NonNull String selection,
                    @NonNull String sentence, @NonNull String meaning, @NonNull String explanation,
                    @NonNull String mode, long savedAt) {
        this(serverUrl, studentId, articleId, startOffset, endOffset, selection, sentence,
                meaning, explanation, mode, savedAt, "", "", 0, 0, 0L, 0L);
    }

    @Ignore
    public Favorite(@NonNull String serverUrl, long studentId, @NonNull String articleId,
                    int startOffset, int endOffset, @NonNull String selection,
                    @NonNull String sentence, @NonNull String meaning, @NonNull String explanation,
                    @NonNull String mode, long savedAt,
                    @NonNull String phonetic, @NonNull String partOfSpeech) {
        this(serverUrl, studentId, articleId, startOffset, endOffset, selection, sentence,
                meaning, explanation, mode, savedAt, phonetic, partOfSpeech, 0, 0, 0L, 0L);
    }
}
