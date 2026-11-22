package com.cethelper.android;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import androidx.room.Room;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public final class StudyDatabaseMigrationTest {
    private static final String TEST_DB_NAME = "migration_3_4_test.db";
    private Context context;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.deleteDatabase(TEST_DB_NAME);
    }

    @After
    public void tearDown() {
        context.deleteDatabase(TEST_DB_NAME);
    }

    @Test
    public void migratesVersion3To4PreservingFavoritesWithDefaultsAndCreatingNewTables() {
        File dbFile = context.getDatabasePath(TEST_DB_NAME);
        File parent = dbFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        // Seed an exact Room v3 database schema and one legacy favorite row.
        try (SQLiteDatabase v3 = SQLiteDatabase.openOrCreateDatabase(dbFile, null)) {
            v3.execSQL("CREATE TABLE IF NOT EXISTS `favorites` ("
                    + "`serverUrl` TEXT NOT NULL, `studentId` INTEGER NOT NULL, `articleId` TEXT NOT NULL, "
                    + "`startOffset` INTEGER NOT NULL, `endOffset` INTEGER NOT NULL, `selection` TEXT NOT NULL, "
                    + "`sentence` TEXT NOT NULL, `meaning` TEXT NOT NULL, `explanation` TEXT NOT NULL, "
                    + "`mode` TEXT NOT NULL, `savedAt` INTEGER NOT NULL, "
                    + "`phonetic` TEXT NOT NULL DEFAULT '', `part_of_speech` TEXT NOT NULL DEFAULT '', "
                    + "`mastery_level` INTEGER NOT NULL DEFAULT 0, `review_count` INTEGER NOT NULL DEFAULT 0, "
                    + "`last_reviewed_at` INTEGER NOT NULL DEFAULT 0, `next_review_at` INTEGER NOT NULL DEFAULT 0, "
                    + "PRIMARY KEY(`serverUrl`, `studentId`, `articleId`, `startOffset`, `endOffset`))");
            v3.execSQL("CREATE TABLE IF NOT EXISTS `articles` ("
                    + "`serverUrl` TEXT NOT NULL, `studentId` INTEGER NOT NULL, `articleId` TEXT NOT NULL, "
                    + "`title` TEXT NOT NULL, `body` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, "
                    + "`last_read_at` INTEGER NOT NULL DEFAULT 0, `last_scroll_y` INTEGER NOT NULL DEFAULT 0, "
                    + "PRIMARY KEY(`serverUrl`, `studentId`, `articleId`))");
            v3.execSQL("CREATE TABLE IF NOT EXISTS `practice_records` ("
                    + "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `student_id` TEXT NOT NULL, "
                    + "`type` TEXT NOT NULL, `prompt_or_source` TEXT NOT NULL, `draft` TEXT NOT NULL, "
                    + "`review_json` TEXT NOT NULL, `estimated_score` INTEGER NOT NULL DEFAULT -1, "
                    + "`created_at` INTEGER NOT NULL)");
            v3.execSQL("CREATE INDEX IF NOT EXISTS `index_practice_records_student_id_created_at` "
                    + "ON `practice_records` (`student_id`, `created_at`)");
            v3.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)");
            v3.execSQL("INSERT OR REPLACE INTO room_master_table (id, identity_hash) "
                    + "VALUES(42, 'f865e348037e78894a76a62260999105')");
            v3.execSQL("INSERT INTO favorites (serverUrl, studentId, articleId, startOffset, endOffset, "
                    + "selection, sentence, meaning, explanation, mode, savedAt) "
                    + "VALUES ('http://127.0.0.1:8080', 101, 'decline-demo-v1', 0, 7, 'decline', "
                    + "'Some students decline.', '婉拒', '解释', 'demo', 1000)");
            v3.setVersion(3);
        }

        // Open via Room v4 with MIGRATION_3_4; Room validates schema hashes, columns, defaults, and indices.
        StudyDatabase db = Room.databaseBuilder(context, StudyDatabase.class, TEST_DB_NAME)
                .addMigrations(StudyDatabase.MIGRATION_1_2, StudyDatabase.MIGRATION_2_3, StudyDatabase.MIGRATION_3_4)
                .allowMainThreadQueries()
                .build();
        try {
            List<FavoriteDao.Row> rows = db.favorites().list("http://127.0.0.1:8080", 101);
            assertEquals(1, rows.size());
            Favorite legacy = rows.get(0).favorite;
            assertEquals("decline", legacy.selection);
            assertEquals("", legacy.lemma);
            assertEquals("", legacy.cetLevel);
            assertEquals(Favorite.SOURCE_READER, legacy.sourceType);

            DictionaryHistory history = new DictionaryHistory(
                    "http://127.0.0.1:8080", 101, "abandon", "/əˈbændən/", "v.",
                    "abandon", "CET-4", "放弃", "用法说明",
                    "They had to abandon the car.", "demo", 2000L
            );
            db.dictionaryHistory().upsert(history);
            List<DictionaryHistory> recent = db.dictionaryHistory().recent("http://127.0.0.1:8080", 101, 10);
            assertEquals(1, recent.size());
            assertEquals("abandon", recent.get(0).word);

            TutorMessage msg = new TutorMessage(
                    "http://127.0.0.1:8080", 101, "free", TutorMessage.ROLE_USER,
                    "abandon 怎么用？", TutorContext.FREE, "", "", 3000L
            );
            db.tutorMessages().insert(msg);
            List<TutorMessage> session = db.tutorMessages().session("http://127.0.0.1:8080", 101, "free");
            assertEquals(1, session.size());
            assertEquals("abandon 怎么用？", session.get(0).content);

            DictionaryEntry entry = new DictionaryEntry(
                    "abandon", "abandon", "/əˈbændən/", "v.", "CET-4",
                    "放弃", "用法说明", "They had to abandon the car.", "例句翻译", "demo"
            );
            db.favorites().insert(Favorite.fromDictionary("http://127.0.0.1:8080", 101, entry, 4000L));
            Favorite foundDict = db.favorites().findDictionary("http://127.0.0.1:8080", 101, "ABANDON");
            assertNotNull(foundDict);
            assertEquals(Favorite.SOURCE_DICTIONARY, foundDict.sourceType);
            db.favorites().deleteDictionary("http://127.0.0.1:8080", 101, "abandon");
            assertNull(db.favorites().findDictionary("http://127.0.0.1:8080", 101, "abandon"));
        } finally {
            db.close();
        }
    }
}
