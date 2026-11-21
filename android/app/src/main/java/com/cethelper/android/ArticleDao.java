package com.cethelper.android;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
interface ArticleDao {
    record Summary(String articleId, String title) {}

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    void insert(ImportedArticle article);

    @Query("SELECT articleId, title FROM articles WHERE serverUrl = :serverUrl "
            + "AND studentId = :studentId ORDER BY createdAt DESC, articleId")
    List<Summary> list(String serverUrl, long studentId);

    @Query("SELECT * FROM articles WHERE serverUrl = :serverUrl AND studentId = :studentId "
            + "AND articleId = :articleId")
    ImportedArticle find(String serverUrl, long studentId, String articleId);
}
