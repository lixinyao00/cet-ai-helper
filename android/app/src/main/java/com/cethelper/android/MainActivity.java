package com.cethelper.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.text.Editable;
import android.text.Layout;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONException;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.function.Consumer;

import com.cethelper.android.SessionStore.Session;

public final class MainActivity extends Activity {
    private static final long REVERIFY_DELAY_MS = 10_000;
    private static final long SCROLL_SAVE_DEBOUNCE_MS = 600;
    private static final float[] READER_FONT_SP = {18f, 21f, 25f};
    private static final String[] READER_FONT_LABELS = {"字号·标准", "字号·大", "字号·超大"};
    private static final String SIGN_IN_AGAIN = "会话已失效，请重新登录。";
    private static final String CONNECTION_ERROR = "无法连接服务，请检查网络和服务端地址。";
    private static final String INVALID_RESPONSE = "服务返回了无法读取的数据。";

    private enum Screen { HOME, READER, VOCABULARY, CLOZE, IMPORT, TRANSLATION, WRITING }

    private record Practice(ClozeRound round, ReadingArticle fromReader) {}

    private record ReaderSnapshot(
            ReadingArticle article,
            boolean fromPractice,
            int scrollY,
            int selectedStart,
            int selectedEnd,
            boolean analysisOpen,
            ApiClient.Explanation explanation,
            SentenceAnalysis analysis
    ) {}

