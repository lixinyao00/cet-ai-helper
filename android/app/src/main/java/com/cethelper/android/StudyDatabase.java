package com.cethelper.android;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(entities = {Favorite.class, ImportedArticle.class, PracticeRecord.class,
        DictionaryHistory.class, TutorMessage.class}, version = 4)
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

    static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE `favorites` ADD COLUMN `lemma` TEXT NOT NULL DEFAULT ''");
            database.execSQL("ALTER TABLE `favorites` ADD COLUMN `cet_level` TEXT NOT NULL DEFAULT ''");
            database.execSQL("ALTER TABLE `favorites` ADD COLUMN `source_type` TEXT NOT NULL DEFAULT 'reader'");

            database.execSQL("CREATE TABLE IF NOT EXISTS `dictionary_history` ("
                    + "`server_url` TEXT NOT NULL, "
                    + "`student_id` INTEGER NOT NULL, "
                    + "`word` TEXT NOT NULL, "
                    + "`phonetic` TEXT NOT NULL DEFAULT '', "
                    + "`part_of_speech` TEXT NOT NULL DEFAULT '', "
                    + "`lemma` TEXT NOT NULL DEFAULT '', "
                    + "`cet_level` TEXT NOT NULL DEFAULT '', "
                    + "`meaning` TEXT NOT NULL, "
                    + "`explanation` TEXT NOT NULL, "
                    + "`example_sentence` TEXT NOT NULL DEFAULT '', "
                    + "`mode` TEXT NOT NULL DEFAULT 'demo', "
                    + "`looked_up_at` INTEGER NOT NULL, "
                    + "PRIMARY KEY(`server_url`, `student_id`, `word`))");
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_dictionary_history_server_url_student_id_looked_up_at` "
                    + "ON `dictionary_history` (`server_url`, `student_id`, `looked_up_at`)");

            database.execSQL("CREATE TABLE IF NOT EXISTS `tutor_messages` ("
                    + "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "`server_url` TEXT NOT NULL, "
                    + "`student_id` INTEGER NOT NULL, "
                    + "`session_key` TEXT NOT NULL, "
                    + "`role` TEXT NOT NULL, "
                    + "`content` TEXT NOT NULL, "
                    + "`context_type` TEXT NOT NULL DEFAULT '', "
                    + "`context_summary` TEXT NOT NULL DEFAULT '', "
                    + "`mode` TEXT NOT NULL DEFAULT '', "
                    + "`created_at` INTEGER NOT NULL)");
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_tutor_messages_server_url_student_id_session_key_created_at` "
                    + "ON `tutor_messages` (`server_url`, `student_id`, `session_key`, `created_at`)");
        }
    };

    abstract FavoriteDao favorites();

    abstract ArticleDao articles();

    abstract PracticeRecordDao practiceRecords();

    abstract DictionaryHistoryDao dictionaryHistory();

    abstract TutorMessageDao tutorMessages();
}
