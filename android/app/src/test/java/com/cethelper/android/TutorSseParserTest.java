package com.cethelper.android;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class TutorSseParserTest {
    @Test
    public void parsesMetaDeltaAndDoneSequenceWithComments() throws Exception {
        String sse = ": keep-alive\n\n"
                + "event: meta\n"
                + "data: {\"mode\":\"demo\",\"contextType\":\"WORD\"}\n\n"
                + "event: delta\n"
                + "data: {\"text\":\"abandon 强调\"}\n\n"
                + "event: delta\n"
                + "data: {\"text\":\"彻底放弃。\"}\n\n"
                + "event: done\n"
                + "data: {\"followUps\":[\"能再给我两个例句吗？\",\"它的名词形式怎么用？\"]}\n\n";

        AtomicReference<String> mode = new AtomicReference<>();
        AtomicReference<String> contextType = new AtomicReference<>();
        StringBuilder text = new StringBuilder();
        AtomicReference<List<String>> followUps = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();

        ApiClient.parseTutorSseStream(
                new BufferedReader(new StringReader(sse)),
                () -> false,
                new ApiClient.TutorStreamListener() {
                    @Override
                    public void onMeta(String m, String ct) {
                        mode.set(m);
                        contextType.set(ct);
                    }

                    @Override
                    public void onDelta(String t) {
                        text.append(t);
                    }

                    @Override
                    public void onDone(List<String> f) {
                        followUps.set(f);
                    }

                    @Override
                    public void onError(Exception failure) {
                        error.set(failure);
                    }
                }
        );

        assertNull(error.get());
        assertEquals("demo", mode.get());
        assertEquals("WORD", contextType.get());
        assertEquals("abandon 强调彻底放弃。", text.toString());
        assertEquals(List.of("能再给我两个例句吗？", "它的名词形式怎么用？"), followUps.get());
    }

    @Test
    public void joinsMultiLineDataFieldsBeforeDispatch() throws Exception {
        String sse = "event: delta\n"
                + "data: {\"text\":\n"
                + "data: \"line1\\nline2\"}\n\n"
                + "event: done\n"
                + "data: {\"followUps\":[]}\n\n";

        StringBuilder text = new StringBuilder();
        AtomicBoolean doneCalled = new AtomicBoolean();
        AtomicReference<Exception> error = new AtomicReference<>();

        ApiClient.parseTutorSseStream(
                new BufferedReader(new StringReader(sse)),
                () -> false,
                new ApiClient.TutorStreamListener() {
                    @Override public void onMeta(String m, String ct) {}
                    @Override public void onDelta(String t) { text.append(t); }
                    @Override public void onDone(List<String> f) { doneCalled.set(true); }
                    @Override public void onError(Exception failure) { error.set(failure); }
                }
        );

        assertNull(error.get());
        assertTrue(doneCalled.get());
        assertEquals("line1\nline2", text.toString());
    }

    @Test
    public void errorEventDispatchesFailureWithStatus200AndStopsStream() throws Exception {
        String sse = "event: meta\n"
                + "data: {\"mode\":\"live\",\"contextType\":\"FREE\"}\n\n"
                + "event: error\n"
                + "data: {\"code\":\"MODEL_TIMEOUT\",\"message\":\"AI 助教响应超时，请稍后重试。\"}\n\n"
                + "event: done\n"
                + "data: {\"followUps\":[]}\n\n";

        AtomicBoolean doneCalled = new AtomicBoolean();
        AtomicReference<Exception> error = new AtomicReference<>();

        ApiClient.parseTutorSseStream(
                new BufferedReader(new StringReader(sse)),
                () -> false,
                new ApiClient.TutorStreamListener() {
                    @Override public void onMeta(String m, String ct) {}
                    @Override public void onDelta(String t) {}
                    @Override public void onDone(List<String> f) { doneCalled.set(true); }
                    @Override public void onError(Exception failure) { error.set(failure); }
                }
        );

        assertTrue(!doneCalled.get());
        assertTrue(error.get() instanceof ApiClient.Failure);
        ApiClient.Failure failure = (ApiClient.Failure) error.get();
        assertEquals(200, failure.status);
        assertEquals("MODEL_TIMEOUT", failure.code);
        assertEquals("AI 助教响应超时，请稍后重试。", failure.getMessage());
    }

    @Test
    public void prematureStreamCloseReportsIoException() throws Exception {
        String sse = "event: meta\n"
                + "data: {\"mode\":\"demo\",\"contextType\":\"WORD\"}\n\n"
                + "event: delta\n"
                + "data: {\"text\":\"partial\"}\n\n";

        StringBuilder text = new StringBuilder();
        AtomicReference<Exception> error = new AtomicReference<>();

        ApiClient.parseTutorSseStream(
                new BufferedReader(new StringReader(sse)),
                () -> false,
                new ApiClient.TutorStreamListener() {
                    @Override public void onMeta(String m, String ct) {}
                    @Override public void onDelta(String t) { text.append(t); }
                    @Override public void onDone(List<String> f) {}
                    @Override public void onError(Exception failure) { error.set(failure); }
                }
        );

        assertEquals("partial", text.toString());
        assertNotNull(error.get());
        assertTrue(error.get() instanceof IOException);
        assertEquals("stream closed", error.get().getMessage());
    }

    @Test
    public void cancellationSuppressesAllSubsequentCallbacks() throws Exception {
        String sse = "event: delta\n"
                + "data: {\"text\":\"first\"}\n\n"
                + "event: delta\n"
                + "data: {\"text\":\"second\"}\n\n"
                + "event: done\n"
                + "data: {\"followUps\":[\"q1\"]}\n\n";

        AtomicBoolean cancelled = new AtomicBoolean(false);
        List<String> received = new ArrayList<>();
        AtomicBoolean doneCalled = new AtomicBoolean();
        AtomicReference<Exception> error = new AtomicReference<>();

        ApiClient.parseTutorSseStream(
                new BufferedReader(new StringReader(sse)),
                cancelled::get,
                new ApiClient.TutorStreamListener() {
                    @Override public void onMeta(String m, String ct) {}

                    @Override
                    public void onDelta(String t) {
                        received.add(t);
                        cancelled.set(true);
                    }

                    @Override public void onDone(List<String> f) { doneCalled.set(true); }
                    @Override public void onError(Exception failure) { error.set(failure); }
                }
        );

        assertEquals(List.of("first"), received);
        assertTrue(!doneCalled.get());
        assertNull(error.get());
    }

    @Test
    public void tutorContextSessionKeySummaryAndQuickPrompts() {
        TutorContext free = TutorContext.free();
        assertEquals("free", free.sessionKey());
        assertTrue(free.summary().length() <= 60);
        assertTrue(free.quickPrompts().size() >= 3);

        TutorContext word = new TutorContext(TutorContext.WORD, "abandon", "abandon", "ex", "note");
        assertEquals("WORD:" + "abandon".hashCode(), word.sessionKey());
        assertTrue(word.quickPrompts().size() >= 3);

        String longSentence = "A".repeat(120);
        TutorContext sentence = new TutorContext(TutorContext.SENTENCE, "长难句", longSentence, "", "");
        assertEquals(60, sentence.summary().length());
        assertTrue(sentence.summary().endsWith("…"));
    }
}
