package com.cethelper.android;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import org.json.JSONException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Future;

final class DictionaryState {
    private static final long SUGGEST_DEBOUNCE_MS = 250L;
    private static final int DEFAULT_SUGGEST_LIMIT = 10;
    private static final int DEFAULT_HISTORY_LIMIT = 20;

    String query = "";
    @Nullable String levelFilter;
    @Nullable DictionaryEntry current;
    List<DictionarySuggestion> suggestions = new ArrayList<>();
    List<DictionaryHistory> history = new ArrayList<>();
    boolean loading;
    @Nullable String error;

    private final Handler main = new Handler(Looper.getMainLooper());
    @Nullable private Runnable listener;
    @Nullable private Runnable pendingSuggestDebounce;
    @Nullable private Future<?> pendingSuggestFuture;
    @Nullable private Future<?> pendingLookupFuture;
    private int suggestRevision;
    private int lookupRevision;
    private int historyRevision;
    private boolean currentFavorite;

    void setListener(@Nullable Runnable onChanged) {
        this.listener = onChanged;
    }

    void setLevelFilter(MainActivity host, @Nullable String level) {
        String normalized = (level == null || level.isEmpty()) ? null : level;
        if ((levelFilter == null && normalized == null)
                || (levelFilter != null && levelFilter.equals(normalized))) {
            return;
        }
        levelFilter = normalized;
        if (!ReadingArticle.trimSpace(query).isEmpty()) {
            suggest(host, query);
        } else {
            notifyChanged();
        }
    }

    void suggest(MainActivity host, String q) {
        query = q == null ? "" : q;
        cancelPendingSuggest();
        int rev = ++suggestRevision;
        String trimmed = ReadingArticle.trimSpace(query);
        if (trimmed.isEmpty()) {
            suggestions = new ArrayList<>();
            notifyChanged();
            return;
        }
        SessionStore.Session account = host.session();
        if (account == null) {
            return;
        }
        String level = levelFilter;
        Runnable task = () -> {
            if (rev != suggestRevision || host.session() != account) {
                return;
            }
            pendingSuggestFuture = host.app().lookups.submit(() -> {
                try {
                    List<DictionarySuggestion> result = new ApiClient(account.serverUrl())
                            .dictionarySuggest(account.token(), trimmed, level, DEFAULT_SUGGEST_LIMIT);
                    host.runOnUiThread(() -> {
                        if (rev != suggestRevision || host.session() != account) {
                            return;
                        }
                        suggestions = new ArrayList<>(result);
                        notifyChanged();
                    });
                } catch (ApiClient.Failure | IOException | JSONException failure) {
                    host.runOnUiThread(() -> {
                        if (rev != suggestRevision || host.session() != account) {
                            return;
                        }
                        if (host.handleUnauthorized(failure)) {
                            return;
                        }
                        suggestions = new ArrayList<>();
                        notifyChanged();
                    });
                }
            });
        };
        pendingSuggestDebounce = task;
        main.postDelayed(task, SUGGEST_DEBOUNCE_MS);
    }

    void lookup(MainActivity host, String word) {
        String trimmed = word == null ? "" : ReadingArticle.trimSpace(word);
        if (trimmed.isEmpty()) {
            return;
        }
        query = trimmed;
        cancelPendingSuggest();
        suggestRevision++;
        suggestions = new ArrayList<>();
        if (pendingLookupFuture != null) {
            pendingLookupFuture.cancel(true);
            pendingLookupFuture = null;
        }
        int rev = ++lookupRevision;
        SessionStore.Session account = host.session();
        if (account == null) {
            return;
        }
        loading = true;
        error = null;
        notifyChanged();

        pendingLookupFuture = host.app().lookups.submit(() -> {
            try {
                DictionaryEntry entry = new ApiClient(account.serverUrl())
                        .dictionaryLookup(account.token(), trimmed);
                long now = System.currentTimeMillis();
                host.app().storage.execute(() -> {
                    boolean fav = false;
                    List<DictionaryHistory> recentRows = Collections.emptyList();
                    try {
                        host.app().dictionaryHistory().upsert(
                                entry.toHistory(account.serverUrl(), account.student().id(), now));
                        String keyWord = favoriteSelection(entry);
                        fav = host.app().favorites().findDictionary(
                                account.serverUrl(), account.student().id(), keyWord) != null;
                        recentRows = host.app().dictionaryHistory().recent(
                                account.serverUrl(), account.student().id(), DEFAULT_HISTORY_LIMIT);
                    } catch (RuntimeException ignored) {
                        // Local persistence errors are non-fatal to rendering lookup results.
                    }
                    boolean resolvedFav = fav;
                    List<DictionaryHistory> resolvedHistory = new ArrayList<>(recentRows);
                    host.runOnUiThread(() -> {
                        if (rev != lookupRevision || host.session() != account) {
                            return;
                        }
                        current = entry;
                        currentFavorite = resolvedFav;
                        if (!resolvedHistory.isEmpty()) {
                            history = resolvedHistory;
                        }
                        loading = false;
                        error = null;
                        notifyChanged();
                    });
                });
            } catch (ApiClient.Failure | IOException | JSONException failure) {
                host.runOnUiThread(() -> {
                    if (rev != lookupRevision || host.session() != account) {
                        return;
                    }
                    loading = false;
                    if (host.handleUnauthorized(failure)) {
                        return;
                    }
                    error = MainActivity.failureMessage(failure);
                    notifyChanged();
                });
            }
        });
    }