    private record RetainedState(
            Session session,
            boolean offline,
            Screen screen,
            ReaderSnapshot reader,
            ReadingArticle vocabularyFromReader,
            String vocabularyQuery,
            Practice cloze,
            TranslationPractice translation,
            WritingPractice writing
    ) {}

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable reverify = this::reverifySession;
    private StudyApplication app;
    private PageImport pageImport;
    private SessionStore sessions;
    private DraftStore drafts;
    private SharedPreferences readerPrefs;
    private FrameLayout screen;
    private TextView sessionBanner;
    private View page;
    private Runnable backAction;
    private Session session;
    private Screen currentScreen = Screen.HOME;
    private Reader activeReader;
    private ReadingArticle vocabularyFromReader;
    private String vocabularyQuery = "";
    private Practice practice;
    private TranslationPractice translation;
    private WritingPractice writing;
    private Bundle pendingRestoreBundle;
    private TextToSpeech tts;
    private boolean ttsReady;
    private String pendingSpeech;
    private boolean destroyed;
    private boolean started;
    private boolean offline;
    private boolean reverifying;
    private Future<?> pendingLookup;
    private Future<?> pendingAnalysis;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        app = (StudyApplication) getApplication();
        sessions = new SessionStore(getSharedPreferences("session", MODE_PRIVATE));
        drafts = new DraftStore(getSharedPreferences("practice_drafts", MODE_PRIVATE));
        readerPrefs = getSharedPreferences("reader_prefs", MODE_PRIVATE);
        setContentView(R.layout.activity_main);
        screen = findViewById(R.id.screen);
        sessionBanner = findViewById(R.id.session_banner);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            View root = findViewById(R.id.study_root);
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets safe = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
                return WindowInsets.CONSUMED;
            });
            root.requestApplyInsets();
        }
        pageImport = new PageImport(this, app, state == null ? null : state.getBundle("import"));
        if (!restoreRetained()) {
            pendingRestoreBundle = state;
            restoreSession();
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public Object onRetainNonConfigurationInstance() {
        if (session == null) {
            return null;
        }
        ReaderSnapshot readerSnapshot = activeReader != null ? activeReader.snapshot() : null;
        return new RetainedState(
                session,
                offline,
                currentScreen,
                readerSnapshot,
                vocabularyFromReader,
                vocabularyQuery,
                practice,
                translation,
                writing);
    }

    @SuppressWarnings("deprecation")
    private boolean restoreRetained() {
        if (getLastNonConfigurationInstance() instanceof RetainedState retained
                && sessions.matches(retained.session().serverUrl(), retained.session().token())) {
            session = retained.session();
            setOffline(retained.offline());
            vocabularyFromReader = retained.vocabularyFromReader();
            vocabularyQuery = retained.vocabularyQuery() == null ? "" : retained.vocabularyQuery();
            practice = retained.cloze();
            translation = retained.translation();
            writing = retained.writing();
            if (pageImport.resume(session.owner())) {
                return true;
            }
            switch (retained.screen()) {
                case CLOZE -> {
                    if (practice != null) {
                        showPractice();
                        return true;
                    }
                }
                case READER -> {
                    if (retained.reader() != null) {
                        restoreReaderSnapshot(retained.reader());
                        return true;
                    }
                }
                case VOCABULARY -> {
                    showVocabulary(vocabularyFromReader);
                    return true;
                }
                case WRITING -> {
                    if (writing != null) {
                        showWriting();
                        return true;
                    }
                }
                case TRANSLATION -> {
                    if (translation != null) {
                        if (translation.answering) {
                            showTranslationAnswer();
                        } else {
                            showTranslationSource();
                        }
                        return true;
                    }
                }
                case IMPORT, HOME -> {
                    // Fall through to showHome().
                }
            }
            showHome();
            return true;
        }
        return false;
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        reverifySession();
    }

    @Override
    protected void onStop() {
        started = false;
        main.removeCallbacks(reverify);
        drafts.flush();
        if (activeReader != null) {
            activeReader.flushProgress();
        }
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        drafts.flush();
        state.putBundle("import", pageImport.saveState());
        state.putString("screen", currentScreen.name());
        state.putString("vocab_query", vocabularyQuery);
        if (session != null) {
            state.putString("session_owner_server", session.serverUrl());
            state.putLong("session_owner_id", session.student().id());
        }
        if (vocabularyFromReader != null) {
            state.putString("vocab_from_article_id", vocabularyFromReader.id());
        }
        if (activeReader != null) {
            ReaderSnapshot snap = activeReader.snapshot();
            state.putString("reader_article_id", snap.article().id());
            state.putBoolean("reader_from_practice", snap.fromPractice());
            state.putInt("reader_scroll_y", snap.scrollY());
            state.putInt("reader_sel_start", snap.selectedStart());
            state.putInt("reader_sel_end", snap.selectedEnd());
            state.putBoolean("reader_analysis_open", snap.analysisOpen());
        }
        if (practice != null) {
            state.putBundle("cloze_state", practice.round().saveState());
            if (practice.fromReader() != null) {
                state.putString("cloze_from_article_id", practice.fromReader().id());
            }
        }
        if (translation != null) {
            state.putBoolean("translation_answering", translation.answering);
            state.putString("translation_active_source", translation.source);
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        pageImport.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        drafts.flush();
        if (activeReader != null) {
            activeReader.flushProgress();
            activeReader.detach();
            activeReader = null;
        }
        if (pendingLookup != null) {
            pendingLookup.cancel(true);
            pendingLookup = null;
        }
        cancelPendingAnalysis();
        if (translation != null) {
            translation.onChanged = null;
            if (!isChangingConfigurations()) {
                closeTranslation();
            }
        }
        if (writing != null) {
            writing.onChanged = null;
            if (!isChangingConfigurations()) {
                closeWriting();
            }
        }
        if (isFinishing()) {
            pageImport.reset();
        }
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
            ttsReady = false;
        }
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (backAction != null) {
            backAction.run();
        } else {
            super.onBackPressed();
        }
    }

    private View show(int layout) {
        if (activeReader != null) {
            activeReader.flushProgress();
            activeReader.detach();
            activeReader = null;
        }
        if (translation != null) {
            translation.onChanged = null;
        }
        if (writing != null) {
            writing.onChanged = null;
        }
        if (pendingLookup != null) {
            pendingLookup.cancel(true);
            pendingLookup = null;
        }
        cancelPendingAnalysis();
        screen.removeAllViews();
        backAction = null;
        page = getLayoutInflater().inflate(layout, screen, false);
        screen.addView(page);
        return page;
    }

    private void cancelPendingAnalysis() {
        if (pendingAnalysis != null) {
            pendingAnalysis.cancel(true);
            pendingAnalysis = null;
        }
    }

    View show(int layout, Runnable onBack) {
        View view = show(layout);
        setBackAction(onBack);
        return view;
    }

    View showImportScreen(int layout, Runnable onBack) {
        currentScreen = Screen.IMPORT;
        return show(layout, onBack);
    }

    View showClozeScreen(int layout, Runnable onBack) {
        currentScreen = Screen.CLOZE;
        return show(layout, onBack);
    }

    View showWritingScreen(int layout, Runnable onBack) {
        currentScreen = Screen.WRITING;
        return show(layout, onBack);
    }

    View showTranslationScreen(int layout, Runnable onBack) {
        currentScreen = Screen.TRANSLATION;
        return show(layout, onBack);
    }

    void setBackAction(Runnable onBack) {
        backAction = onBack;
    }

    boolean isCurrent(View view) {
        return !destroyed && page == view;
    }

    ArticleOwner articleOwner() {
        return session == null ? null : session.owner();
    }

    void speakEnglish(String text) {
        if (destroyed || text == null) {
            return;
        }
        String trimmed = ReadingArticle.trimSpace(text);
        if (trimmed.isEmpty()) {
            return;
        }
        if (tts == null) {
            pendingSpeech = trimmed;
            tts = new TextToSpeech(getApplicationContext(), status -> {
                if (destroyed || tts == null) {
                    return;
                }
                if (status == TextToSpeech.SUCCESS) {
                    int langResult = tts.setLanguage(Locale.US);
                    if (langResult == TextToSpeech.LANG_MISSING_DATA
                            || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                        tts.setLanguage(Locale.ENGLISH);
                    }
                    ttsReady = true;
                    String queued = pendingSpeech;
                    pendingSpeech = null;
                    if (queued != null && !queued.isEmpty()) {
                        tts.speak(queued, TextToSpeech.QUEUE_FLUSH, null, "cet-" + System.currentTimeMillis());
                    }
                }
            });
            return;
        }
        if (ttsReady) {
            tts.speak(trimmed, TextToSpeech.QUEUE_FLUSH, null, "cet-" + System.currentTimeMillis());
        } else {
            pendingSpeech = trimmed;
        }
    }

    void recordFavoriteReview(Favorite favorite, boolean correct) {
        if (favorite == null) {
            return;
        }
        long now = System.currentTimeMillis();
        int nextMastery = ClozeRound.nextMasteryLevel(favorite.masteryLevel, correct);
        long nextReviewAt = ClozeRound.nextReviewTimestamp(nextMastery, now);
        app.storage.execute(() -> {
            try {
                app.favorites().recordReview(favorite.serverUrl, favorite.studentId, favorite.articleId,
                        favorite.startOffset, favorite.endOffset, nextMastery, now, nextReviewAt);
            } catch (RuntimeException ignored) {
                // Non-fatal persistence error; practice flow continues seamlessly.
            }
        });
    }

    void startWrongPractice(ClozeRound wrongRound) {
        if (wrongRound == null) {
            return;
        }
        ReadingArticle fromReader = practice == null ? null : practice.fromReader();
        practice = new Practice(wrongRound, fromReader);
        showPractice();
    }

    void savePracticeRecord(PracticeRecord record) {
        if (record == null) {
            return;
        }
        app.storage.execute(() -> {
            try {
                app.practiceRecords().insert(record);
            } catch (RuntimeException ignored) {
                // Non-fatal history persistence error.
            }
        });
    }

    void loadPracticeHistory(String type, Consumer<List<PracticeRecord>> callback) {
        ArticleOwner owner = articleOwner();
        if (owner == null) {
            callback.accept(new ArrayList<>());
            return;
        }
        String key = PracticeRecord.ownerKey(owner);
        app.storage.execute(() -> {
            List<PracticeRecord> records;
            try {
                records = app.practiceRecords().listByType(key, type, 20);
            } catch (RuntimeException failure) {
                records = new ArrayList<>();
            }
            List<PracticeRecord> result = records;
            runOnUiThread(() -> {
                if (!destroyed) {
                    callback.accept(result);
                }
            });
        });
    }

    private void restoreSession() {
        String token = sessions.token();
        if (token == null || ReadingArticle.trimSpace(token).isEmpty()) {
            clearSession(null);
            return;
        }
        String server = sessions.serverUrl();
        Session cached = sessions.verified(server, token);
        View status = show(R.layout.screen_status);
        status.<TextView>findViewById(R.id.status_message).setText("正在恢复会话…");
        app.accounts.execute(() -> {
            try {
                ApiClient.Student student = new ApiClient(server).me(token);
                runOnUiThread(() -> {
                    if (isCurrent(status) && sessions.matches(server, token)) {
                        if (cached != null && cached.student().id() != student.id()) {
                            clearSession(SIGN_IN_AGAIN);
                            return;
                        }
                        Session verified = new Session(server, token, student);
                        sessions.save(verified);
                        openSession(verified, false);
                    }
                });
            } catch (ApiClient.Failure | IOException | JSONException failure) {
                runOnUiThread(() -> {
                    if (!isCurrent(status) || !sessions.matches(server, token)) {
                        return;
                    }
                    if (failure instanceof ApiClient.Failure http && http.status == 401) {
                        clearSession(SIGN_IN_AGAIN);
                    } else if (failure instanceof ApiClient.ConnectionFailure && cached != null) {
                        openSession(cached, true);
                    } else {
                        showRestoreError(status, failureMessage(failure));
                    }
                });
            }
        });
    }

    private void openSession(Session account, boolean offline) {
        session = account;
        setOffline(offline);
        Bundle saved = pendingRestoreBundle;
        pendingRestoreBundle = null;
        if (pageImport.resume(account.owner())) {
            return;
        }
        if (saved != null && restoreFromBundle(account, saved)) {
            return;
        }
        showHome();
    }

    private boolean restoreFromBundle(Session account, Bundle saved) {
        String savedServer = saved.getString("session_owner_server");
        long savedStudentId = saved.getLong("session_owner_id", -1L);
        if (!account.serverUrl().equals(savedServer) || account.student().id() != savedStudentId) {
            return false;
        }
        vocabularyQuery = saved.getString("vocab_query", "");
        Screen targetScreen;
        try {
            targetScreen = Screen.valueOf(saved.getString("screen", Screen.HOME.name()));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
        switch (targetScreen) {
            case WRITING -> {
                showWriting();
                return true;
            }
            case TRANSLATION -> {
                TranslationPractice exercise = translation();
                if (saved.getBoolean("translation_answering", false)) {
                    String activeSource = saved.getString("translation_active_source", "");
                    if (!activeSource.isEmpty()) {
                        exercise.begin(activeSource);
                        showTranslationAnswer();
                        return true;
                    }
                }
                showTranslationSource();
                return true;
            }
            case VOCABULARY -> {
                String fromArticleId = saved.getString("vocab_from_article_id");
                if (fromArticleId == null) {
                    showVocabulary(null);
                    return true;
                }
                loadArticleById(account.owner(), fromArticleId, article -> showVocabulary(article));
                return true;
            }
            case READER -> {
                String articleId = saved.getString("reader_article_id");
                if (articleId == null) {
                    return false;
                }
                int scrollY = saved.getInt("reader_scroll_y", 0);
                int selStart = saved.getInt("reader_sel_start", -1);
                int selEnd = saved.getInt("reader_sel_end", -1);
                boolean analysisOpen = saved.getBoolean("reader_analysis_open", false);
                loadArticleById(account.owner(), articleId, article -> {
                    if (article == null) {
                        showHome();
                    } else {
                        restoreReaderSnapshot(new ReaderSnapshot(article, false, scrollY,
                                selStart, selEnd, analysisOpen, null, null));
                    }
                });
                return true;
            }
            case CLOZE -> {
                Bundle clozeBundle = saved.getBundle("cloze_state");
                if (clozeBundle == null) {
                    return false;
                }
                String fromArticleId = saved.getString("cloze_from_article_id");
                View loading = show(R.layout.screen_status, this::showHome);
                loading.<TextView>findViewById(R.id.status_message).setText(R.string.cloze_loading);
                ReadingArticle builtIn = example();
                ArticleOwner owner = account.owner();
                app.storage.execute(() -> {
                    try {
                        List<FavoriteDao.Row> rows = app.favorites().list(owner.serverUrl(), owner.studentId());
                        List<Favorite> favs = new ArrayList<>(rows.size());
                        for (FavoriteDao.Row r : rows) {
                            favs.add(r.favorite);
                        }
                        Map<String, ReadingArticle> cache = new HashMap<>();
                        cache.put(builtIn.id(), builtIn);
                        ClozeRound restored = ClozeRound.restoreState(clozeBundle, favs, id -> {
                            ReadingArticle cached = cache.get(id);
                            if (cached != null) {
                                return cached;
                            }
                            ImportedArticle imported = app.articles().find(owner.serverUrl(), owner.studentId(), id);
                            if (imported != null) {
                                ReadingArticle ra = imported.readingArticle();
                                cache.put(id, ra);
                                return ra;
                            }
                            return null;
                        });
                        ReadingArticle fromReader = null;
                        if (fromArticleId != null) {
                            fromReader = cache.get(fromArticleId);
                            if (fromReader == null) {
                                ImportedArticle imp = app.articles().find(owner.serverUrl(), owner.studentId(), fromArticleId);
                                if (imp != null) {
                                    fromReader = imp.readingArticle();
                                }
                            }
                        }
                        ReadingArticle resolvedFromReader = fromReader;
                        runOnUiThread(() -> {
                            if (isCurrent(loading)) {
                                if (restored != null) {
                                    practice = new Practice(restored, resolvedFromReader);
                                    showPractice();
                                } else {
                                    showVocabulary(resolvedFromReader);
                                }
                            }
                        });
                    } catch (RuntimeException failure) {
                        runOnUiThread(() -> {
                            if (isCurrent(loading)) {
                                showHome();
                            }
                        });
                    }
                });
                return true;
            }
            case IMPORT, HOME -> {
                return false;
            }
        }
        return false;
    }

    private void loadArticleById(ArticleOwner owner, String articleId, Consumer<ReadingArticle> callback) {
        if (DemoArticle.ID.equals(articleId)) {
            callback.accept(example());
            return;
        }
        View loading = show(R.layout.screen_status, this::showHome);
        loading.<TextView>findViewById(R.id.status_message).setText("正在恢复阅读进度…");
        app.storage.execute(() -> {
            ReadingArticle loaded = null;
            try {
                ImportedArticle imported = app.articles().find(owner.serverUrl(), owner.studentId(), articleId);
                if (imported != null) {
                    loaded = imported.readingArticle();
                }
            } catch (RuntimeException ignored) {
                // Fall back to Home.
            }
            ReadingArticle result = loaded;
            runOnUiThread(() -> {
                if (isCurrent(loading)) {
                    callback.accept(result);
                }
            });
        });
    }

    private void setOffline(boolean offline) {
        this.offline = offline;
        sessionBanner.setText(R.string.offline_session);
        sessionBanner.setVisibility(offline ? View.VISIBLE : View.GONE);
        scheduleReverification();
    }

    private void scheduleReverification() {
        main.removeCallbacks(reverify);
        if (started && offline && !reverifying) {
            main.postDelayed(reverify, REVERIFY_DELAY_MS);
        }
    }

    private void reverifySession() {
        Session account = session;
        if (!started || !offline || reverifying || account == null) {
            return;
        }
        reverifying = true;
        app.accounts.execute(() -> {
            try {
                ApiClient.Student student = new ApiClient(account.serverUrl()).me(account.token());
                runOnUiThread(() -> {
                    reverifying = false;
                    if (!isActive(account)) {
                        scheduleReverification();
                        return;
                    }
                    if (student.id() != account.student().id()) {
                        clearSession(SIGN_IN_AGAIN);
                        return;
                    }
                    sessions.save(new Session(account.serverUrl(), account.token(), student));
                    setOffline(false);
                });
            } catch (ApiClient.Failure | IOException | JSONException failure) {
                runOnUiThread(() -> {
                    reverifying = false;
                    if (isActive(account) && !handleUnauthorized(account, failure)
                            && !(failure instanceof ApiClient.ConnectionFailure)) {
                        sessionBanner.setText(R.string.offline_verification_failed);
                    }
                    scheduleReverification();
                });
            }
        });
    }

    private boolean isActive(Session account) {
        return !destroyed && account != null && session == account && sessions.matches(account.serverUrl(), account.token());
    }

    private void showRestoreError(View status, String reason) {
        status.<TextView>findViewById(R.id.status_message).setText(reason);
        status.findViewById(R.id.status_progress).setVisibility(View.GONE);
        View retry = status.findViewById(R.id.status_retry);
        View login = status.findViewById(R.id.status_login);
        retry.setVisibility(View.VISIBLE);
        login.setVisibility(View.VISIBLE);
        retry.setOnClickListener(v -> restoreSession());
        login.setOnClickListener(v -> clearSession(null));
    }

    private void clearSession(String reason) {
        pageImport.reset();
        practice = null;
        vocabularyFromReader = null;
        vocabularyQuery = "";
        pendingRestoreBundle = null;
        closeTranslation();
        closeWriting();
        session = null;
        currentScreen = Screen.HOME;
        setOffline(false);
        sessions.clear();
        showLogin(reason);
    }

    private void showLogin(String reason) {
        currentScreen = Screen.HOME;
        View loginPage = show(R.layout.screen_login);
        EditText name = loginPage.findViewById(R.id.username);
        EditText password = loginPage.findViewById(R.id.password);
        setMessage(loginPage.findViewById(R.id.login_status), reason);
        loginPage.findViewById(R.id.login).setOnClickListener(v -> submitLogin(loginPage, false));
        loginPage.findViewById(R.id.register).setOnClickListener(v -> submitLogin(loginPage, true));
        loginPage.findViewById(R.id.server_settings).setOnClickListener(v -> editServerUrl());
        password.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_DONE && name.isEnabled()) {
                submitLogin(loginPage, false);
                return true;
            }
            return false;
        });
    }

    private void editServerUrl() {
        View form = getLayoutInflater().inflate(R.layout.dialog_server, screen, false);
        EditText field = form.findViewById(R.id.server_url);
        field.setText(sessions.serverUrl());
        View optionalTestButton = UiViews.findOptionalView(form, "server_test_button");
        TextView optionalTestStatus = UiViews.findOptionalView(form, "server_test_status", TextView.class);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.server_settings)
                .setMessage("可点击「测试连接」检查服务状态与 Demo / Live 模式。")
                .setView(form)
                .setNeutralButton("测试连接", null)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, null)
                .create();

        Runnable runHealthCheck = () -> {
            String url;
            try {
                url = ApiClient.normalizeBaseUrl(field.getText().toString());
            } catch (IllegalArgumentException invalidUrl) {
                field.setError("请输入有效的 HTTP 或 HTTPS 服务端地址。");
                return;
            }
            field.setError(null);
            updateHealthStatus(dialog, optionalTestStatus, "正在探测服务连接…");
            Button neutral = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            if (neutral != null) {
                neutral.setEnabled(false);
            }
            if (optionalTestButton != null) {
                optionalTestButton.setEnabled(false);
            }
            app.accounts.execute(() -> {
                try {
                    ApiClient.Health health = new ApiClient(url).health();
                    String modeLabel = formatServerMode(health.mode());
                    String verPart = health.version().isEmpty() ? "" : " (v" + health.version() + ")";
                    String msg = "连接成功 · 状态 " + health.status()
                            + (modeLabel.isEmpty() ? "" : " · " + modeLabel) + verPart;
                    runOnUiThread(() -> {
                        if (!destroyed && dialog.isShowing()) {
                            updateHealthStatus(dialog, optionalTestStatus, msg);
                            if (neutral != null) {
                                neutral.setEnabled(true);
                            }
                            if (optionalTestButton != null) {
                                optionalTestButton.setEnabled(true);
                            }
                        }
                    });
                } catch (ApiClient.Failure | IOException | JSONException failure) {
                    String msg = "连接测试失败：" + failureMessage(failure);
                    runOnUiThread(() -> {
                        if (!destroyed && dialog.isShowing()) {
                            updateHealthStatus(dialog, optionalTestStatus, msg);
                            if (neutral != null) {
                                neutral.setEnabled(true);
                            }
                            if (optionalTestButton != null) {
                                optionalTestButton.setEnabled(true);
                            }
                        }
                    });
                }
            });
        };

        if (optionalTestButton != null) {
            optionalTestButton.setOnClickListener(v -> runHealthCheck.run());
        }
        dialog.setOnShowListener(ignored -> {
            Button neutral = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            if (neutral != null) {
                neutral.setOnClickListener(v -> runHealthCheck.run());
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String url;
                try {
                    url = ApiClient.normalizeBaseUrl(field.getText().toString());
                } catch (IllegalArgumentException invalidUrl) {
                    field.setError("请输入有效的 HTTP 或 HTTPS 服务端地址。");
                    return;
                }
                sessions.changeServer(url);
                dialog.dismiss();
                TextView loginStatus = page.findViewById(R.id.login_status);
                if (loginStatus != null) {
                    setMessage(loginStatus, "服务端地址已保存。");
                }
            });
        });
        dialog.show();
    }

    private static void updateHealthStatus(AlertDialog dialog, TextView statusView, String message) {
        if (statusView != null) {
            statusView.setText(message);
            statusView.setVisibility(View.VISIBLE);
        }
        dialog.setMessage(message);
    }

    private static String formatServerMode(String mode) {
        if (mode == null || mode.isEmpty()) {
            return "";
        }
        if ("demo".equalsIgnoreCase(mode)) {
            return "Demo 演示模式";
        }
        if ("live".equalsIgnoreCase(mode)) {
            return "Live 真实模型";
        }
        return mode;
    }

    private void submitLogin(View loginPage, boolean register) {
        EditText nameField = loginPage.findViewById(R.id.username);
        EditText passwordField = loginPage.findViewById(R.id.password);
        TextView status = loginPage.findViewById(R.id.login_status);
        String username = nameField.getText().toString();
        String password = passwordField.getText().toString();
        if (username.isEmpty() || password.isEmpty()) {
            setMessage(status, "请输入用户名和密码。");
            return;
        }
        if (register && !username.matches("[A-Za-z][A-Za-z0-9_]{2,31}")) {
            setMessage(status, "用户名需为 3–32 位，以英文字母开头，仅可使用字母、数字或下划线。");
            return;
        }
        if (register && (password.length() < 8 || password.getBytes(StandardCharsets.UTF_8).length > 72
                || Character.isWhitespace(password.charAt(0))
                || Character.isWhitespace(password.charAt(password.length() - 1)))) {
            setMessage(status, "密码需至少 8 位，UTF-8 编码最多 72 字节，首尾不能有空白。");
            return;
        }
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(passwordField.getWindowToken(), 0);
        setLoginEnabled(loginPage, false);
        setMessage(status, register ? "正在注册…" : "正在登录…");
        String server = sessions.serverUrl();
        app.accounts.execute(() -> {
            ApiClient api = new ApiClient(server);
            boolean registered = false;
            boolean issued = false;
            try {
                if (register) {
                    api.register(username, password);
                    registered = true;
                }
                String token = api.login(username, password);
                issued = true;
                ApiClient.Student student = api.me(token);
                runOnUiThread(() -> {
                    if (isCurrent(loginPage) && server.equals(sessions.serverUrl())) {
                        Session verified = new Session(server, token, student);
                        sessions.save(verified);
                        passwordField.setText("");
                        openSession(verified, false);
                    }
                });
            } catch (ApiClient.Failure | IOException | JSONException failure) {
                boolean expired = issued && failure instanceof ApiClient.Failure http && http.status == 401;
                String reason = (registered ? "账号已注册，请登录。\n" : "")
                        + (expired ? SIGN_IN_AGAIN : failureMessage(failure));
                runOnUiThread(() -> {
                    if (isCurrent(loginPage)) {
                        setLoginEnabled(loginPage, true);
                        setMessage(status, reason);
                    }
                });
            }
        });
    }

    private static void setLoginEnabled(View view, boolean enabled) {
        for (int id : new int[]{R.id.username, R.id.password, R.id.login, R.id.register, R.id.server_settings}) {
            view.findViewById(id).setEnabled(enabled);
        }
    }

    void showHome() {
        currentScreen = Screen.HOME;
        practice = null;
        vocabularyFromReader = null;
        closeTranslation();
        closeWriting();
        View home = show(R.layout.screen_home);
        home.<TextView>findViewById(R.id.current_student).setText(session.student().username());
        home.findViewById(R.id.open_article).setOnClickListener(v -> showReader(example(), null));
        home.findViewById(R.id.open_vocabulary).setOnClickListener(v -> showVocabulary(null));
        home.findViewById(R.id.logout).setOnClickListener(v -> logout());
        home.findViewById(R.id.open_translation).setOnClickListener(v -> showTranslationSource());
        home.findViewById(R.id.open_writing).setOnClickListener(v -> showWriting());
        home.findViewById(R.id.capture_import).setOnClickListener(v -> pageImport.launch(true, PageImport.Target.READING));
        home.findViewById(R.id.pick_import).setOnClickListener(v -> pageImport.launch(false, PageImport.Target.READING));

        View serverCheck = UiViews.findOptionalView(home, "home_server_status");
        if (serverCheck != null) {
            serverCheck.setOnClickListener(v -> probeHomeServerHealth(home, session));
        }

        loadHomeData(home, session, home.findViewById(R.id.imported_articles));
    }

    private void probeHomeServerHealth(View home, Session account) {
        TextView status = home.findViewById(R.id.home_status);
        setMessage(status, "正在探测服务连接状态…");
        app.accounts.execute(() -> {
            try {
                ApiClient.Health health = new ApiClient(account.serverUrl()).health();
                String modeLabel = formatServerMode(health.mode());
                String verPart = health.version().isEmpty() ? "" : " (v" + health.version() + ")";
                String msg = "服务连接正常 · " + (modeLabel.isEmpty() ? health.status() : modeLabel) + verPart;
                runOnUiThread(() -> {
                    if (isCurrent(home)) {
                        setMessage(status, msg);
                    }
                });
            } catch (ApiClient.Failure | IOException | JSONException failure) {
                runOnUiThread(() -> {
                    if (isCurrent(home)) {
                        setMessage(status, "服务探测失败：" + failureMessage(failure));
                    }
                });
            }
        });
    }

    private TranslationPractice translation() {
        if (translation == null) {
            translation = new TranslationPractice(app.practiceRequests, session.owner(), session.token(),
                    drafts, this::savePracticeRecord);
        }
        return translation;
    }

    void showTranslationSource() {
        TranslationPractice exercise = translation();
        exercise.answering = false;
        TranslationScreen.showSource(this, exercise,
                camera -> pageImport.launch(camera, PageImport.Target.TRANSLATION));
    }

    void startTranslation(String correctedSource) {
        translation().begin(correctedSource);
        showTranslationAnswer();
    }

    void showTranslationAnswerFromHistory() {
        showTranslationAnswer();
    }

    void replaceTranslation(View page, String source, Runnable onReplaced) {
        TranslationPractice exercise = translation();
        Runnable replace = () -> {
            exercise.replace(source);
            onReplaced.run();
        };
        if (exercise.hasInput()) {
            confirmDraftChange(page, R.string.replace_practice, R.string.replace_translation_confirm,
                    R.string.replace_confirm, replace);
        } else {
            replace.run();
        }
    }

    void confirmDraftChange(View page, int title, int message, int button, Runnable change) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(button, (dialog, which) -> {
                    if (isCurrent(page)) {
                        change.run();
                    }
                }).show();
    }

    private void showTranslationAnswer() {
        Session account = session;
        TranslationScreen.showAnswer(this, translation, failure -> handleUnauthorized(account, failure));
    }

    private void closeTranslation() {
        if (translation != null) {
            translation.onChanged = null;
            translation.resetResponses();
            translation = null;
        }
    }

    void showWriting() {
        Session account = session;
        if (writing == null) {
            writing = new WritingPractice(app.practiceRequests, account.owner(), account.token(),
                    drafts, this::savePracticeRecord);
        }
        WritingScreen.show(this, writing, failure -> handleUnauthorized(account, failure));
    }

    private void closeWriting() {
        if (writing != null) {
            writing.onChanged = null;
            writing.cancel();
            writing = null;
        }
    }

    private void loadHomeData(View home, Session account, LinearLayout container) {
        TextView status = home.findViewById(R.id.articles_status);
        View retry = home.findViewById(R.id.articles_retry);
        setMessage(status, "正在读取材料…");
        retry.setVisibility(View.GONE);
        retry.setOnClickListener(v -> loadHomeData(home, account, container));
        long now = System.currentTimeMillis();
        app.storage.execute(() -> {
            try {
                List<ArticleDao.Summary> rows = app.articles().list(account.serverUrl(), account.student().id());
                int totalVocab = app.favorites().countAll(account.serverUrl(), account.student().id());
                int dueVocab = app.favorites().countDue(account.serverUrl(), account.student().id(), now);
                runOnUiThread(() -> {
                    if (!isCurrent(home)) {
                        return;
                    }
                    bindHomeStats(home, dueVocab, totalVocab, rows.size());
                    container.removeAllViews();
                    setMessage(status, rows.isEmpty() ? "还没有导入材料。" : null);
                    SimpleDateFormat dateFormat = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
                    for (ArticleDao.Summary article : rows) {
                        View row = getLayoutInflater().inflate(R.layout.row_article, container, false);
                        View clickTarget = row instanceof TextView ? row : row.findViewById(R.id.imported_article);
                        if (clickTarget instanceof TextView titleView) {
                            titleView.setText(article.title());
                        }
                        View openTarget = clickTarget != null ? clickTarget : row;
                        openTarget.setOnClickListener(v -> openArticle(home, article.articleId(), null));
                        View.OnLongClickListener confirmDelete = v -> {
                            confirmDeleteArticle(home, account, container, article);
                            return true;
                        };
                        row.setOnLongClickListener(confirmDelete);
                        if (openTarget != row) {
                            openTarget.setOnLongClickListener(confirmDelete);
                        }
                        TextView metaView = UiViews.findOptionalView(row, "article_row_meta", TextView.class);
                        if (metaView != null) {
                            String meta = article.lastReadAt() > 0
                                    ? "上次阅读 " + dateFormat.format(new Date(article.lastReadAt()))
                                    : "导入于 " + dateFormat.format(new Date(article.createdAt()));
                            metaView.setText(meta);
                        }
                        View deleteButton = UiViews.findOptionalView(row, "article_row_delete");
                        if (deleteButton != null) {
                            deleteButton.setOnClickListener(v -> confirmDeleteArticle(home, account, container, article));
                        }
                        container.addView(row);
                    }
                });
            } catch (RuntimeException failure) {
                runOnUiThread(() -> {
                    if (isCurrent(home)) {
                        setMessage(status, "无法读取本机材料。");
                        retry.setVisibility(View.VISIBLE);
                    }
                });
            }
        });
    }

    private static void bindHomeStats(View home, int dueCount, int totalVocab, int articlesCount) {
        TextView dueView = UiViews.findOptionalView(home, "home_stat_due_count", TextView.class);
        if (dueView != null) {
            dueView.setText(Integer.toString(dueCount));
        }
        TextView totalView = UiViews.findOptionalView(home, "home_stat_total_vocab", TextView.class);
        if (totalView != null) {
            totalView.setText(Integer.toString(totalVocab));
        }
        TextView articlesView = UiViews.findOptionalView(home, "home_stat_articles_count", TextView.class);
        if (articlesView != null) {
            articlesView.setText(Integer.toString(articlesCount));
        }
    }

    private void confirmDeleteArticle(View home, Session account, LinearLayout container, ArticleDao.Summary article) {
        new AlertDialog.Builder(this)
                .setTitle("删除导入文章")
                .setMessage("确定要删除「" + article.title() + "」吗？删除后无法恢复。")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("删除", (dialog, which) -> app.storage.execute(() -> {
                    try {
                        app.articles().delete(account.serverUrl(), account.student().id(), article.articleId());
                        runOnUiThread(() -> {
                            if (isCurrent(home)) {
                                loadHomeData(home, account, container);
                            }
                        });
                    } catch (RuntimeException failure) {
                        runOnUiThread(() -> {
                            if (isCurrent(home)) {
                                articleOpenError(home, "删除文章失败，请重试。");
                            }
                        });
                    }
                }))
                .show();
    }

    private ReadingArticle example() {
        return DemoArticle.open(getString(R.string.article_title));
    }

    private void openArticle(View source, String articleId, Favorite favorite) {
        if (!isCurrent(source)) {
            return;
        }
        if (DemoArticle.ID.equals(articleId)) {
            int savedScroll = favorite == null ? readerPrefs.getInt(demoScrollKey(session.owner()), 0) : 0;
            showReaderWithScroll(example(), favorite, savedScroll, this::showHome, R.string.back, false);
            return;
        }
        ArticleOwner owner = articleOwner();
        app.storage.execute(() -> {
            try {
                ImportedArticle article = app.articles().find(owner.serverUrl(), owner.studentId(), articleId);
                runOnUiThread(() -> {
                    if (isCurrent(source)) {
                        if (article == null) {
                            articleOpenError(source, "找不到这篇本机材料。");
                        } else {
                            int savedScroll = favorite == null ? article.lastScrollY : 0;
                            showReaderWithScroll(article.readingArticle(), favorite, savedScroll,
                                    this::showHome, R.string.back, false);
                        }
                    }
                });
            } catch (RuntimeException failure) {
                runOnUiThread(() -> {
                    if (isCurrent(source)) {
                        articleOpenError(source, "无法打开本机材料，请重试。");
                    }
                });
            }
        });
    }

    private static String demoScrollKey(ArticleOwner owner) {
        return "demo_scroll|" + owner.serverUrl() + "|" + owner.studentId();
    }

    private void articleOpenError(View source, String message) {
        TextView status = source.findViewById(R.id.home_status);
        if (status == null) {
            status = source.findViewById(R.id.vocabulary_status);
        }
        if (status != null) {
            setMessage(status, message);
        }
    }

    private void logout() {
        Session account = session;
        clearSession("已退出登录。");
        app.practiceRequests.execute(() -> {
            try {
                new ApiClient(account.serverUrl()).logout(account.token());
            } catch (ApiClient.Failure | IOException | JSONException ignored) {
                // Local sign-out already removed the credential and verified identity.
            }
        });
    }

    private boolean handleUnauthorized(Session account, Exception failure) {
        if (isActive(account) && failure instanceof ApiClient.Failure http
                && http.status == 401) {
            clearSession(SIGN_IN_AGAIN);
            return true;
        }
        return false;
    }

    void showReader(ReadingArticle article, Favorite favorite) {
        int savedScroll = 0;
        if (favorite == null && session != null && DemoArticle.ID.equals(article.id())) {
            savedScroll = readerPrefs.getInt(demoScrollKey(session.owner()), 0);
        }
        showReaderWithScroll(article, favorite, savedScroll, this::showHome, R.string.back, false);
    }

    void showReader(ReadingArticle article, Favorite favorite, Runnable backToPractice) {
        showReaderWithScroll(article, favorite, 0, backToPractice, R.string.cloze_return_to_round, true);
    }

    private void showReaderWithScroll(ReadingArticle article, Favorite favorite, int initialScrollY,
                                      Runnable onBack, int backLabel, boolean fromPractice) {
        currentScreen = Screen.READER;
        View readerView = show(R.layout.screen_reader, onBack);
        Button back = readerView.findViewById(R.id.reader_back);
        back.setText(backLabel);
        back.setOnClickListener(v -> onBack.run());
        Reader reader = new Reader(readerView, article, fromPractice);
        activeReader = reader;
        reader.open(favorite, initialScrollY, null);
    }

    private void restoreReaderSnapshot(ReaderSnapshot snap) {
        boolean backToPractice = snap.fromPractice() && practice != null;
        Runnable onBack = backToPractice ? this::showPractice : this::showHome;
        int backLabel = backToPractice ? R.string.cloze_return_to_round : R.string.back;
        currentScreen = Screen.READER;
        View readerView = show(R.layout.screen_reader, onBack);
        Button back = readerView.findViewById(R.id.reader_back);
        back.setText(backLabel);
        back.setOnClickListener(v -> onBack.run());
        Reader reader = new Reader(readerView, snap.article(), backToPractice);
        activeReader = reader;
        reader.open(null, snap.scrollY(), snap);
    }

    private final class Reader {
        private record ParagraphView(ReadingArticle.Paragraph paragraph, WordTextView textView) {}

        private final View view;
        private final ReadingArticle article;
        private final boolean fromPractice;
        private final List<ParagraphView> paragraphs = new ArrayList<>();
        private final View panel;
        private final TextView title;
        private final TextView mode;
        private final TextView meaning;
        private final TextView detail;
        private final TextView phoneticPos;
        private final View speakButton;
        private final Button action;
        private final ScrollView panelScroll;
        private final ScrollView articleScroll;
        private final View wordActions;
        private final View analyze;
        private final View backToWord;
        private final ScrollView sentenceScroll;
        private final SentenceAnalysisPanel sentencePanel;
        private final View scrollProgressView;
        private final View fontToggle;
        private final Runnable saveScrollTask = this::persistScrollNow;
        private WordTextView marked;
        private ReadingArticle.Selection currentSelection;
        private ApiClient.Explanation lastExplanation;
        private SentenceAnalysis lastAnalysis;
        private int revision;
        private int analysisRevision;
        private int readingPosition;
        private int lastSavedScroll = -1;
        private boolean analysisOpen;

        Reader(View view, ReadingArticle article, boolean fromPractice) {
            this.view = view;
            this.article = article;
            this.fromPractice = fromPractice;
            panel = view.findViewById(R.id.explanation_panel);
            title = view.findViewById(R.id.panel_selection);
            mode = view.findViewById(R.id.panel_mode);
            meaning = view.findViewById(R.id.panel_meaning);
            detail = view.findViewById(R.id.panel_detail);
            phoneticPos = UiViews.findOptionalView(view, "reader_word_phonetic_pos", TextView.class);
            speakButton = UiViews.findOptionalView(view, "reader_word_speak");
            action = view.findViewById(R.id.panel_action);
            panelScroll = view.findViewById(R.id.panel_scroll);
            articleScroll = view.findViewById(R.id.article_scroll);
            wordActions = view.findViewById(R.id.panel_word_actions);
            analyze = view.findViewById(R.id.panel_analyze);
            backToWord = view.findViewById(R.id.panel_back_to_word);
            sentenceScroll = view.findViewById(R.id.sentence_scroll);
            sentencePanel = new SentenceAnalysisPanel(sentenceScroll);
            sentencePanel.setOnSpeak(MainActivity.this::speakEnglish);
            scrollProgressView = UiViews.findOptionalView(view, "reader_scroll_progress");
            fontToggle = UiViews.findOptionalView(view, "reader_font_toggle");
        }

        ReaderSnapshot snapshot() {
            int currentScrollY = articleScroll.getScrollY();
            if (currentScrollY == 0 && readingPosition > 0) {
                currentScrollY = readingPosition;
            }
            boolean panelShowing = panel.getVisibility() == View.VISIBLE && currentSelection != null;
            return new ReaderSnapshot(
                    article,
                    fromPractice,
                    currentScrollY,
                    panelShowing ? currentSelection.start() : -1,
                    panelShowing ? currentSelection.end() : -1,
                    panelShowing && analysisOpen,
                    panelShowing ? lastExplanation : null,
                    panelShowing && analysisOpen ? lastAnalysis : null);
        }

        void detach() {
            main.removeCallbacks(saveScrollTask);
        }

        void flushProgress() {
            main.removeCallbacks(saveScrollTask);
            persistScrollNow();
        }

        private void persistScrollNow() {
            Session account = session;
            if (account == null) {
                return;
            }
            int y = articleScroll.getScrollY();
            if (y == 0 && readingPosition > 0) {
                y = readingPosition;
            }
            if (y == lastSavedScroll && y == 0) {
                return;
            }
            lastSavedScroll = y;
            int scrollToSave = y;
            if (DemoArticle.ID.equals(article.id())) {
                readerPrefs.edit().putInt(demoScrollKey(account.owner()), scrollToSave).apply();
                return;
            }
            long now = System.currentTimeMillis();
            app.storage.execute(() -> {
                try {
                    app.articles().updateProgress(account.serverUrl(), account.student().id(),
                            article.id(), now, scrollToSave);
                } catch (RuntimeException ignored) {
                    // Best-effort reading progress persistence.
                }
            });
        }

        void open(Favorite favorite, int initialScrollY, ReaderSnapshot restoreSnap) {
            view.<TextView>findViewById(R.id.reader_title).setText(article.title());
            LinearLayout container = view.findViewById(R.id.article_paragraphs);
            int fontTier = getFontTier();
            float fontSizeSp = READER_FONT_SP[fontTier];
            for (ReadingArticle.Paragraph paragraph : article.paragraphs()) {
                WordTextView textView = (WordTextView) getLayoutInflater()
                        .inflate(R.layout.reader_paragraph, container, false);
                textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp);
                paragraphs.add(new ParagraphView(paragraph, textView));
                textView.setParagraph(paragraph.text(), (start, end) -> select(paragraph, start, end));
                container.addView(textView);
            }
            if (fontToggle != null) {
                updateFontToggleLabel(fontTier);
                fontToggle.setOnClickListener(v -> cycleFontSize());
            }
            view.findViewById(R.id.reader_vocabulary).setOnClickListener(v -> showVocabulary(article));
            Runnable closePanel = () -> {
                revision++;
                analysisRevision++;
                cancelPendingAnalysis();
                if (pendingLookup != null) {
                    pendingLookup.cancel(true);
                    pendingLookup = null;
                }
                currentSelection = null;
                lastExplanation = null;
                lastAnalysis = null;
                analysisOpen = false;
                clearMark();
                panel.setVisibility(View.GONE);
                articleScroll.post(() -> {
                    if (isCurrent(view) && panel.getVisibility() == View.GONE) {
                        articleScroll.scrollTo(0, readingPosition);
                        updateScrollProgress(readingPosition);
                    }
                });
            };
            view.findViewById(R.id.panel_close).setOnClickListener(v -> closePanel.run());
            View optionalClose = UiViews.findOptionalView(view, "reader_word_close");
            if (optionalClose != null) {
                optionalClose.setOnClickListener(v -> closePanel.run());
            }

            title.setOnClickListener(v -> speakCurrentTarget());
            if (speakButton != null) {
                speakButton.setOnClickListener(v -> speakCurrentTarget());
            }

            articleScroll.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
                if (isCurrent(view)) {
                    readingPosition = scrollY;
                    updateScrollProgress(scrollY);
                    main.removeCallbacks(saveScrollTask);
                    main.postDelayed(saveScrollTask, SCROLL_SAVE_DEBOUNCE_MS);
                }
            });

            view.addOnLayoutChangeListener((v, left, top, right, bottom, pl, pt, pr, pb) -> {
                if (right - left != pr - pl || bottom - top != pb - pt) {
                    resizePanel();
                    updateScrollProgress(articleScroll.getScrollY());
                }
            });

            refreshSavedHighlights();

            if (favorite != null) {
                int start = favorite.startOffset;
                int end = favorite.endOffset;
                if (start >= 0 && start < end && end <= article.body().length()
                        && article.body().substring(start, end).equals(favorite.selection)) {
                    ParagraphView target = mark(start, end);
                    if (target != null) {
                        reveal(target.textView(), start - target.paragraph().start());
                    }
                }
            } else if (initialScrollY > 0) {
                readingPosition = initialScrollY;
                articleScroll.post(() -> {
                    if (isCurrent(view)) {
                        articleScroll.scrollTo(0, initialScrollY);
                        updateScrollProgress(initialScrollY);
                    }
                });
            } else {
                articleScroll.post(() -> {
                    if (isCurrent(view)) {
                        updateScrollProgress(articleScroll.getScrollY());
                    }
                });
            }

            if (restoreSnap != null && restoreSnap.selectedStart() >= 0
                    && restoreSnap.selectedEnd() > restoreSnap.selectedStart()) {
                restoreSelection(restoreSnap);
            }
        }

        private void restoreSelection(ReaderSnapshot snap) {
            for (ParagraphView item : paragraphs) {
                ReadingArticle.Paragraph p = item.paragraph();
                if (snap.selectedStart() >= p.start() && snap.selectedEnd() <= p.end()) {
                    ReadingArticle.Selection sel = p.select(
                            snap.selectedStart() - p.start(), snap.selectedEnd() - p.start());
                    if (sel != null) {
                        currentSelection = sel;
                        mark(sel.start(), sel.end());
                        if (snap.explanation() != null) {
                            lastExplanation = snap.explanation();
                            showWordPanel(sel);
                            renderExplanation(snap.explanation());
                            Session account = session;
                            int reqRev = revision;
                            panelButton(getString(R.string.save_word),
                                    () -> favorite(account, reqRev, sel, snap.explanation()));
                            if (snap.analysisOpen() && sel.sentence() != null) {
                                if (snap.analysis() != null) {
                                    lastAnalysis = snap.analysis();
                                    showSentencePanelCached(sel, snap.analysis());
                                } else {
                                    showSentencePanel(sel);
                                }
                            }
                        } else {
                            request(sel);
                            if (snap.analysisOpen() && sel.sentence() != null) {
                                showSentencePanel(sel);
                            }
                        }
                    }
                    break;
                }
            }
        }

        private void speakCurrentTarget() {
            if (currentSelection == null) {
                return;
            }
            if (analysisOpen && currentSelection.sentence() != null) {
                speakEnglish(currentSelection.sentence());
            } else {
                speakEnglish(currentSelection.text());
            }
        }

        private int getFontTier() {
            int tier = readerPrefs.getInt("font_tier", 0);
            if (tier < 0 || tier >= READER_FONT_SP.length) {
                return 0;
            }
            return tier;
        }

        private void cycleFontSize() {
            int nextTier = (getFontTier() + 1) % READER_FONT_SP.length;
            readerPrefs.edit().putInt("font_tier", nextTier).apply();
            float sizeSp = READER_FONT_SP[nextTier];
            for (ParagraphView item : paragraphs) {
                item.textView().setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
            }
            updateFontToggleLabel(nextTier);
            articleScroll.post(() -> {
                if (isCurrent(view)) {
                    updateScrollProgress(articleScroll.getScrollY());
                }
            });
        }

        private void updateFontToggleLabel(int tier) {
            if (fontToggle instanceof TextView labelView) {
                labelView.setText(READER_FONT_LABELS[tier]);
            }
        }

        private void updateScrollProgress(int scrollY) {
            if (scrollProgressView == null) {
                return;
            }
            View child = articleScroll.getChildAt(0);
            int maxScroll = child == null ? 0 : Math.max(0, child.getHeight() - articleScroll.getHeight());
            int percent = maxScroll <= 0 ? 100 : Math.min(100, Math.max(0, Math.round(scrollY * 100f / maxScroll)));
            if (scrollProgressView instanceof ProgressBar progressBar) {
                progressBar.setMax(100);
                progressBar.setProgress(percent);
            } else if (scrollProgressView instanceof TextView textView) {
                textView.setText(percent + "%");
            }
        }

        private void refreshSavedHighlights() {
            Session account = session;
            if (account == null) {
                return;
            }
            app.storage.execute(() -> {
                try {
                    List<Favorite> saved = app.favorites().listForArticle(
                            account.serverUrl(), account.student().id(), article.id());
                    runOnUiThread(() -> {
                        if (!isCurrent(view)) {
                            return;
                        }
                        for (ParagraphView item : paragraphs) {
                            ReadingArticle.Paragraph p = item.paragraph();
                            List<int[]> ranges = new ArrayList<>();
                            for (Favorite fav : saved) {
                                if (fav.startOffset >= p.start() && fav.endOffset <= p.end()) {
                                    ranges.add(new int[]{fav.startOffset - p.start(), fav.endOffset - p.start()});
                                }
                            }
                            item.textView().setSavedRanges(ranges);
                        }
                    });
                } catch (RuntimeException ignored) {
                    // Highlighting saved words is a progressive enhancement.
                }
            });
        }

        private void reveal(WordTextView wordView, int offset) {
            wordView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                           int oldLeft, int oldTop, int oldRight, int oldBottom) {
                    wordView.removeOnLayoutChangeListener(this);
                    wordView.post(() -> {
                        Layout layout = wordView.getLayout();
                        if (isCurrent(view) && layout != null) {
                            int line = layout.getLineForOffset(offset);
                            int padding = wordView.getTotalPaddingTop();
                            Rect rectangle = new Rect(0, padding + layout.getLineTop(line),
                                    wordView.getWidth(), padding + layout.getLineBottom(line));
                            wordView.requestRectangleOnScreen(rectangle, true);
                        }
                    });
                }
            });
        }

        private void select(ReadingArticle.Paragraph paragraph, int start, int end) {
            if (isCurrent(view)) {
                ReadingArticle.Selection selection = paragraph.select(start, end);
                if (selection != null) {
                    readingPosition = articleScroll.getScrollY();
                    request(selection);
                }
            }
        }

        private void clearMark() {
            if (marked != null) {
                marked.mark(-1, -1);
                marked = null;
            }
        }

        private ParagraphView mark(int start, int end) {
            clearMark();
            for (ParagraphView item : paragraphs) {
                ReadingArticle.Paragraph paragraph = item.paragraph();
                if (start >= paragraph.start() && end <= paragraph.end()) {
                    marked = item.textView();
                    marked.mark(start - paragraph.start(), end - paragraph.start());
                    return item;
                }
            }
            return null;
        }

        private void request(ReadingArticle.Selection selection) {
            Session account = session;
            currentSelection = selection;
            lastExplanation = null;
            lastAnalysis = null;
            int requestRevision = ++revision;
            if (pendingLookup != null) {
                pendingLookup.cancel(true);
            }
            mark(selection.start(), selection.end());
            showWordPanel(selection);
            if (selection.sentence() == null) {
                render(null, false, "请在同一句中选择单词或词组。");
                return;
            }
            String cacheKey = StudyApplication.explanationCacheKey(
                    account.serverUrl(), selection.text(), selection.sentence());
            ApiClient.Explanation cached = app.explanationCache.get(cacheKey);
            if (cached != null) {
                lastExplanation = cached;
                renderExplanation(cached);
                panelButton(getString(R.string.save_word),
                        () -> favorite(account, requestRevision, selection, cached));
                return;
            }
            render(null, false, "正在查询…");
            pendingLookup = app.lookups.submit(() -> {
                try {
                    ApiClient.Explanation result = new ApiClient(account.serverUrl())
                            .explain(account.token(), selection.text(), selection.sentence());
                    app.explanationCache.put(cacheKey, result);
                    runOnUiThread(() -> {
                        if (current(requestRevision)) {
                            lastExplanation = result;
                            renderExplanation(result);
                            panelButton(getString(R.string.save_word),
                                    () -> favorite(account, requestRevision, selection, result));
                        }
                    });
                } catch (ApiClient.Failure | IOException | JSONException failure) {
                    runOnUiThread(() -> {
                        if (handleUnauthorized(account, failure) || !current(requestRevision)) {
                            return;
                        }
                        boolean unsupported = failure instanceof ApiClient.Failure http
                                && (http.status == 422 || "DEMO_UNSUPPORTED".equals(http.code));
                        render(null, unsupported, unsupported
                                ? "演示模式暂未收录该选词。" : failureMessage(failure));
                        if (!unsupported) {
                            panelButton(getString(R.string.retry), () -> request(selection));
                        }
                    });
                }
            });
        }

        private void renderExplanation(ApiClient.Explanation result) {
            String meta = result.formattedMeta();
            if (phoneticPos != null) {
                phoneticPos.setText(meta);
                phoneticPos.setVisibility(meta.isEmpty() ? View.GONE : View.VISIBLE);
                render(result, false, result.detail());
            } else {
                String combinedDetail = meta.isEmpty() ? result.detail() : meta + "\n" + result.detail();
                render(result, false, combinedDetail);
            }
        }

        private void showWordPanel(ReadingArticle.Selection selection) {
            analysisRevision++;
            cancelPendingAnalysis();
            analysisOpen = false;
            title.setText(selection.text());
            panelScroll.setVisibility(View.VISIBLE);
            wordActions.setVisibility(View.VISIBLE);
            sentenceScroll.setVisibility(View.GONE);
            backToWord.setVisibility(View.GONE);
            analyze.setVisibility(selection.sentence() == null ? View.GONE : View.VISIBLE);
            analyze.setOnClickListener(v -> {
                if (isCurrent(view) && panel.getVisibility() == View.VISIBLE) {
                    showSentencePanel(selection);
                }
            });
            resizePanel();
        }

        private void showSentencePanel(ReadingArticle.Selection selection) {
            analysisOpen = true;
            title.setText(R.string.sentence_analysis_title);
            panelScroll.setVisibility(View.GONE);
            wordActions.setVisibility(View.GONE);
            sentenceScroll.setVisibility(View.VISIBLE);
            backToWord.setVisibility(View.VISIBLE);
            backToWord.setOnClickListener(v -> showWordPanel(selection));
            requestAnalysis(selection.sentence());
        }

        private void showSentencePanelCached(ReadingArticle.Selection selection, SentenceAnalysis cached) {
            analysisOpen = true;
            title.setText(R.string.sentence_analysis_title);
            panelScroll.setVisibility(View.GONE);
            wordActions.setVisibility(View.GONE);
            sentenceScroll.setVisibility(View.VISIBLE);
            backToWord.setVisibility(View.VISIBLE);
            backToWord.setOnClickListener(v -> showWordPanel(selection));
            panel.setVisibility(View.VISIBLE);
            sentencePanel.show(cached);
            resizePanel();
        }

        private void requestAnalysis(String sentence) {
            Session account = session;
            int requestRevision = ++analysisRevision;
            cancelPendingAnalysis();
            String cacheKey = StudyApplication.analysisCacheKey(account.serverUrl(), sentence);
            SentenceAnalysis cached = app.analysisCache.get(cacheKey);
            if (cached != null) {
                lastAnalysis = cached;
                sentencePanel.show(cached);
                resizePanel();
                return;
            }
            sentencePanel.loading(sentence);
            resizePanel();
            pendingAnalysis = app.lookups.submit(() -> {
                try {
                    SentenceAnalysis result = new ApiClient(account.serverUrl()).analyze(account.token(), sentence);
                    app.analysisCache.put(cacheKey, result);
                    runOnUiThread(() -> {
                        if (currentAnalysis(requestRevision)) {
                            lastAnalysis = result;
                            sentencePanel.show(result);
                            resizePanel();
                        }
                    });
                } catch (ApiClient.Failure | IOException | JSONException failure) {
                    runOnUiThread(() -> {
                        if (handleUnauthorized(account, failure) || !currentAnalysis(requestRevision)) {
                            return;
                        }
                        sentencePanel.error(analysisError(failure), () -> requestAnalysis(sentence));
                        resizePanel();
                    });
                }
            });
        }

        private boolean currentAnalysis(int requestRevision) {
            return isCurrent(view) && analysisOpen && analysisRevision == requestRevision;
        }

        private String analysisError(Exception failure) {
            if (failure instanceof ApiClient.Failure http) {
                if (http.status == 422 && "DEMO_UNSUPPORTED".equals(http.code)) {
                    return getString(R.string.analysis_unsupported);
                }
                if (http.status == 504) {
                    return getString(R.string.analysis_timeout);
                }
            }
            return failure instanceof SocketTimeoutException
                    ? getString(R.string.analysis_timeout) : failureMessage(failure);
        }

        private boolean current(int requestRevision) {
            return isCurrent(view) && revision == requestRevision;
        }

        private void render(ApiClient.Explanation result, boolean unsupported, String message) {
            mode.setVisibility(unsupported || (result != null && "demo".equals(result.mode()))
                    ? View.VISIBLE : View.GONE);
            if (result == null && phoneticPos != null) {
                phoneticPos.setVisibility(View.GONE);
            }
            meaning.setVisibility(result == null ? View.GONE : View.VISIBLE);
            meaning.setText(result == null ? "" : result.meaning());
            detail.setText(message);
            action.setVisibility(View.GONE);
            panel.setVisibility(View.VISIBLE);
            panelScroll.scrollTo(0, 0);
            resizePanel();
        }

        private void resizePanel() {
            panel.post(() -> {
                if (!isCurrent(view) || panel.getVisibility() != View.VISIBLE) {
                    return;
                }
                int width = view.getWidth() - view.getPaddingLeft() - view.getPaddingRight()
                        - panel.getPaddingLeft() - panel.getPaddingRight();
                if (width <= 0) {
                    return;
                }
                ScrollView scroll = analysisOpen ? sentenceScroll : panelScroll;
                View content = scroll.getChildAt(0);
                content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                int maxHeight = analysisOpen ? view.getHeight() * 3 / 5 : view.getHeight() / 3;
                int height = Math.min(content.getMeasuredHeight(), maxHeight);
                ViewGroup.LayoutParams params = scroll.getLayoutParams();
                if (params.height != height) {
                    params.height = height;
                    scroll.setLayoutParams(params);
                }
            });
        }

        private void panelButton(String label, Runnable onClick) {
            action.setText(label);
            action.setEnabled(onClick != null);
            action.setOnClickListener(onClick == null ? null : v -> onClick.run());
            action.setVisibility(View.VISIBLE);
        }

        private void favorite(Session account, int requestRevision, ReadingArticle.Selection selection,
                              ApiClient.Explanation result) {
            Favorite row = new Favorite(account.serverUrl(), account.student().id(), article.id(),
                    selection.start(), selection.end(), selection.text(), selection.sentence(),
                    result.meaning(), result.detail(), result.mode(), System.currentTimeMillis(),
                    result.phonetic(), result.partOfSpeech());
            panelButton("正在收藏…", null);
            app.storage.execute(() -> {
                try {
                    app.favorites().insert(row);
                    runOnUiThread(() -> {
                        if (current(requestRevision)) {
                            panelButton("已收藏", null);
                            refreshSavedHighlights();
                        }
                    });
                } catch (RuntimeException failure) {
                    runOnUiThread(() -> {
                        if (current(requestRevision)) {
                            panelButton("收藏失败，重试", () -> favorite(account, requestRevision, selection, result));
                        }
                    });
                }
            });
        }
    }

    private void showVocabulary(ReadingArticle fromReader) {
        currentScreen = Screen.VOCABULARY;
        vocabularyFromReader = fromReader;
        practice = null;
        Session account = session;
        View vocabulary = show(R.layout.screen_vocabulary);
        vocabulary.<TextView>findViewById(R.id.vocabulary_student).setText(account.student().username());
        Runnable returnBack = fromReader == null ? this::showHome : () -> showReader(fromReader, null);
        backAction = returnBack;
        vocabulary.findViewById(R.id.vocabulary_back).setOnClickListener(v -> returnBack.run());
        View start = vocabulary.findViewById(R.id.cloze_start);
        TextView status = vocabulary.findViewById(R.id.vocabulary_status);
        View retry = vocabulary.findViewById(R.id.vocabulary_retry);
        EditText searchInput = UiViews.findOptionalView(vocabulary, "vocabulary_search_input", EditText.class);
        TextView summaryStats = UiViews.findOptionalView(vocabulary, "vocabulary_summary_stats", TextView.class);
        ListView listView = vocabulary.findViewById(R.id.vocabulary_list);
        retry.setOnClickListener(v -> showVocabulary(fromReader));
        setMessage(status, "正在读取收藏…");
        app.storage.execute(() -> {
            try {
                List<FavoriteDao.Row> rows = app.favorites().list(account.serverUrl(), account.student().id());
                runOnUiThread(() -> {
                    if (isCurrent(vocabulary)) {
                        List<FavoriteDao.Row> allRows = new ArrayList<>(rows);
                        FavoritesAdapter adapter = new FavoritesAdapter(vocabulary, allRows,
                                rowToDelete -> confirmDeleteFavorite(vocabulary, allRows, rowToDelete,
                                        status, summaryStats, start, searchInput));
                        listView.setAdapter(adapter);
                        Runnable refreshUi = () -> {
                            adapter.filter(vocabularyQuery);
                            updateVocabularyStats(summaryStats, allRows);
                            start.setEnabled(!allRows.isEmpty());
                            if (allRows.isEmpty()) {
                                setMessage(status, getString(R.string.cloze_empty));
                            } else if (adapter.getCount() == 0) {
                                setMessage(status, "未找到匹配的生词。");
                            } else {
                                setMessage(status, null);
                            }
                        };
                        if (searchInput != null) {
                            searchInput.setText(vocabularyQuery);
                            searchInput.setSelection(searchInput.length());
                            searchInput.addTextChangedListener(new TextWatcher() {
                                @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
                                @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                                    if (isCurrent(vocabulary)) {
                                        vocabularyQuery = s.toString();
                                        refreshUi.run();
                                    }
                                }
                                @Override public void afterTextChanged(Editable s) {}
                            });
                        }
                        refreshUi.run();
                        start.setOnClickListener(v -> {
                            if (isCurrent(vocabulary) && !allRows.isEmpty()) {
                                startPractice(account.owner(), allRows, fromReader);
                            }
                        });
                    }
                });
            } catch (RuntimeException failure) {
                runOnUiThread(() -> {
                    if (isCurrent(vocabulary)) {
                        setMessage(status, "无法读取本机收藏。");
                        retry.setVisibility(View.VISIBLE);
                    }
                });
            }
        });
    }

    private void confirmDeleteFavorite(View vocabulary, List<FavoriteDao.Row> allRows, FavoriteDao.Row target,
                                       TextView status, TextView summaryStats, View start, EditText searchInput) {
        Favorite fav = target.favorite;
        new AlertDialog.Builder(this)
                .setTitle("删除生词")
                .setMessage("确定要从生词本中移除「" + fav.selection + "」吗？")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("删除", (dialog, which) -> app.storage.execute(() -> {
                    try {
                        app.favorites().delete(fav.serverUrl, fav.studentId, fav.articleId,
                                fav.startOffset, fav.endOffset);
                        runOnUiThread(() -> {
                            if (isCurrent(vocabulary)) {
                                allRows.remove(target);
                                ListView listView = vocabulary.findViewById(R.id.vocabulary_list);
                                if (listView.getAdapter() instanceof FavoritesAdapter adapter) {
                                    adapter.filter(vocabularyQuery);
                                    updateVocabularyStats(summaryStats, allRows);
                                    start.setEnabled(!allRows.isEmpty());
                                    if (allRows.isEmpty()) {
                                        setMessage(status, getString(R.string.cloze_empty));
                                    } else if (adapter.getCount() == 0) {
                                        setMessage(status, "未找到匹配的生词。");
                                    } else {
                                        setMessage(status, null);
                                    }
                                }
                            }
                        });
                    } catch (RuntimeException failure) {
                        runOnUiThread(() -> {
                            if (isCurrent(vocabulary)) {
                                setMessage(status, "删除生词失败，请重试。");
                            }
                        });
                    }
                }))
                .show();
    }

    private static void updateVocabularyStats(TextView summaryStats, List<FavoriteDao.Row> allRows) {
        if (summaryStats == null) {
            return;
        }
        long now = System.currentTimeMillis();
        int total = allRows.size();
        int due = 0;
        int mastered = 0;
        for (FavoriteDao.Row row : allRows) {
            if (row.favorite.nextReviewAt <= now) {
                due++;
            }
            if (row.favorite.masteryLevel >= 3) {
                mastered++;
            }
        }
        summaryStats.setText("共 " + total + " 词 · 待复习 " + due + " · 已掌握 " + mastered);
    }

    private void startPractice(ArticleOwner owner, List<FavoriteDao.Row> rows, ReadingArticle fromReader) {
        Runnable backToVocabulary = () -> showVocabulary(fromReader);
        View loading = showClozeScreen(R.layout.screen_status, backToVocabulary);
        loading.<TextView>findViewById(R.id.status_message).setText(R.string.cloze_loading);
        ReadingArticle builtIn = example();
        List<Favorite> favorites = new ArrayList<>(rows.size());
        for (FavoriteDao.Row row : rows) {
            favorites.add(row.favorite);
        }
        app.storage.execute(() -> {
            try {
                Map<String, ReadingArticle> sources = new HashMap<>();
                sources.put(builtIn.id(), builtIn);
                ClozeRound.Creation creation = ClozeRound.create(favorites, articleId -> {
                    ReadingArticle article = sources.get(articleId);
                    if (article == null) {
                        ImportedArticle imported = app.articles().find(
                                owner.serverUrl(), owner.studentId(), articleId);
                        if (imported != null) {
                            article = imported.readingArticle();
                            sources.put(article.id(), article);
                        }
                    }
                    return article;
                });
                runOnUiThread(() -> {
                    if (isCurrent(loading)) {
                        if (creation.round() == null) {
                            showPracticeError(loading, R.string.cloze_bad_source, backToVocabulary);
                        } else {
                            practice = new Practice(creation.round(), fromReader);
                            showPractice();
                        }
                    }
                });
            } catch (RuntimeException failure) {
                runOnUiThread(() -> showPracticeError(loading, R.string.cloze_load_error, backToVocabulary));
            }
        });
    }

    private void showPractice() {
        Practice current = practice;
        new ClozeScreen(this, current.round(), () -> showVocabulary(current.fromReader())).show();
    }

    private void showPracticeError(View loading, int message, Runnable backToVocabulary) {
        if (isCurrent(loading)) {
            loading.findViewById(R.id.status_progress).setVisibility(View.GONE);
            loading.<TextView>findViewById(R.id.status_message).setText(message);
            Button back = loading.findViewById(R.id.status_retry);
            back.setText(R.string.cloze_back);
            back.setVisibility(View.VISIBLE);
            back.setOnClickListener(v -> backToVocabulary.run());
        }
    }

    private final class FavoritesAdapter extends BaseAdapter {
        private final View vocabulary;
        private final List<FavoriteDao.Row> allRows;
        private final List<FavoriteDao.Row> filteredRows = new ArrayList<>();
        private final Consumer<FavoriteDao.Row> onDelete;

        FavoritesAdapter(View vocabulary, List<FavoriteDao.Row> allRows, Consumer<FavoriteDao.Row> onDelete) {
            this.vocabulary = vocabulary;
            this.allRows = allRows;
            this.onDelete = onDelete;
            this.filteredRows.addAll(allRows);
        }

        void filter(String query) {
            filteredRows.clear();
            String normalized = query == null ? "" : ReadingArticle.trimSpace(query).toLowerCase(Locale.ROOT);
            if (normalized.isEmpty()) {
                filteredRows.addAll(allRows);
            } else {
                for (FavoriteDao.Row entry : allRows) {
                    Favorite fav = entry.favorite;
                    String title = DemoArticle.ID.equals(fav.articleId)
                            ? getString(R.string.article_title) : (entry.articleTitle == null ? "" : entry.articleTitle);
                    if (containsIgnoreCase(fav.selection, normalized)
                            || containsIgnoreCase(fav.meaning, normalized)
                            || containsIgnoreCase(fav.sentence, normalized)
                            || containsIgnoreCase(title, normalized)) {
                        filteredRows.add(entry);
                    }
                }
            }
            notifyDataSetChanged();
        }

        private boolean containsIgnoreCase(String text, String needle) {
            return text != null && text.toLowerCase(Locale.ROOT).contains(needle);
        }

        @Override
        public int getCount() {
            return filteredRows.size();
        }

        @Override
        public FavoriteDao.Row getItem(int position) {
            return filteredRows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView == null
                    ? getLayoutInflater().inflate(R.layout.row_favorite, parent, false) : convertView;
            FavoriteDao.Row entry = getItem(position);
            Favorite favorite = entry.favorite;
            row.<TextView>findViewById(R.id.favorite_term).setText(favorite.selection);
            row.<TextView>findViewById(R.id.favorite_meaning).setText(favorite.meaning);
            row.<TextView>findViewById(R.id.favorite_sentence).setText(favorite.sentence);
            String articleTitle = DemoArticle.ID.equals(favorite.articleId)
                    ? getString(R.string.article_title) : entry.articleTitle;
            setMessage(row.findViewById(R.id.favorite_article), articleTitle);

            TextView phoneticView = UiViews.findOptionalView(row, "favorite_row_phonetic", TextView.class);
            if (phoneticView != null) {
                String meta = formatFavoriteMeta(favorite);
                phoneticView.setText(meta);
                phoneticView.setVisibility(meta.isEmpty() ? View.GONE : View.VISIBLE);
            }

            TextView masteryView = UiViews.findOptionalView(row, "favorite_row_mastery", TextView.class);
            if (masteryView != null) {
                masteryView.setText(formatMasteryLabel(favorite));
                masteryView.setVisibility(View.VISIBLE);
            }

            View deleteButton = UiViews.findOptionalView(row, "favorite_row_delete");
            if (deleteButton != null) {
                deleteButton.setOnClickListener(v -> onDelete.accept(entry));
            }
            row.setOnLongClickListener(v -> {
                onDelete.accept(entry);
                return true;
            });

            row.findViewById(R.id.favorite_jump)
                    .setOnClickListener(v -> openArticle(vocabulary, favorite.articleId, favorite));
            return row;
        }
    }

    private static String formatFavoriteMeta(Favorite favorite) {
        StringBuilder sb = new StringBuilder();
        if (favorite.phonetic != null && !favorite.phonetic.isEmpty()) {
            sb.append(favorite.phonetic);
        }
        if (favorite.partOfSpeech != null && !favorite.partOfSpeech.isEmpty()) {
            if (sb.length() > 0) {
                sb.append("  ");
            }
            sb.append(favorite.partOfSpeech);
        }
        return sb.toString();
    }

    private static String formatMasteryLabel(Favorite favorite) {
        String level = switch (favorite.masteryLevel) {
            case 1 -> "初识";
            case 2 -> "熟悉";
            case 3 -> "已掌握";
            default -> "未复习";
        };
        boolean due = favorite.nextReviewAt <= System.currentTimeMillis();
        return due ? level + " · 待复习" : level;
    }

    private static void setMessage(TextView view, String message) {
        view.setText(message);
        view.setVisibility(message == null ? View.GONE : View.VISIBLE);
    }

    static String failureMessage(Exception failure) {
        if (failure instanceof ApiClient.Failure http) {
            return http.getMessage();
        }
        return failure instanceof IOException ? CONNECTION_ERROR : INVALID_RESPONSE;
    }
}
