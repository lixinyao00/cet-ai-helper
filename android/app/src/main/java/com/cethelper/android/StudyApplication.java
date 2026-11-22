package com.cethelper.android;

import android.app.Application;
import android.util.LruCache;

import androidx.room.Room;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class StudyApplication extends Application {
    final ExecutorService accounts = Executors.newSingleThreadExecutor();
    final ExecutorService storage = Executors.newSingleThreadExecutor();
    final ExecutorService images = Executors.newSingleThreadExecutor();
    final ExecutorService lookups = Executors.newFixedThreadPool(3);
    final ExecutorService practiceRequests = Executors.newFixedThreadPool(3);
    final ExecutorService tutorStreams = Executors.newSingleThreadExecutor();
    final LruCache<String, ApiClient.Explanation> explanationCache = new LruCache<>(200);
    final LruCache<String, SentenceAnalysis> analysisCache = new LruCache<>(100);
    private StudyDatabase database;

    @Override
    public void onCreate() {
        super.onCreate();
        database = Room.databaseBuilder(this, StudyDatabase.class, "study.db")
                .addMigrations(StudyDatabase.MIGRATION_1_2, StudyDatabase.MIGRATION_2_3, StudyDatabase.MIGRATION_3_4)
                .build();
    }

    ArticleDao articles() {
        return database.articles();
    }

    FavoriteDao favorites() {
        return database.favorites();
    }

    PracticeRecordDao practiceRecords() {
        return database.practiceRecords();
    }

    DictionaryHistoryDao dictionaryHistory() {
        return database.dictionaryHistory();
    }

    TutorMessageDao tutorMessages() {
        return database.tutorMessages();
    }

    static String explanationCacheKey(String serverUrl, String selection, String sentence) {
        return serverUrl + "\0" + selection + "\0" + sentence;
    }

    static String analysisCacheKey(String serverUrl, String sentence) {
        return serverUrl + "\0" + sentence;
    }
}
