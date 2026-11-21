package com.cethelper.android;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(entities = {Favorite.class, ImportedArticle.class, PracticeRecord.class}, version = 3)
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

    static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE favorites ADD COLUMN phonetic TEXT NOT NULL DEFAULT ''");
            database.execSQL("ALTER TABLE favorites ADD COLUMN part_of_speech TEXT NOT NULL DEFAULT ''");
            database.execSQL("ALTER TABLE favorites ADD COLUMN mastery_level INTEGER NOT NULL DEFAULT 0");
            database.execSQL("ALTER TABLE favorites ADD COLUMN review_count INTEGER NOT NULL DEFAULT 0");
            database.execSQL("ALTER TABLE favorites ADD COLUMN last_reviewed_at INTEGER NOT NULL DEFAULT 0");
            database.execSQL("ALTER TABLE favorites ADD COLUMN next_review_at INTEGER NOT NULL DEFAULT 0");

            database.execSQL("ALTER TABLE articles ADD COLUMN last_read_at INTEGER NOT NULL DEFAULT 0");
            database.execSQL("ALTER TABLE articles ADD COLUMN last_scroll_y INTEGER NOT NULL DEFAULT 0");

            database.execSQL("CREATE TABLE IF NOT EXISTS `practice_records` ("
                    + "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "`student_id` TEXT NOT NULL, "
                    + "`type` TEXT NOT NULL, "
                    + "`prompt_or_source` TEXT NOT NULL, "
                    + "`draft` TEXT NOT NULL, "
                    + "`review_json` TEXT NOT NULL, "
                    + "`estimated_score` INTEGER NOT NULL DEFAULT -1, "
                    + "`created_at` INTEGER NOT NULL)");
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_practice_records_student_id_created_at` "
                    + "ON `practice_records` (`student_id`, `created_at`)");
        }
    };

    abstract FavoriteDao favorites();

    abstract ArticleDao articles();

    abstract PracticeRecordDao practiceRecords();
}