    void toggleFavorite(MainActivity host, DictionaryEntry e) {
        if (e == null) {
            return;
        }
        SessionStore.Session account = host.session();
        if (account == null) {
            return;
        }
        String keyWord = favoriteSelection(e);
        host.app().storage.execute(() -> {
            boolean nowFav;
            try {
                Favorite existing = host.app().favorites().findDictionary(
                        account.serverUrl(), account.student().id(), keyWord);
                if (existing != null) {
                    host.app().favorites().deleteDictionary(
                            account.serverUrl(), account.student().id(), keyWord);
                    nowFav = false;
                } else {
                    Favorite row = Favorite.fromDictionary(
                            account.serverUrl(), account.student().id(), e, System.currentTimeMillis());
                    host.app().favorites().insert(row);
                    nowFav = true;
                }
            } catch (RuntimeException ignored) {
                return;
            }
            boolean finalFav = nowFav;
            host.runOnUiThread(() -> {
                if (host.session() == account && isSameEntry(current, e)) {
                    currentFavorite = finalFav;
                    notifyChanged();
                }
            });
        });
    }

    boolean isFavorite(DictionaryEntry e) {
        return e != null && isSameEntry(current, e) && currentFavorite;
    }

    void loadHistory(MainActivity host) {
        SessionStore.Session account = host.session();
        if (account == null) {
            return;
        }
        int rev = ++historyRevision;
        DictionaryEntry snapshotCurrent = current;
        host.app().storage.execute(() -> {
            List<DictionaryHistory> rows;
            boolean fav = currentFavorite;
            try {
                rows = host.app().dictionaryHistory().recent(
                        account.serverUrl(), account.student().id(), DEFAULT_HISTORY_LIMIT);
                if (snapshotCurrent != null) {
                    fav = host.app().favorites().findDictionary(
                            account.serverUrl(), account.student().id(),
                            favoriteSelection(snapshotCurrent)) != null;
                }
            } catch (RuntimeException ignored) {
                rows = Collections.emptyList();
            }
            List<DictionaryHistory> loaded = new ArrayList<>(rows);
            boolean resolvedFav = fav;
            host.runOnUiThread(() -> {
                if (rev != historyRevision || host.session() != account) {
                    return;
                }
                history = loaded;
                if (isSameEntry(current, snapshotCurrent)) {
                    currentFavorite = resolvedFav;
                }
                notifyChanged();
            });
        });
    }

    void clearHistory(MainActivity host) {
        SessionStore.Session account = host.session();
        if (account == null) {
            return;
        }
        historyRevision++;
        history = new ArrayList<>();
        notifyChanged();
        host.app().storage.execute(() -> {
            try {
                host.app().dictionaryHistory().clear(account.serverUrl(), account.student().id());
            } catch (RuntimeException ignored) {
                // Best-effort clear.
            }
        });
    }

    void reset() {
        cancelPendingSuggest();
        if (pendingLookupFuture != null) {
            pendingLookupFuture.cancel(true);
            pendingLookupFuture = null;
        }
        suggestRevision++;
        lookupRevision++;
        historyRevision++;
        query = "";
        levelFilter = null;
        current = null;
        suggestions = new ArrayList<>();
        history = new ArrayList<>();
        loading = false;
        error = null;
        currentFavorite = false;
        listener = null;
    }

    private void cancelPendingSuggest() {
        if (pendingSuggestDebounce != null) {
            main.removeCallbacks(pendingSuggestDebounce);
            pendingSuggestDebounce = null;
        }
        if (pendingSuggestFuture != null) {
            pendingSuggestFuture.cancel(true);
            pendingSuggestFuture = null;
        }
    }

    private void notifyChanged() {
        if (listener != null) {
            listener.run();
        }
    }

    private static String favoriteSelection(DictionaryEntry e) {
        if (e.lemma != null && !e.lemma.isEmpty()) {
            return e.lemma;
        }
        return e.word == null ? "" : e.word;
    }

    private static boolean isSameEntry(@Nullable DictionaryEntry a, @Nullable DictionaryEntry b) {
        if (a == null || b == null) {
            return false;
        }
        return favoriteSelection(a).equalsIgnoreCase(favoriteSelection(b));
    }
}
