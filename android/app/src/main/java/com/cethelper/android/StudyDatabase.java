package com.cethelper.android;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(entities = {Favorite.class, ImportedArticle.class}, version = 2)
abstract class StudyDatabase extends RoomDatabase {
    static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS articles ("
                    + "serverUrl TEXT NOT NULL, studentId INTEGER NOT NULL, articleId TEXT NOT NULL, "
                    + "title TEXT NOT NULL, body TEXT NOT NULL, createdAt INTEGER NOT NULL, "
                    + "PRIMARY KEY(serverUrl, studentId, articleId))");
        }
    };

    abstract FavoriteDao favorites();

    abstract ArticleDao articles();
}
