package com.cethelper.android;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

final class DraftStore {
    private static final long DEBOUNCE_MS = 400L;

    record Translation(String source, String draft) {}
    record Writing(String prompt, String essay) {}

    private final SharedPreferences preferences;
    private final Handler handler;
    private Runnable pendingTranslation;
    private Runnable pendingWriting;

    DraftStore(SharedPreferences preferences) {
        this.preferences = preferences;
        Looper mainLooper = Looper.getMainLooper();
        this.handler = mainLooper != null ? new Handler(mainLooper) : null;
    }

    Translation translation(ArticleOwner owner) {
        flushTranslation();
        String key = prefix(owner);
        return new Translation(preferences.getString(key + "translation.source", ""),
                preferences.getString(key + "translation.draft", ""));
    }

    void saveTranslation(ArticleOwner owner, String source, String draft) {
        saveTranslation(owner, source, draft, false);
    }

    void saveTranslation(ArticleOwner owner, String source, String draft, boolean immediate) {
        if (handler == null || immediate) {
            cancelTranslation();
            writeTranslation(owner, source, draft);
            return;
        }
        cancelTranslation();
        pendingTranslation = () -> {
            pendingTranslation = null;
            writeTranslation(owner, source, draft);
        };
        handler.postDelayed(pendingTranslation, DEBOUNCE_MS);
    }

    Writing writing(ArticleOwner owner) {
        flushWriting();
        String key = prefix(owner);
        return new Writing(preferences.getString(key + "writing.prompt", ""),
                preferences.getString(key + "writing.essay", ""));
    }

    void saveWriting(ArticleOwner owner, String prompt, String essay) {
        saveWriting(owner, prompt, essay, false);
    }

    void saveWriting(ArticleOwner owner, String prompt, String essay, boolean immediate) {
        if (handler == null || immediate) {
            cancelWriting();
            writeWriting(owner, prompt, essay);
            return;
        }
        cancelWriting();
        pendingWriting = () -> {
            pendingWriting = null;
            writeWriting(owner, prompt, essay);
        };
        handler.postDelayed(pendingWriting, DEBOUNCE_MS);
    }

    void flush() {
        flushTranslation();
        flushWriting();
    }

    private void flushTranslation() {
        if (pendingTranslation != null) {
            Runnable task = pendingTranslation;
            cancelTranslation();
            task.run();
        }
    }

    private void flushWriting() {
        if (pendingWriting != null) {
            Runnable task = pendingWriting;
            cancelWriting();
            task.run();
        }
    }

    private void cancelTranslation() {
        if (handler != null && pendingTranslation != null) {
            handler.removeCallbacks(pendingTranslation);
        }
        pendingTranslation = null;
    }

    private void cancelWriting() {
        if (handler != null && pendingWriting != null) {
            handler.removeCallbacks(pendingWriting);
        }
        pendingWriting = null;
    }

    private void writeTranslation(ArticleOwner owner, String source, String draft) {
        String key = prefix(owner);
        preferences.edit().putString(key + "translation.source", source)
                .putString(key + "translation.draft", draft).apply();
    }

    private void writeWriting(ArticleOwner owner, String prompt, String essay) {
        String key = prefix(owner);
        preferences.edit().putString(key + "writing.prompt", prompt)
                .putString(key + "writing.essay", essay).apply();
    }

    private static String prefix(ArticleOwner owner) {
        return owner.studentId() + "|" + owner.serverUrl() + "|";
    }
}
