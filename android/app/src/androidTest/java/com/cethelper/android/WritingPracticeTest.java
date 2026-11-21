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

public final class WritingPracticeTest {
    private static final String TOKEN = "writing-fixture-token";
    private static final String PROMPT = "How can we help the neighborhood?";
    private static final String EDITED_PROMPT = "How can students help the neighborhood?";
    private static final String QUOTE = "The class organize events";
    private static final String DRAFT = "🙂 " + QUOTE + ".\n\n" + QUOTE + ". We brings bags.";
    private static final String IN_PROGRESS = "We organize events, and we brings bags.";
    private static final String REVISION = "We organize events, and we bring bags.";
    private static final String ANSWER = "The class organizes events every spring.";
    private static final String EXERCISE_INSTRUCTION = "用正确的动词形式写出完整句子。";
    private static final String EXERCISE_ITEM = "The class ___ (organize) events every spring.";
    private static final String RETEST_INSTRUCTION = "用复数主语和正确的动词写一句话。";
    private static final String RETEST_ITEM = "In the art club,  describe what pupils do each week.";
    private static final String RETEST_QUOTE = "Many pupils talks";
    private static final String RETEST_ANSWER = RETEST_QUOTE + ", and many friends listen. " + RETEST_QUOTE + ".";
    private static final String RETEST_EDITED = "Many pupils talk.";
    private static final String NEXT_ROUND = "We organize the fair, and the team checks the venue.";
    private static final String NEXT_EXERCISE_ITEM = "Describe how to select a venue.";


