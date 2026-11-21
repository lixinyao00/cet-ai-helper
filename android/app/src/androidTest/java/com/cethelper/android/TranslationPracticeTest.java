package com.cethelper.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.view.View;
import android.view.inspector.WindowInspector;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class TranslationPracticeTest {
    private static final String SOURCE = "今天读书，今天讨论，明天写作，今天复习。";
    private static final String DRAFT = "I very much like it, yet I very much doubt it.";
    private static final String REVISION = "I like it, but I still have doubts.";
    private static final String TOKEN = "translation-ui-fixture";

    @Test
    @SdkSuppress(minSdkVersion = 29)
    public void retainsLatestHintAndLocatesEachReviewQuoteInItsSubmission() throws Exception {
        SharedPreferences preferences = InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getSharedPreferences("session", Context.MODE_PRIVATE);
        String previousUrl = preferences.getString("server_url", null);
        String previousToken = preferences.getString("token", null);

        try (Fixture server = new Fixture()) {
            assertTrue(preferences.edit().putString("server_url", server.url()).putString("token", TOKEN).commit());
            try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
                await(scenario, activity -> activity.findViewById(R.id.open_translation) != null);
                click(scenario, R.id.open_translation);
                setText(scenario, R.id.translation_source_input, SOURCE);
                click(scenario, R.id.translation_start);
                setText(scenario, R.id.translation_draft, DRAFT);
                assertEquals(0, server.hintRequests.get());

                click(scenario, R.id.translation_keywords);
                assertTrue(server.keywordRequested.await(5, TimeUnit.SECONDS));
                click(scenario, R.id.translation_structure);
                awaitHint(scenario);
                scenario.recreate();
                server.releaseKeyword.countDown();
                assertTrue(server.keywordReplied.await(5, TimeUnit.SECONDS));
                SystemClock.sleep(400);
                awaitHint(scenario);
                assertText(scenario, R.id.translation_draft, DRAFT);
                assertEquals(2, server.hintRequests.get());
                assertEquals(1, server.meRequests.get());

                click(scenario, R.id.translation_submit);
                assertTrue(server.firstReviewRequested.await(5, TimeUnit.SECONDS));
                setText(scenario, R.id.translation_draft, REVISION);
                server.releaseFirstReview.countDown();
                await(scenario, activity -> issues(activity).getChildCount() == 3);
                scenario.onActivity(activity -> {
                    assertEquals(View.VISIBLE, activity.findViewById(R.id.translation_edited).getVisibility());
                    var repeated = issues(activity).getChildAt(1)
                            .<TextView>findViewById(R.id.review_issue_candidates);
                    assertEquals("该引文出现 3 次，高亮均为候选位置。", repeated.getText().toString());
                    var english = issues(activity).getChildAt(2)
                            .<TextView>findViewById(R.id.review_issue_candidates);
                    assertEquals("该引文出现 2 次，高亮均为候选位置。", english.getText().toString());
                });
                assertQuote(scenario, 0, SOURCE, "明天", SOURCE.indexOf("明天"));
                int secondToday = SOURCE.indexOf("今天", 1);
                assertQuote(scenario, 1, SOURCE, "今天", 0, secondToday, SOURCE.lastIndexOf("今天"));
                assertQuote(scenario, 2, DRAFT, "very much", DRAFT.indexOf("very much"), DRAFT.lastIndexOf("very much"));

                click(scenario, R.id.translation_submit);
                await(scenario, activity -> "修订反馈".contentEquals(
                        activity.<TextView>findViewById(R.id.translation_review_status).getText()));
                scenario.onActivity(activity -> {
                    assertEquals(0, issues(activity).getChildCount());
                    assertEquals(View.GONE, activity.findViewById(R.id.translation_edited).getVisibility());
                });
                assertText(scenario, R.id.translation_draft, REVISION);
                assertEquals(2, server.reviewRequests.get());
                assertNull(server.failure.get());
            }
        } finally {
            assertTrue(preferences.edit().putString("server_url", previousUrl)
                    .putString("token", previousToken).commit());
        }
    }

    private static LinearLayout issues(MainActivity activity) {
        return activity.findViewById(R.id.translation_issues);
    }

    private static void awaitHint(ActivityScenario<MainActivity> scenario) {
        await(scenario, activity -> {
            LinearLayout items = activity.findViewById(R.id.translation_hint_items);
            return items.getChildCount() == 2 && "最后选择的结构".contentEquals(
                    ((TextView) items.getChildAt(0)).getText())
                    && activity.findViewById(R.id.translation_structure).isSelected();
        });
    }

    private static void assertQuote(ActivityScenario<MainActivity> scenario, int index,
                                    String snapshot, String quote, int... starts) {
        scenario.onActivity(activity -> assertTrue(issues(activity).getChildAt(index)
                .findViewById(R.id.review_issue_quote).performClick()));
        scenario.onActivity(activity -> {
            TextView text = null;
            for (View window : WindowInspector.getGlobalWindowViews()) {
                TextView candidate = window.findViewById(R.id.review_snapshot);
                if (candidate != null && candidate.isShown()) {
                    text = candidate;
                    assertTrue(window.findViewById(android.R.id.button1).performClick());
                    break;
                }
            }
            assertNotNull(text);
            Spanned highlighted = (Spanned) text.getText();
            assertEquals(snapshot, highlighted.toString());
            BackgroundColorSpan[] spans = highlighted.getSpans(0, highlighted.length(), BackgroundColorSpan.class);
            int[] positions = Arrays.stream(spans).mapToInt(span -> {
                int start = highlighted.getSpanStart(span);
                assertEquals(start + quote.length(), highlighted.getSpanEnd(span));
                return start;
            }).sorted().toArray();
            assertArrayEquals(starts, positions);
        });
    }

    private static void click(ActivityScenario<MainActivity> scenario, int id) {
        scenario.onActivity(activity -> assertTrue(activity.findViewById(id).performClick()));
    }

    private static void setText(ActivityScenario<MainActivity> scenario, int id, String text) {
        scenario.onActivity(activity -> activity.<EditText>findViewById(id).setText(text));
    }

    private static void assertText(ActivityScenario<MainActivity> scenario, int id, String text) {
        scenario.onActivity(activity -> assertEquals(text,
                activity.<TextView>findViewById(id).getText().toString()));
    }

    private static void await(ActivityScenario<MainActivity> scenario, Predicate<MainActivity> condition) {
        AtomicBoolean ready = new AtomicBoolean();
        long deadline = SystemClock.elapsedRealtime() + 6000;
        do {
            scenario.onActivity(activity -> ready.set(condition.test(activity)));
            if (ready.get()) {
                return;
            }
            SystemClock.sleep(35);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail("The translation screen did not reach the expected state");
    }

    private static final class Fixture implements AutoCloseable {
        final CountDownLatch keywordRequested = new CountDownLatch(1);
        final CountDownLatch releaseKeyword = new CountDownLatch(1);
        final CountDownLatch keywordReplied = new CountDownLatch(1);
        final CountDownLatch firstReviewRequested = new CountDownLatch(1);
        final CountDownLatch releaseFirstReview = new CountDownLatch(1);
        final AtomicInteger meRequests = new AtomicInteger();
        final AtomicInteger hintRequests = new AtomicInteger();
        final AtomicInteger reviewRequests = new AtomicInteger();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final ServerSocket listener;
        private final ExecutorService handlers = Executors.newFixedThreadPool(3);
        private final Thread acceptor;

        Fixture() throws IOException {
            listener = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            acceptor = new Thread(() -> {
                while (!listener.isClosed()) {
                    try {
                        Socket client = listener.accept();
                        handlers.execute(() -> serve(client));
                    } catch (IOException problem) {
                        if (!listener.isClosed()) {
                            failure.set(problem);
                        }
                    }
                }
            }, "translation-test-http");
            acceptor.start();
        }

        String url() {
            return "http://127.0.0.1:" + listener.getLocalPort();
        }

        private void serve(Socket client) {
            try (client; DataInputStream input = new DataInputStream(new BufferedInputStream(client.getInputStream()))) {
                client.setSoTimeout(4500);
                String request = readLine(input);
                int length = 0;
                String token = "";
                for (String header = readLine(input); !header.isEmpty(); header = readLine(input)) {
                    if (header.regionMatches(true, 0, "Content-Length: ", 0, 16)) {
                        length = Integer.parseInt(header.substring(16));
                    }
                    if (header.regionMatches(true, 0, "Authorization: ", 0, 15)) {
                        token = header.substring(15);
                    }
                }
                assertEquals("Bearer " + TOKEN, token);
                byte[] body = new byte[length];
                input.readFully(body);
                JSONObject payload = length == 0 ? new JSONObject()
                        : new JSONObject(new String(body, StandardCharsets.UTF_8));
                JSONObject response;
                boolean lateKeyword = false;
                if (request.startsWith("GET /api/v1/me ")) {
                    meRequests.incrementAndGet();
                    response = new JSONObject().put("id", 42005).put("username", "translation_fixture");
                } else {
                    assertEquals(SOURCE, payload.getString("sourceText"));
                    response = new JSONObject().put("sourceText", SOURCE).put("mode", "live");
                    if (request.startsWith("POST /api/v1/translation-hints ")) {
                        hintRequests.incrementAndGet();
                        String type = payload.getString("hintType");
                        response.put("hintType", type);
                        if ("keyword".equals(type)) {
                            lateKeyword = true;
                            keywordRequested.countDown();
                            assertTrue(releaseKeyword.await(5, TimeUnit.SECONDS));
                            response.put("keywords", new JSONArray().put(new JSONObject().put("source", "今天")
                                    .put("expressions", new JSONArray().put("today")).put("note", "旧响应")));
                        } else {
                            assertEquals("structure", type);
                            response.put("structure", new JSONObject().put("mainClause", "最后选择的结构")
                                    .put("clauses", new JSONArray()).put("connectives", new JSONArray().put(
                                            new JSONObject().put("source", "明天").put("suggestion", "tomorrow"))));
                        }
                    } else {
                        assertTrue(request.startsWith("POST /api/v1/translation-reviews "));
                        String draft = payload.getString("studentTranslation");
                        boolean first = reviewRequests.incrementAndGet() == 1;
                        assertEquals(first ? DRAFT : REVISION, draft);
                        JSONArray feedback = new JSONArray();
                        if (first) {
                            firstReviewRequested.countDown();
                            assertTrue(releaseFirstReview.await(5, TimeUnit.SECONDS));
                            feedback.put(issue("OMISSION", "明天")).put(issue("OMISSION", "今天"))
                                    .put(issue("UNNATURAL", "very much"));
                        }
                        response.put("studentTranslation", draft).put("overallFeedback", first ? "首稿反馈" : "修订反馈")
                                .put("issues", feedback);
                    }
                }
                byte[] data = response.toString().getBytes(StandardCharsets.UTF_8);
                String headers = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nConnection: close\r\n"
                        + "Content-Length: " + data.length + "\r\n\r\n";
                try {
                    client.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
                    client.getOutputStream().write(data);
                } catch (IOException cancelled) {
                    if (!lateKeyword) {
                        throw cancelled;
                    }
                } finally {
                    if (lateKeyword) {
                        keywordReplied.countDown();
                    }
                }
            } catch (Exception | AssertionError problem) {
                failure.set(problem);
            }
        }

        private static String readLine(DataInputStream input) throws IOException {
            StringBuilder line = new StringBuilder();
            int value;
            while ((value = input.readUnsignedByte()) != '\n') {
                if (value != '\r') {
                    line.append((char) value);
                }
            }
            return line.toString();
        }

        private static JSONObject issue(String type, String quote) throws Exception {
            return new JSONObject().put("type", type).put("quote", quote)
                    .put("detail", "测试说明").put("suggestion", "测试建议");
        }

        @Override
        public void close() throws Exception {
            releaseKeyword.countDown();
            releaseFirstReview.countDown();
            listener.close();
            acceptor.join(3000);
            handlers.shutdownNow();
        }
    }
}
