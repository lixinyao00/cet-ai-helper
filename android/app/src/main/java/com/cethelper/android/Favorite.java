package com.cethelper.android;

import androidx.annotation.NonNull;
import androidx.room.Entity;

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

    public Favorite(@NonNull String serverUrl, long studentId, @NonNull String articleId,
                    int startOffset, int endOffset, @NonNull String selection,
                    @NonNull String sentence, @NonNull String meaning, @NonNull String explanation,
                    @NonNull String mode, long savedAt) {
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
    }
}
