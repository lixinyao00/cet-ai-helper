package com.cethelper.android;

import android.content.SharedPreferences;

final class DraftStore {
    record Translation(String source, String draft) {}
    record Writing(String prompt, String essay) {}

    private final SharedPreferences preferences;

    DraftStore(SharedPreferences preferences) {
        this.preferences = preferences;
    }

    Translation translation(ArticleOwner owner) {
        String key = prefix(owner);
        return new Translation(preferences.getString(key + "translation.source", ""),
                preferences.getString(key + "translation.draft", ""));
    }

    void saveTranslation(ArticleOwner owner, String source, String draft) {
        String key = prefix(owner);
        preferences.edit().putString(key + "translation.source", source)
                .putString(key + "translation.draft", draft).apply();
    }

    Writing writing(ArticleOwner owner) {
        String key = prefix(owner);
        return new Writing(preferences.getString(key + "writing.prompt", ""),
                preferences.getString(key + "writing.essay", ""));
    }

    void saveWriting(ArticleOwner owner, String prompt, String essay) {
        String key = prefix(owner);
        preferences.edit().putString(key + "writing.prompt", prompt)
                .putString(key + "writing.essay", essay).apply();
    }

    private static String prefix(ArticleOwner owner) {
        return owner.studentId() + "|" + owner.serverUrl() + "|";
    }
}
