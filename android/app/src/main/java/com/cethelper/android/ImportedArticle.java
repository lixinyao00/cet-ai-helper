package com.cethelper.android;

import androidx.annotation.NonNull;
import androidx.room.Entity;

@Entity(tableName = "articles", primaryKeys = {"serverUrl", "studentId", "articleId"})
public final class ImportedArticle {
    @NonNull public final String serverUrl;
    public final long studentId;
    @NonNull public final String articleId;
    @NonNull public final String title;
    @NonNull public final String body;
    public final long createdAt;

    public ImportedArticle(@NonNull String serverUrl, long studentId, @NonNull String articleId,
                           @NonNull String title, @NonNull String body, long createdAt) {
        this.serverUrl = serverUrl;
        this.studentId = studentId;
        this.articleId = articleId;
        this.title = title;
        this.body = body;
        this.createdAt = createdAt;
    }

    ReadingArticle readingArticle() {
        return new ReadingArticle(articleId, title, body);
    }
}
