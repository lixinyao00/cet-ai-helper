package com.cethelper.android;

import androidx.room.Dao;
import androidx.room.Embedded;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
interface FavoriteDao {
    final class Row {
        @Embedded public final Favorite favorite;
        public final String articleTitle;

        public Row(Favorite favorite, String articleTitle) {
            this.favorite = favorite;
            this.articleTitle = articleTitle;
        }
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    void insert(Favorite favorite);

    @Query("SELECT favorites.*, articles.title AS articleTitle FROM favorites LEFT JOIN articles "
            + "ON favorites.serverUrl = articles.serverUrl AND favorites.studentId = articles.studentId "
            + "AND favorites.articleId = articles.articleId "
            + "WHERE favorites.serverUrl = :serverUrl AND favorites.studentId = :studentId "
            + "ORDER BY favorites.savedAt DESC, favorites.articleId, favorites.startOffset, favorites.endOffset")
    List<Row> list(String serverUrl, long studentId);
}
