package com.cethelper.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import android.os.SystemClock;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class ClozeRecreationTest {
    private static final long STUDENT_ID = 42001;
    private static final String TOKEN = "cloze-recreation-test";
    private static final String REPLACEMENT_TOKEN = "cloze-recreation-other-student";

    private StudyApplication app;
    private SharedPreferences preferences;
    private String previousServer;
    private String previousToken;
    private MeServer server;

    @Before
    public void preparePracticeAccount() throws IOException {
        app = (StudyApplication) InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getApplicationContext();
        preferences = app.getSharedPreferences("session", Context.MODE_PRIVATE);
        previousServer = preferences.getString("server_url", null);
        previousToken = preferences.getString("token", null);
        server = new MeServer();
        assertTrue(preferences.edit().putString("server_url", server.url())
                .putString("token", TOKEN).commit());

        saveFavorite("decline", "Some students decline the invitation.", 2);
        saveFavorite("lower demand", "The report attributed the decline to lower demand.", 1);
    }

    @After
    public void restorePracticeAccount() throws IOException, InterruptedException {
        if (server == null) {
            return;
        }
        try {
            SharedPreferences.Editor editor = preferences.edit();
            restorePreference(editor, "server_url", previousServer);
            restorePreference(editor, "token", previousToken);
            assertTrue(editor.commit());
            try (SQLiteDatabase database = SQLiteDatabase.openDatabase(
                    app.getDatabasePath("study.db").getPath(), null, SQLiteDatabase.OPEN_READWRITE)) {
                database.delete("favorites", "serverUrl = ? AND studentId = ?",
                        new String[]{server.url(), Long.toString(STUDENT_ID)});
            }
        } finally {
            server.close();
        }
    }

    @Test
    public void recreatesRevealedQuestionDraftGradeAndFinalScore() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            startPractice(scenario);
            assertText(scenario, R.id.cloze_progress, "第 1 / 2 题");
            assertText(scenario, R.id.cloze_prompt, "Some students ____ the invitation.");
            click(scenario, R.id.cloze_reveal);

            recreate(scenario);
            assertText(scenario, R.id.cloze_progress, "第 1 / 2 题");
            assertText(scenario, R.id.cloze_feedback, "已查看答案，本题不计入独立答对。");
            click(scenario, R.id.cloze_next);

            scenario.onActivity(activity -> activity.<EditText>findViewById(R.id.cloze_input)
                    .setText("lower"));
            server.rejectToken = true;

            recreate(scenario);
            assertText(scenario, R.id.cloze_progress, "第 2 / 2 题");
            assertText(scenario, R.id.cloze_prompt, "The report attributed the decline to ____.");
            assertText(scenario, R.id.cloze_input, "lower");
            scenario.onActivity(activity -> activity.<EditText>findViewById(R.id.cloze_input)
                    .append(" demand"));
            click(scenario, R.id.cloze_submit);
            assertText(scenario, R.id.cloze_feedback, "答对了");

            recreate(scenario);
            assertText(scenario, R.id.cloze_progress, "第 2 / 2 题");
            assertText(scenario, R.id.cloze_feedback, "答对了");
            scenario.onActivity(activity -> assertEquals(View.GONE,
                    activity.findViewById(R.id.cloze_entry).getVisibility()));
            click(scenario, R.id.cloze_next);
            assertText(scenario, R.id.cloze_score, "独立答对 1 / 2 题");

            recreate(scenario);
            assertText(scenario, R.id.cloze_score, "独立答对 1 / 2 题");
            assertEquals(1, server.meRequests.get());
        }
    }

    @Test
    public void freshLaunchValidatesSessionAndHandlesUnauthorized() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            startPractice(scenario);
        }

        server.rejectToken = true;
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            awaitEnabled(scenario, R.id.login);
            assertText(scenario, R.id.login_status, "会话已失效，请重新登录。");
            assertEquals(2, server.meRequests.get());
            assertNull(preferences.getString("token", null));
        }
    }

    @Test
    public void changedTokenDoesNotRestorePreviousStudentsPractice() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            startPractice(scenario);
            assertTrue(preferences.edit().putString("token", REPLACEMENT_TOKEN).commit());

            recreate(scenario);

            awaitEnabled(scenario, R.id.open_vocabulary);
            assertText(scenario, R.id.current_student, "other_student");
            click(scenario, R.id.open_vocabulary);
            awaitText(scenario, R.id.vocabulary_status, "还没有收藏。请先到阅读页收藏单词或词组。");
            scenario.onActivity(activity -> assertNull(activity.findViewById(R.id.cloze_progress)));
            assertEquals(2, server.meRequests.get());
        }
    }

    private void saveFavorite(String answer, String sentence, long savedAt) {
        int start = DemoArticle.BODY.indexOf(sentence) + sentence.indexOf(answer);
        app.favorites().insert(new Favorite(server.url(), STUDENT_ID, DemoArticle.ID,
                start, start + answer.length(), answer, sentence, "测试释义", "测试解释", "demo", savedAt));
    }

    private static void restorePreference(SharedPreferences.Editor editor, String key, String previous) {
        if (previous == null) {
            editor.remove(key);
        } else {
            editor.putString(key, previous);
        }
    }

    private static void startPractice(ActivityScenario<MainActivity> scenario) {
        awaitEnabled(scenario, R.id.open_vocabulary);
        click(scenario, R.id.open_vocabulary);
        awaitEnabled(scenario, R.id.cloze_start);
        click(scenario, R.id.cloze_start);
        awaitEnabled(scenario, R.id.cloze_reveal);
    }

    private static void recreate(ActivityScenario<MainActivity> scenario) {
        AtomicInteger previous = new AtomicInteger();
        scenario.onActivity(activity -> previous.set(System.identityHashCode(activity)));
        scenario.recreate();
        scenario.onActivity(activity -> assertNotEquals(previous.get(), System.identityHashCode(activity)));
    }

    private static void click(ActivityScenario<MainActivity> scenario, int id) {
        scenario.onActivity(activity -> assertTrue(activity.findViewById(id).performClick()));
    }

    private static void assertText(ActivityScenario<MainActivity> scenario, int id, String expected) {
        scenario.onActivity(activity -> {
            TextView view = activity.findViewById(id);
            assertNotNull("Missing view " + activity.getResources().getResourceEntryName(id), view);
            assertEquals(expected, view.getText().toString());
        });
    }

    private static void awaitEnabled(ActivityScenario<MainActivity> scenario, int id) {
        awaitView(scenario, id, view -> view.isShown() && view.isEnabled());
    }

    private static void awaitText(ActivityScenario<MainActivity> scenario, int id, String expected) {
        awaitView(scenario, id, view -> view instanceof TextView text && expected.contentEquals(text.getText()));
    }

    private static void awaitView(ActivityScenario<MainActivity> scenario, int id,
                                  java.util.function.Predicate<View> condition) {
        AtomicBoolean ready = new AtomicBoolean();
        long deadline = SystemClock.elapsedRealtime() + 5000;
        do {
            scenario.onActivity(activity -> {
                View view = activity.findViewById(id);
                ready.set(view != null && condition.test(view));
            });
            if (ready.get()) {
                return;
            }
            SystemClock.sleep(30);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail("View did not reach the expected state: " + id);
    }

    private static final class MeServer implements AutoCloseable {
        final AtomicInteger meRequests = new AtomicInteger();
        volatile boolean rejectToken;
        private final ServerSocket socket;
        private final Thread worker;

        MeServer() throws IOException {
            socket = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
            worker = new Thread(this::serve, "cloze-test-me");
            worker.start();
        }

        String url() {
            return "http://127.0.0.1:" + socket.getLocalPort();
        }

        private void serve() {
            while (!socket.isClosed()) {
                try (Socket client = socket.accept()) {
                    client.setSoTimeout(3000);
                    respond(client);
                } catch (IOException failure) {
                    if (!socket.isClosed()) {
                        throw new AssertionError(failure);
                    }
                }
            }
        }

        private void respond(Socket client) throws IOException {
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    client.getInputStream(), StandardCharsets.US_ASCII));
            String request = reader.readLine();
            if (request == null) {
                return;
            }
            String authorization = "";
            for (String header = reader.readLine(); header != null && !header.isEmpty();
                 header = reader.readLine()) {
                if (header.regionMatches(true, 0, "Authorization: ", 0, 15)) {
                    authorization = header.substring(15);
                }
            }
            if (request.startsWith("GET /api/v1/me ")) {
                meRequests.incrementAndGet();
            }
            boolean replacement = authorization.equals("Bearer " + REPLACEMENT_TOKEN);
            boolean authorized = !rejectToken && (replacement || authorization.equals("Bearer " + TOKEN));
            String json = authorized
                    ? "{\"id\":" + (replacement ? STUDENT_ID + 1 : STUDENT_ID)
                            + ",\"username\":\"" + (replacement ? "other_student" : "cloze_student") + "\"}"
                    : "{\"code\":\"UNAUTHORIZED\",\"message\":\"expired\"}";
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            String headers = "HTTP/1.1 " + (authorized ? "200 OK" : "401 Unauthorized")
                    + "\r\nContent-Type: application/json\r\nConnection: close\r\nContent-Length: "
                    + body.length + "\r\n\r\n";
            OutputStream output = client.getOutputStream();
            output.write(headers.getBytes(StandardCharsets.US_ASCII));
            output.write(body);
        }

        @Override
        public void close() throws IOException, InterruptedException {
            socket.close();
            worker.join(3500);
        }
    }
}