    @Test
    @SdkSuppress(minSdkVersion = 29)
    public void keepsExerciseAndSnapshotWithSecondIssueAndIgnoresSupersededReview() throws Exception {
        runWritingScenario((scenario, server) -> {
            scenario.onActivity(activity -> {
                View first = issues(activity).getChildAt(0);
                View second = issues(activity).getChildAt(1);
                assertNull(first.findViewById(R.id.writing_exercise));
                assertEquals("表达不清 · 「We brings bags」\n定位引文",
                        text(first, R.id.review_issue_quote));
                assertEquals("语法 · 「" + QUOTE + "」\n定位引文",
                        text(second, R.id.review_issue_quote));
                assertEquals("用正确的动词形式写出完整句子。", text(second, R.id.writing_exercise_instruction));
                assertEquals("The class ___ (organize) events every spring.", text(second, R.id.writing_exercise_item));
                assertEquals("该引文出现 2 次，高亮均为候选位置。", text(second, R.id.review_issue_candidates));
            });

            enter(scenario, R.id.writing_exercise_answer, ANSWER);
            enter(scenario, R.id.writing_prompt, EDITED_PROMPT);
            enter(scenario, R.id.writing_essay, IN_PROGRESS);
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertEquals(EDITED_PROMPT, text(activity.getWindow().getDecorView(), R.id.writing_prompt));
                assertEquals(IN_PROGRESS, text(activity.getWindow().getDecorView(), R.id.writing_essay));
                assertEquals(ANSWER, text(activity.getWindow().getDecorView(), R.id.writing_exercise_answer));
                assertEquals(View.VISIBLE, activity.findViewById(R.id.writing_review_edited).getVisibility());
            });
            assertEquals(1, server.sessionChecks.get());
            assertSnapshot(scenario, 0, "We brings bags", DRAFT.indexOf("We brings bags"));
            assertSnapshot(scenario, 1, QUOTE, DRAFT.indexOf(QUOTE), DRAFT.lastIndexOf(QUOTE));

            click(scenario, R.id.writing_submit);
            assertTrue(server.delayedReview.requested.await(5, TimeUnit.SECONDS));
            scenario.onActivity(activity -> assertTrue(!activity.findViewById(R.id.writing_submit).isEnabled()));
            enter(scenario, R.id.writing_essay, REVISION);
            click(scenario, R.id.writing_submit);
            await(scenario, activity -> "修订稿反馈".equals(text(activity.getWindow().getDecorView(),
                    R.id.writing_review_overall)));
            server.delayedReview.release.countDown();
            assertTrue(server.delayedReview.sent.await(5, TimeUnit.SECONDS));
            SystemClock.sleep(350);
            scenario.onActivity(activity -> {
                assertEquals("修订稿反馈", text(activity.getWindow().getDecorView(), R.id.writing_review_overall));
                assertEquals(0, issues(activity).getChildCount());
                assertNull(activity.findViewById(R.id.writing_exercise_answer));
                assertEquals(View.GONE, activity.findViewById(R.id.writing_review_edited).getVisibility());
            });
            assertEquals(3, server.reviewCount.get());
            assertNull(server.failure.get());
        });
    }

    @Test
    @SdkSuppress(minSdkVersion = 29)
    public void keepsSecondIssueFollowUpAcrossRotationAndDiscardsRepliesFromPreviousReview() throws Exception {
        runWritingScenario((scenario, server) -> {
            scenario.onActivity(activity -> {
                assertNull(issues(activity).getChildAt(0).findViewById(R.id.writing_exercise));
                assertNotNull(issues(activity).getChildAt(1).findViewById(R.id.writing_exercise_submit));
            });
            click(scenario, R.id.writing_request_retest);
            await(scenario, activity -> activity.findViewById(R.id.writing_retest).isShown());
            enter(scenario, R.id.writing_retest_answer, RETEST_ANSWER);
            enter(scenario, R.id.writing_exercise_answer, ANSWER);
            click(scenario, R.id.writing_exercise_submit);
            assertTrue(server.delayedExercise.requested.await(5, TimeUnit.SECONDS));
            enter(scenario, R.id.writing_exercise_answer, "Typing a different answer.");
            scenario.recreate();
            scenario.onActivity(activity -> {
                View root = activity.getWindow().getDecorView();
                assertEquals(EXERCISE_ITEM, text(root, R.id.writing_exercise_item));
                assertEquals("Typing a different answer.", text(root, R.id.writing_exercise_answer));
                assertEquals(RETEST_ITEM, text(root, R.id.writing_retest_item));
                assertEquals(RETEST_ANSWER, text(root, R.id.writing_retest_answer));
                View exercise = root.findViewById(R.id.writing_exercise_result);
                assertEquals("正在判答…", text(exercise, R.id.writing_answer_status));
            });
            server.delayedExercise.release.countDown();
            await(scenario, activity -> "正确".equals(text(activity.findViewById(R.id.writing_exercise_result),
                    R.id.writing_answer_verdict)));
            scenario.onActivity(activity -> assertEquals(ANSWER, text(
                    activity.findViewById(R.id.writing_exercise_result), R.id.writing_answer_snapshot)));
            assertEquals(1, server.sessionChecks.get());

            click(scenario, R.id.writing_retest_submit);
            await(scenario, activity -> "同类错误再次出现".equals(text(activity.findViewById(R.id.writing_retest_result),
                    R.id.writing_answer_verdict)));
            enter(scenario, R.id.writing_retest_answer, RETEST_EDITED);
            scenario.onActivity(activity -> {
                View result = activity.findViewById(R.id.writing_retest_result);
                TextView snapshot = result.findViewById(R.id.writing_answer_snapshot);
                assertHighlights(snapshot, RETEST_ANSWER, RETEST_QUOTE,
                        RETEST_ANSWER.indexOf(RETEST_QUOTE), RETEST_ANSWER.lastIndexOf(RETEST_QUOTE));
            });
            assertSnapshot(scenario, 1, QUOTE, DRAFT.indexOf(QUOTE), DRAFT.lastIndexOf(QUOTE));

            click(scenario, R.id.writing_retest_submit);
            assertTrue(server.delayedRetestAnswer.requested.await(5, TimeUnit.SECONDS));
            click(scenario, R.id.writing_request_retest);
            assertTrue(server.delayedRetestTask.requested.await(5, TimeUnit.SECONDS));
            enter(scenario, R.id.writing_prompt, EDITED_PROMPT);
            enter(scenario, R.id.writing_essay, NEXT_ROUND);
            click(scenario, R.id.writing_submit);
            await(scenario, activity -> "新一轮反馈".equals(text(activity.getWindow().getDecorView(),
                    R.id.writing_review_overall)));
            server.delayedRetestAnswer.release.countDown();
            server.delayedRetestTask.release.countDown();
            assertTrue(server.delayedRetestAnswer.sent.await(5, TimeUnit.SECONDS));
            assertTrue(server.delayedRetestTask.sent.await(5, TimeUnit.SECONDS));
            SystemClock.sleep(350);
            scenario.onActivity(activity -> {
                View root = activity.getWindow().getDecorView();
                assertEquals(NEXT_EXERCISE_ITEM, text(root, R.id.writing_exercise_item));
                assertEquals("", text(root, R.id.writing_exercise_answer));
                assertEquals(View.GONE, root.findViewById(R.id.writing_retest).getVisibility());
                assertEquals(View.GONE, root.findViewById(R.id.writing_assessment_content).getVisibility());
            });
            assertEquals(2, server.reviewCount.get());
            assertEquals(2, server.taskCount.get());
            assertEquals(2, server.retestAnswerCount.get());
            assertNull(server.failure.get());
        });
    }

    private interface ScenarioCheck {
        void run(ActivityScenario<MainActivity> scenario, Fixture server) throws Exception;
    }

    private static void runWritingScenario(ScenarioCheck check) throws Exception {
        SharedPreferences preferences = InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getSharedPreferences("session", Context.MODE_PRIVATE);
        String previousUrl = preferences.getString("server_url", null);
        String previousToken = preferences.getString("token", null);
        try (Fixture server = new Fixture()) {
            assertTrue(preferences.edit().putString("server_url", server.url()).putString("token", TOKEN).commit());
            try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
                await(scenario, activity -> activity.findViewById(R.id.open_writing) != null);
                click(scenario, R.id.open_writing);
                enter(scenario, R.id.writing_prompt, PROMPT);
                enter(scenario, R.id.writing_essay, DRAFT);
                click(scenario, R.id.writing_submit);
                await(scenario, activity -> issues(activity).getChildCount() == 2);
                check.run(scenario, server);
            }
        } finally {
            assertTrue(preferences.edit().putString("server_url", previousUrl)
                    .putString("token", previousToken).commit());
        }
    }

    private static void assertSnapshot(ActivityScenario<MainActivity> scenario, int row, String quote, int... starts) {
        scenario.onActivity(activity ->
                assertTrue(issues(activity).getChildAt(row).findViewById(R.id.review_issue_quote).performClick()));
        scenario.onActivity(activity -> {
            TextView snapshot = null;
            for (View window : WindowInspector.getGlobalWindowViews()) {
                TextView candidate = window.findViewById(R.id.review_snapshot);
                if (candidate != null && candidate.isShown()) {
                    snapshot = candidate;
                    assertTrue(window.findViewById(android.R.id.button1).performClick());
                    break;
                }
            }
            assertNotNull(snapshot);
            assertHighlights(snapshot, DRAFT, quote, starts);
        });
    }

    private static void assertHighlights(TextView snapshot, String answer, String quote, int... starts) {
        Spanned highlighted = (Spanned) snapshot.getText();
        assertEquals(answer, highlighted.toString());
        int[] positions = Arrays.stream(highlighted.getSpans(0, highlighted.length(), BackgroundColorSpan.class))
                .mapToInt(span -> {
                    int start = highlighted.getSpanStart(span);
                    assertEquals(start + quote.length(), highlighted.getSpanEnd(span));
                    return start;
                }).sorted().toArray();
        assertArrayEquals(starts, positions);
    }

    private static LinearLayout issues(MainActivity activity) {
        return activity.findViewById(R.id.writing_issues);
    }

    private static String text(View root, int id) {
        return root.<TextView>findViewById(id).getText().toString();
    }

    private static void click(ActivityScenario<MainActivity> scenario, int id) {
        scenario.onActivity(activity -> {
            assertTrue(activity.findViewById(id).isEnabled());
            assertTrue(activity.findViewById(id).performClick());
        });
    }

    private static void enter(ActivityScenario<MainActivity> scenario, int id, String value) {
        scenario.onActivity(activity -> activity.<EditText>findViewById(id).setText(value));
    }

    private static void await(ActivityScenario<MainActivity> scenario, Predicate<MainActivity> condition) {
        AtomicBoolean ready = new AtomicBoolean();
        long deadline = SystemClock.elapsedRealtime() + 6000;
        do {
            scenario.onActivity(activity -> ready.set(condition.test(activity)));
            if (ready.get()) {
                return;
            }
            SystemClock.sleep(30);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail("The writing screen did not reach the expected state");
    }

    private static final class Delay {
        final CountDownLatch requested = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch sent = new CountDownLatch(1);

        void awaitRelease() throws InterruptedException {
            requested.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
        }
    }

    private record Reply(JSONObject json, Delay delay) {
        Reply(JSONObject json) {
            this(json, null);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Delay delayedReview = new Delay();
        final Delay delayedExercise = new Delay();
        final Delay delayedRetestTask = new Delay();
        final Delay delayedRetestAnswer = new Delay();
        final AtomicInteger sessionChecks = new AtomicInteger();
        final AtomicInteger reviewCount = new AtomicInteger();
        final AtomicInteger taskCount = new AtomicInteger();
        final AtomicInteger retestAnswerCount = new AtomicInteger();
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
            }, "writing-test-http");
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
                Reply reply;
                if (request.startsWith("GET /api/v1/me ")) {
                    sessionChecks.incrementAndGet();
                    reply = new Reply(new JSONObject().put("id", 42006).put("username", "writing_fixture"));
                } else {
                    assertTrue(request.startsWith("POST /api/v1/"));
                    byte[] bytes = new byte[length];
                    input.readFully(bytes);
                    JSONObject body = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
                    reply = switch (request.split(" ", 3)[1]) {
                        case "/api/v1/writing-reviews" -> review(body);
                        case "/api/v1/writing-exercise-attempts" -> gradeExercise(body);
                        case "/api/v1/writing-retests" -> createRetest(body);
                        case "/api/v1/writing-retest-attempts" -> gradeRetest(body);
                        default -> throw new AssertionError("Unexpected request " + request);
                    };
                }
                send(client, reply);
            } catch (Exception | AssertionError problem) {
                failure.set(problem);
            }
        }

        private Reply review(JSONObject body) throws Exception {
            String essay = body.getString("essay");
            String prompt = body.getString("prompt");
            int attempt = reviewCount.incrementAndGet();
            assertEquals(attempt == 1 ? PROMPT : EDITED_PROMPT, prompt);
            JSONArray issues = new JSONArray();
            JSONObject output = new JSONObject().put("prompt", prompt).put("essay", essay)
                    .put("mode", "live").put("issues", issues);
            if (attempt == 1) {
                assertEquals(DRAFT, essay);
                issues.put(issue("CLARITY", "We brings bags")).put(issue("GRAMMAR", QUOTE));
                output.put("overallFeedback", "首稿反馈").put("exercise",
                        task(EXERCISE_INSTRUCTION, EXERCISE_ITEM).put("issueIndex", 1));
            } else if (essay.equals(IN_PROGRESS)) {
                return new Reply(output.put("overallFeedback", "迟到的反馈"), delayedReview);
            } else if (essay.equals(NEXT_ROUND)) {
                issues.put(issue("WORD_CHOICE", "the venue"));
                output.put("overallFeedback", "新一轮反馈").put("exercise",
                        task("用一句话写出选址方法。", NEXT_EXERCISE_ITEM).put("issueIndex", 0));
            } else {
                assertEquals(REVISION, essay);
                output.put("overallFeedback", "修订稿反馈");
            }
            return new Reply(output);
        }

        private Reply gradeExercise(JSONObject body) throws Exception {
            validateContext(body.getJSONObject("context"));
            assertEquals(ANSWER, body.getString("answer"));
            return new Reply(assessment(ANSWER, "CORRECT"), delayedExercise);
        }

        private Reply createRetest(JSONObject body) throws Exception {
            validateContext(body);
            int attempt = taskCount.incrementAndGet();
            JSONObject response = new JSONObject().put("issue", issue("GRAMMAR", QUOTE)).put("mode", "live");
            if (attempt == 1) {
                return new Reply(response.put("task", task(RETEST_INSTRUCTION, RETEST_ITEM)));
            }
            return new Reply(response.put("task", task("用复数主语描写新场景。", "A late new task.")),
                    delayedRetestTask);
        }

        private Reply gradeRetest(JSONObject body) throws Exception {
            validateContext(body.getJSONObject("context"));
            JSONObject task = body.getJSONObject("task");
            assertEquals(RETEST_INSTRUCTION, task.getString("instruction"));
            assertEquals(RETEST_ITEM, task.getString("item"));
            int attempt = retestAnswerCount.incrementAndGet();
            String answer = body.getString("answer");
            if (attempt == 1) {
                assertEquals(RETEST_ANSWER, answer);
                return new Reply(assessment(answer, "SAME_ERROR").put("quote", RETEST_QUOTE));
            }
            assertEquals(RETEST_EDITED, answer);
            return new Reply(assessment(answer, "TARGET_USED").put("quote", "Many pupils talk"), delayedRetestAnswer);
        }

        private static void validateContext(JSONObject context) throws Exception {
            JSONObject expected = issue("GRAMMAR", QUOTE);
            JSONObject original = context.getJSONObject("issue");
            for (String key : new String[]{"type", "quote", "detail", "suggestion"}) {
                assertEquals(expected.getString(key), original.getString(key));
            }
            JSONObject exercise = context.getJSONObject("exercise");
            assertEquals(EXERCISE_ITEM, exercise.getString("item"));
            assertEquals(EXERCISE_INSTRUCTION, exercise.getString("instruction"));
        }

        private static JSONObject task(String instruction, String item) throws Exception {
            return new JSONObject().put("instruction", instruction).put("item", item);
        }

        private static JSONObject assessment(String answer, String verdict) throws Exception {
            return new JSONObject().put("issue", issue("GRAMMAR", QUOTE)).put("answer", answer)
                    .put("verdict", verdict).put("feedback", "固定的判答反馈。")
                    .put("nextStep", "继续练习该语法点。").put("mode", "live");
        }

        private static void send(Socket client, Reply reply) throws Exception {
            Delay delay = reply.delay();
            if (delay != null) {
                delay.awaitRelease();
            }
            byte[] response = reply.json().toString().getBytes(StandardCharsets.UTF_8);
            String headers = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nConnection: close\r\n"
                    + "Content-Length: " + response.length + "\r\n\r\n";
            try {
                client.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
                client.getOutputStream().write(response);
            } catch (IOException cancelledRequest) {
                if (delay == null) {
                    throw cancelledRequest;
                }
            } finally {
                if (delay != null) {
                    delay.sent.countDown();
                }
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
                    .put("detail", "检查这处表达。").put("suggestion", "修正动词形式。");
        }

        @Override
        public void close() throws Exception {
            delayedReview.release.countDown();
            delayedExercise.release.countDown();
            delayedRetestTask.release.countDown();
            delayedRetestAnswer.release.countDown();
            listener.close();
            acceptor.join(3000);
            handlers.shutdownNow();
        }
    }
}
