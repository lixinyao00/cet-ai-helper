package com.cethelper.android;

import android.app.Application;

import androidx.room.Room;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class StudyApplication extends Application {
    final ExecutorService accounts = Executors.newSingleThreadExecutor();
    final ExecutorService storage = Executors.newSingleThreadExecutor();
    final ExecutorService images = Executors.newSingleThreadExecutor();
    final ExecutorService practiceRequests = Executors.newFixedThreadPool(3);
    private StudyDatabase database;

    @Override
    public void onCreate() {
        super.onCreate();
        database = Room.databaseBuilder(this, StudyDatabase.class, "study.db")
                .addMigrations(StudyDatabase.MIGRATION_1_2)
                .build();
    }

    ArticleDao articles() {
        return database.articles();
    }

    FavoriteDao favorites() {
        return database.favorites();
    }
}
