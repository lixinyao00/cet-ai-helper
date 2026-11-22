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

    @Query("SELECT * FROM favorites WHERE serverUrl = :serverUrl AND studentId = :studentId "
            + "AND articleId = :articleId ORDER BY startOffset ASC")
    List<Favorite> listForArticle(String serverUrl, long studentId, String articleId);

    @Query("DELETE FROM favorites WHERE serverUrl = :serverUrl AND studentId = :studentId "
            + "AND articleId = :articleId AND startOffset = :startOffset AND endOffset = :endOffset")
    void delete(String serverUrl, long studentId, String articleId, int startOffset, int endOffset);

    @Query("UPDATE favorites SET mastery_level = :masteryLevel, review_count = review_count + 1, "
            + "last_reviewed_at = :reviewedAt, next_review_at = :nextReviewAt "
            + "WHERE serverUrl = :serverUrl AND studentId = :studentId "
            + "AND articleId = :articleId AND startOffset = :startOffset AND endOffset = :endOffset")
    void recordReview(String serverUrl, long studentId, String articleId, int startOffset, int endOffset,
                      int masteryLevel, long reviewedAt, long nextReviewAt);

    @Query("SELECT COUNT(*) FROM favorites WHERE serverUrl = :serverUrl AND studentId = :studentId")
    int countAll(String serverUrl, long studentId);

    @Query("SELECT COUNT(*) FROM favorites WHERE serverUrl = :serverUrl AND studentId = :studentId "
            + "AND next_review_at <= :now")
    int countDue(String serverUrl, long studentId, long now);

    @Query("SELECT * FROM favorites WHERE serverUrl = :serverUrl AND studentId = :studentId "
            + "AND articleId = '" + Favorite.DICT_ARTICLE_ID + "' AND LOWER(selection) = LOWER(:selection) LIMIT 1")
    Favorite findDictionary(String serverUrl, long studentId, String selection);

    @Query("DELETE FROM favorites WHERE serverUrl = :serverUrl AND studentId = :studentId "
            + "AND articleId = '" + Favorite.DICT_ARTICLE_ID + "' AND LOWER(selection) = LOWER(:selection)")
    void deleteDictionary(String serverUrl, long studentId, String selection);
}
