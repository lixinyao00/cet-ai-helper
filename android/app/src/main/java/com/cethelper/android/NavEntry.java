package com.cethelper.android;

import android.os.Bundle;

import androidx.annotation.Nullable;

final class NavEntry {
    enum Screen { HOME, DICTIONARY, TUTOR, READER, VOCABULARY, CLOZE, IMPORT, TRANSLATION, WRITING }

    static final String ARG_QUERY = "query";
    static final String ARG_FROM_READER_ID = "from_reader_id";
    static final String ARG_FROM_PRACTICE = "from_practice";
    static final String ARG_FAV_START = "fav_start";
    static final String ARG_FAV_END = "fav_end";
    static final String ARG_FAV_SELECTION = "fav_selection";
    static final String ARG_TUTOR_CONTEXT = "tutor_context";

    final Screen screen;
    @Nullable final String articleId;
    @Nullable final Bundle args;
    private final boolean root;

    NavEntry(Screen screen, @Nullable String articleId, @Nullable Bundle args, boolean root) {
        if (screen == null) {
            throw new IllegalArgumentException("screen must not be null");
        }
        this.screen = screen;
        this.articleId = articleId;
        this.args = args;
        this.root = root && isL1TabScreen(screen);
    }

    static boolean isL1TabScreen(Screen screen) {
        return screen == Screen.HOME
                || screen == Screen.DICTIONARY
                || screen == Screen.TUTOR
                || screen == Screen.VOCABULARY;
    }

    static NavEntry home() {
        return new NavEntry(Screen.HOME, null, null, true);
    }

    static NavEntry tab(Screen tab) {
        if (!isL1TabScreen(tab)) {
            throw new IllegalArgumentException("Not an L1 tab screen: " + tab);
        }
        return new NavEntry(tab, null, null, true);
    }

    static NavEntry of(Screen screen) {
        return new NavEntry(screen, null, null, false);
    }

    static NavEntry reader(String articleId, @Nullable Bundle args) {
        return new NavEntry(Screen.READER, articleId, args, false);
    }

    static NavEntry dictionary(@Nullable Bundle args, boolean asRoot) {
        return new NavEntry(Screen.DICTIONARY, null, args, asRoot);
    }

    static NavEntry tutor(@Nullable Bundle args, boolean asRoot) {
        return new NavEntry(Screen.TUTOR, null, args, asRoot);
    }

    static NavEntry vocabulary(@Nullable Bundle args, boolean asRoot) {
        return new NavEntry(Screen.VOCABULARY, null, args, asRoot);
    }

    boolean isRoot() {
        return root && isL1TabScreen(screen);
    }

    Bundle toBundle() {
        Bundle b = new Bundle();
        b.putString("screen", screen.name());
        if (articleId != null) {
            b.putString("articleId", articleId);
        }
        b.putBoolean("root", root);
        if (args != null) {
            b.putBundle("args", new Bundle(args));
        }
        return b;
    }

    static NavEntry fromBundle(@Nullable Bundle b) {
        if (b == null) {
            return home();
        }
        Screen screen;
        try {
            screen = Screen.valueOf(b.getString("screen", Screen.HOME.name()));
        } catch (IllegalArgumentException ignored) {
            screen = Screen.HOME;
        }
        String articleId = b.getString("articleId");
        boolean root = b.getBoolean("root", screen == Screen.HOME);
        Bundle args = b.getBundle("args");
        return new NavEntry(screen, articleId, args == null ? null : new Bundle(args), root);
    }
}
