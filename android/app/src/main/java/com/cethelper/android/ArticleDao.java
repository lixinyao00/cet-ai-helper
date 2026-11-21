package com.cethelper.android;

import androidx.room.ColumnInfo;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
interface ArticleDao {
    final class Summary {
        public final String articleId;
        public final String title;
        public final long createdAt;
        @ColumnInfo(name = "last_read_at")
        public final long lastReadAt;
        @ColumnInfo(name = "last_scroll_y")
        public final int lastScrollY;

        public Summary(String articleId, String title, long createdAt, long lastReadAt, int lastScrollY) {
            this.articleId = articleId;
            this.title = title;
            this.createdAt = createdAt;
            this.lastReadAt = lastReadAt;
            this.lastScrollY = lastScrollY;
        }

        String articleId() {
            return articleId;
        }

        String title() {
            return title;
        }

        long createdAt() {
            return createdAt;
        }

        long lastReadAt() {
            return lastReadAt;
        }

        int lastScrollY() {
            return lastScrollY;
        }
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    void insert(ImportedArticle article);

    @Query("SELECT articleId, title, createdAt, last_read_at, last_scroll_y FROM articles "
            + "WHERE serverUrl = :serverUrl AND studentId = :studentId "
            + "ORDER BY createdAt DESC, articleId")
    List<Summary> list(String serverUrl, long studentId);

    @Query("SELECT * FROM articles WHERE serverUrl = :serverUrl AND studentId = :studentId "
            + "AND articleId = :articleId")
    ImportedArticle find(String serverUrl, long studentId, String articleId);

    @Query("DELETE FROM articles WHERE serverUrl = :serverUrl AND studentId = :studentId "
            + "AND articleId = :articleId")
    void delete(String serverUrl, long studentId, String articleId);

    @Query("UPDATE articles SET last_read_at = :lastReadAt, last_scroll_y = :lastScrollY "
            + "WHERE serverUrl = :serverUrl AND studentId = :studentId AND articleId = :articleId")
    void updateProgress(String serverUrl, long studentId, String articleId, long lastReadAt, int lastScrollY);

    @Query("SELECT COUNT(*) FROM articles WHERE serverUrl = :serverUrl AND studentId = :studentId")
    int count(String serverUrl, long studentId);
}
