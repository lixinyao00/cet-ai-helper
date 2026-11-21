package com.cethelper.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
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
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONException;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.cethelper.android.SessionStore.Session;

public final class MainActivity extends Activity {
    private static final long REVERIFY_DELAY_MS = 10_000;
    private static final String SIGN_IN_AGAIN = "会话已失效，请重新登录。";
    private static final String CONNECTION_ERROR = "无法连接服务，请检查网络和服务端地址。";
    private static final String INVALID_RESPONSE = "服务返回了无法读取的数据。";

    private record Practice(ClozeRound round, ReadingArticle fromReader) {}

    private record RetainedPractice(Session session, boolean offline, Practice cloze,
                                    TranslationPractice translation, WritingPractice writing) {}

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable reverify = this::reverifySession;
    private final ExecutorService lookups = Executors.newFixedThreadPool(3);
    private StudyApplication app;
    private PageImport pageImport;
    private SessionStore sessions;
    private DraftStore drafts;
    private FrameLayout screen;
    private TextView sessionBanner;
    private View page;
    private Runnable backAction;
    private Session session;
    private Practice practice;
    private TranslationPractice translation;
    private WritingPractice writing;
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
        if (!restorePractice()) {
            restoreSession();
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public Object onRetainNonConfigurationInstance() {
        return session != null && (practice != null || translation != null || writing != null)
                ? new RetainedPractice(session, offline, practice, translation, writing) : null;
    }

    @SuppressWarnings("deprecation")
    private boolean restorePractice() {
        if (getLastNonConfigurationInstance() instanceof RetainedPractice retained
                && sessions.matches(retained.session().serverUrl(), retained.session().token())) {
            session = retained.session();
            setOffline(retained.offline());
            practice = retained.cloze();
            translation = retained.translation();
            writing = retained.writing();
            if (practice != null) {
                showPractice();
            } else if (writing != null) {
                showWriting();
            } else if (!pageImport.resume(session.owner())) {
                if (translation.answering) {
                    showTranslationAnswer();
                } else {
                    showTranslationSource();
                }
            }
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
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putBundle("import", pageImport.saveState());
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
        lookups.shutdownNow();
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
        View screen = show(layout);
        setBackAction(onBack);
        return screen;
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
        if (!pageImport.resume(account.owner())) {
            showHome();
        }
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
        closeTranslation();
        closeWriting();
        session = null;
        setOffline(false);
        sessions.clear();
        showLogin(reason);
    }

    private void showLogin(String reason) {
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
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.server_settings)
                .setView(form)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String url;
                    try {
                        url = ApiClient.normalizeBaseUrl(field.getText().toString());
                    } catch (IllegalArgumentException invalidUrl) {
                        field.setError("请输入有效的 HTTP 或 HTTPS 服务端地址。");
                        return;
                    }
                    sessions.changeServer(url);
                    dialog.dismiss();
                    setMessage(page.findViewById(R.id.login_status), "服务端地址已保存。");
                }));
        dialog.show();
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
        practice = null;
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
        loadArticles(home, session, home.findViewById(R.id.imported_articles));
    }

    private TranslationPractice translation() {
        if (translation == null) {
            translation = new TranslationPractice(app.practiceRequests, session.owner(), session.token(), drafts);
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
            writing = new WritingPractice(app.practiceRequests, account.owner(), account.token(), drafts);
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

    private void loadArticles(View home, Session account, LinearLayout container) {
        TextView status = home.findViewById(R.id.articles_status);
        View retry = home.findViewById(R.id.articles_retry);
        setMessage(status, "正在读取材料…");
        retry.setVisibility(View.GONE);
        retry.setOnClickListener(v -> loadArticles(home, account, container));
        app.storage.execute(() -> {
            try {
                List<ArticleDao.Summary> rows = app.articles().list(account.serverUrl(), account.student().id());
                runOnUiThread(() -> {
                    if (!isCurrent(home)) {
                        return;
                    }
                    setMessage(status, rows.isEmpty() ? "还没有导入材料。" : null);
                    for (ArticleDao.Summary article : rows) {
                        Button row = (Button) getLayoutInflater().inflate(R.layout.row_article, container, false);
                        row.setText(article.title());
                        row.setOnClickListener(v -> openArticle(home, article.articleId(), null));
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

    private ReadingArticle example() {
        return DemoArticle.open(getString(R.string.article_title));
    }

    private void openArticle(View source, String articleId, Favorite favorite) {
        if (!isCurrent(source)) {
            return;
        }
        if (DemoArticle.ID.equals(articleId)) {
            showReader(example(), favorite);
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
                            showReader(article.readingArticle(), favorite);
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

    private void articleOpenError(View source, String message) {
        TextView status = source.findViewById(R.id.home_status);
        if (status == null) {
            status = source.findViewById(R.id.vocabulary_status);
        }
        setMessage(status, message);
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
        showReader(article, favorite, this::showHome, R.string.back);
    }

    void showReader(ReadingArticle article, Favorite favorite, Runnable backToPractice) {
        showReader(article, favorite, backToPractice, R.string.cloze_return_to_round);
    }

    private void showReader(ReadingArticle article, Favorite favorite, Runnable onBack, int backLabel) {
        View reader = show(R.layout.screen_reader, onBack);
        Button back = reader.findViewById(R.id.reader_back);
        back.setText(backLabel);
        back.setOnClickListener(v -> onBack.run());
        new Reader(reader, article).open(favorite);
    }

    private final class Reader {
        private record ParagraphView(ReadingArticle.Paragraph paragraph, WordTextView textView) {}

        private final View view;
        private final ReadingArticle article;
        private final List<ParagraphView> paragraphs = new ArrayList<>();
        private final View panel;
        private final TextView title;
        private final TextView mode;
        private final TextView meaning;
        private final TextView detail;
        private final Button action;
        private final ScrollView panelScroll;
        private final ScrollView articleScroll;
        private final View wordActions;
        private final View analyze;
        private final View backToWord;
        private final ScrollView sentenceScroll;
        private final SentenceAnalysisPanel sentencePanel;
        private WordTextView marked;
        private int revision;
        private int analysisRevision;
        private int readingPosition;
        private boolean analysisOpen;

        Reader(View view, ReadingArticle article) {
            this.view = view;
            this.article = article;
            panel = view.findViewById(R.id.explanation_panel);
            title = view.findViewById(R.id.panel_selection);
            mode = view.findViewById(R.id.panel_mode);
            meaning = view.findViewById(R.id.panel_meaning);
            detail = view.findViewById(R.id.panel_detail);
            action = view.findViewById(R.id.panel_action);
            panelScroll = view.findViewById(R.id.panel_scroll);
            articleScroll = view.findViewById(R.id.article_scroll);
            wordActions = view.findViewById(R.id.panel_word_actions);
            analyze = view.findViewById(R.id.panel_analyze);
            backToWord = view.findViewById(R.id.panel_back_to_word);
            sentenceScroll = view.findViewById(R.id.sentence_scroll);
            sentencePanel = new SentenceAnalysisPanel(sentenceScroll);
        }

        void open(Favorite favorite) {
            view.<TextView>findViewById(R.id.reader_title).setText(article.title());
            LinearLayout container = view.findViewById(R.id.article_paragraphs);
            for (ReadingArticle.Paragraph paragraph : article.paragraphs()) {
                WordTextView textView = (WordTextView) getLayoutInflater()
                        .inflate(R.layout.reader_paragraph, container, false);
                paragraphs.add(new ParagraphView(paragraph, textView));
                textView.setParagraph(paragraph.text(), (start, end) -> select(paragraph, start, end));
                container.addView(textView);
            }
            view.findViewById(R.id.reader_vocabulary).setOnClickListener(v -> showVocabulary(article));
            view.findViewById(R.id.panel_close).setOnClickListener(v -> {
                revision++;
                analysisRevision++;
                cancelPendingAnalysis();
                if (pendingLookup != null) {
                    pendingLookup.cancel(true);
                    pendingLookup = null;
                }
                clearMark();
                panel.setVisibility(View.GONE);
                articleScroll.post(() -> {
                    if (isCurrent(view) && panel.getVisibility() == View.GONE) {
                        articleScroll.scrollTo(0, readingPosition);
                    }
                });
            });
            view.addOnLayoutChangeListener((v, left, top, right, bottom, pl, pt, pr, pb) -> {
                if (right - left != pr - pl || bottom - top != pb - pt) {
                    resizePanel();
                }
            });
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
            }
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
            render(null, false, "正在查询…");
            pendingLookup = lookups.submit(() -> {
                try {
                    ApiClient.Explanation result = new ApiClient(account.serverUrl())
                            .explain(account.token(), selection.text(), selection.sentence());
                    runOnUiThread(() -> {
                        if (current(requestRevision)) {
                            render(result, false, result.detail());
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

        private void requestAnalysis(String sentence) {
            Session account = session;
            int requestRevision = ++analysisRevision;
            cancelPendingAnalysis();
            sentencePanel.loading(sentence);
            resizePanel();
            pendingAnalysis = lookups.submit(() -> {
                try {
                    SentenceAnalysis result = new ApiClient(account.serverUrl()).analyze(account.token(), sentence);
                    runOnUiThread(() -> {
                        if (currentAnalysis(requestRevision)) {
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
                    result.meaning(), result.detail(), result.mode(), System.currentTimeMillis());
            panelButton("正在收藏…", null);
            app.storage.execute(() -> {
                try {
                    app.favorites().insert(row);
                    runOnUiThread(() -> {
                        if (current(requestRevision)) {
                            panelButton("已收藏", null);
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
        retry.setOnClickListener(v -> showVocabulary(fromReader));
        setMessage(status, "正在读取收藏…");
        app.storage.execute(() -> {
            try {
                List<FavoriteDao.Row> rows = app.favorites().list(account.serverUrl(), account.student().id());
                runOnUiThread(() -> {
                    if (isCurrent(vocabulary)) {
                        setMessage(status, rows.isEmpty() ? getString(R.string.cloze_empty) : null);
                        vocabulary.<ListView>findViewById(R.id.vocabulary_list)
                                .setAdapter(new FavoritesAdapter(vocabulary, rows));
                        start.setEnabled(!rows.isEmpty());
                        start.setOnClickListener(v -> {
                            if (isCurrent(vocabulary)) {
                                startPractice(account.owner(), rows, fromReader);
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

    private void startPractice(ArticleOwner owner, List<FavoriteDao.Row> rows, ReadingArticle fromReader) {
        Runnable backToVocabulary = () -> showVocabulary(fromReader);
        View loading = show(R.layout.screen_status, backToVocabulary);
        loading.<TextView>findViewById(R.id.status_message).setText(R.string.cloze_loading);
        ReadingArticle builtIn = example();
        app.storage.execute(() -> {
            try {
                Map<String, ReadingArticle> sources = new HashMap<>();
                sources.put(builtIn.id(), builtIn);
                List<ClozeQuestion> questions = new ArrayList<>(rows.size());
                for (FavoriteDao.Row row : rows) {
                    Favorite favorite = row.favorite;
                    ReadingArticle article = sources.get(favorite.articleId);
                    if (article == null) {
                        ImportedArticle imported = app.articles().find(
                                owner.serverUrl(), owner.studentId(), favorite.articleId);
                        if (imported != null) {
                            article = imported.readingArticle();
                            sources.put(article.id(), article);
                        }
                    }
                    ClozeQuestion question = ClozeQuestion.from(article, favorite);
                    if (question == null) {
                        runOnUiThread(() -> showPracticeError(loading, R.string.cloze_bad_source, backToVocabulary));
                        return;
                    }
                    questions.add(question);
                }
                runOnUiThread(() -> {
                    if (isCurrent(loading)) {
                        practice = new Practice(new ClozeRound(questions), fromReader);
                        showPractice();
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
        private final List<FavoriteDao.Row> rows;

        FavoritesAdapter(View vocabulary, List<FavoriteDao.Row> rows) {
            this.vocabulary = vocabulary;
            this.rows = rows;
        }

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public FavoriteDao.Row getItem(int position) {
            return rows.get(position);
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
            row.findViewById(R.id.favorite_jump)
                    .setOnClickListener(v -> openArticle(vocabulary, favorite.articleId, favorite));
            return row;
        }
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
