package com.cethelper.android;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Ignore;

@Entity(tableName = "articles", primaryKeys = {"serverUrl", "studentId", "articleId"})
public final class ImportedArticle {
    @NonNull public final String serverUrl;
    public final long studentId;
    @NonNull public final String articleId;
    @NonNull public final String title;
    @NonNull public final String body;
    public final long createdAt;

    @ColumnInfo(name = "last_read_at", defaultValue = "0")
    public final long lastReadAt;

    @ColumnInfo(name = "last_scroll_y", defaultValue = "0")
    public final int lastScrollY;

    public ImportedArticle(@NonNull String serverUrl, long studentId, @NonNull String articleId,
                           @NonNull String title, @NonNull String body, long createdAt,
                           long lastReadAt, int lastScrollY) {
        this.serverUrl = serverUrl;
        this.studentId = studentId;
        this.articleId = articleId;
        this.title = title;
        this.body = body;
        this.createdAt = createdAt;
        this.lastReadAt = lastReadAt;
        this.lastScrollY = lastScrollY;
    }

    @Ignore
    public ImportedArticle(@NonNull String serverUrl, long studentId, @NonNull String articleId,
                           @NonNull String title, @NonNull String body, long createdAt) {
        this(serverUrl, studentId, articleId, title, body, createdAt, 0L, 0);
    }

    ReadingArticle readingArticle() {
        return new ReadingArticle(articleId, title, body);
    }
}
