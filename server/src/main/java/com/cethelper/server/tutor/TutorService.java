package com.cethelper.server.tutor;

import com.cethelper.server.ai.AiFailures;
import com.cethelper.server.common.ApiException;
import com.cethelper.server.config.CetProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

/**
 * AI 助教服务：同步校验、demo/live 生产者选择、SSE 事件顺序与生命周期管理。
 *
 * <p>事件严格按 {@code meta} → {@code delta} × N → {@code done} 或 {@code error} 发送，
 * 每个事件只有一行 JSON {@code data}，随后关闭 emitter。客户端断开时静默停止，
 * 不把 {@link IOException} 抛给全局异常处理器。</p>
 */
@Service
public class TutorService {

    private static final Logger log = LoggerFactory.getLogger(TutorService.class);

    private static final int MAX_MESSAGES = 40;
    private static final int MAX_MESSAGE_LENGTH = 4000;
    private static final int MAX_TITLE_LENGTH = 200;
    private static final int MAX_FOCUS_LENGTH = 2000;
    private static final int MAX_REFERENCE_LENGTH = 6000;
    private static final int MAX_EXTRA_NOTE_LENGTH = 4000;
    private static final int CHUNK_SIZE = 12;

    private static final Set<String> CONTEXT_TYPES = Set.of(
            TutorContextPayload.FREE, TutorContextPayload.WORD, TutorContextPayload.SENTENCE,
            TutorContextPayload.TRANSLATION, TutorContextPayload.WRITING);

    private static final String MODEL_ERROR_MESSAGE = "AI 助教暂时不可用，请稍后再试。";
    private static final String MODEL_TIMEOUT_MESSAGE = "AI 助教响应超时，请稍后重试。";

    private final DemoTutorCatalog demoCatalog;
    private final LiveTutorStreamer liveStreamer;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;
    private final boolean liveMode;
    private final Duration readTimeout;

    /** 测试可置 0，避免 30ms × N 的等待拖慢用例。 */
    private volatile long chunkDelayMillis = 30;

    public TutorService(DemoTutorCatalog demoCatalog,
                        ObjectProvider<LiveTutorStreamer> liveStreamerProvider,
                        CetProperties properties,
                        ObjectMapper objectMapper,
                        @Qualifier("tutorStreamExecutor") ExecutorService executor) {
        CetProperties.Explanation config = properties.getExplanation();
        this.liveMode = config.isLive();
        this.demoCatalog = demoCatalog;
        this.liveStreamer = liveStreamerProvider.getIfAvailable();
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.readTimeout = config.getReadTimeout();
        if (liveMode && liveStreamer == null) {
            throw new IllegalStateException("live 模式未初始化 AI 助教流式客户端，请检查 CET_MODEL_* 配置。");
        }
        log.info("AI 助教模式：{}", liveMode ? "live（在线模型）" : "demo（内置答复）");
    }

    /** 同步校验；通过时返回规范化后的上下文。校验失败抛 400 INVALID_INPUT。 */
    public TutorContextPayload validate(TutorChatRequest request) {
        if (request == null) {
            throw ApiException.invalidInput("请求体不能为空。");
        }
        List<TutorChatMessage> messages = request.messages();
        if (messages == null || messages.isEmpty()) {
            throw ApiException.invalidInput("messages 不能为空。");
        }
        if (messages.size() > MAX_MESSAGES) {
            throw ApiException.invalidInput("messages 最多 40 条。");
        }
        for (TutorChatMessage message : messages) {
            if (message == null) {
                throw ApiException.invalidInput("messages 中不能包含空元素。");
            }
            if (!"user".equals(message.role()) && !"assistant".equals(message.role())) {
                throw ApiException.invalidInput("消息 role 只能为 user 或 assistant。");
            }
            if (message.content() == null) {
                throw ApiException.invalidInput("消息 content 不能为空。");
            }
            if (message.content().length() > MAX_MESSAGE_LENGTH) {
                throw ApiException.invalidInput("单条消息不能超过 4000 个字符。");
            }
        }
        TutorChatMessage last = messages.get(messages.size() - 1);
        if (!"user".equals(last.role())) {
            throw ApiException.invalidInput("最后一条消息必须来自用户。");
        }
        if (last.content().strip().isEmpty()) {
            throw ApiException.invalidInput("最后一条用户消息不能为空。");
        }
        return normalizeContext(request.context());
    }

    /** 校验通过后创建 emitter；meta 与后续事件在独立线程中推送。 */
    public SseEmitter stream(List<TutorChatMessage> messages, TutorContextPayload context) {
        long timeoutMillis = Math.max(60_000L, readTimeout.toMillis() + 30_000L);
        SseEmitter emitter = new SseEmitter(timeoutMillis);
        StreamState state = new StreamState();
        emitter.onTimeout(state::cancel);
        emitter.onError(error -> state.cancel());
        emitter.onCompletion(state::cancel);

        List<TutorChatMessage> history = List.copyOf(messages);
        executor.submit(() -> {
            try {
                send(emitter, state, "meta", Map.of(
                        "mode", liveMode ? "live" : "demo",
                        "contextType", context.type()));
                if (state.cancelled()) {
                    return;
                }
                if (liveMode) {
                    pushLive(emitter, state, context, history);
                } else {
                    pushDemo(emitter, state, context, history);
                }
            } catch (RuntimeException ex) {
                log.warn("AI 助教推送初始化失败：{}", ex.getClass().getSimpleName());
                pushError(emitter, state, "MODEL_ERROR", MODEL_ERROR_MESSAGE);
                completeQuietly(emitter, state);
            }
        });
        return emitter;
    }

    private void pushDemo(SseEmitter emitter, StreamState state,
                          TutorContextPayload context, List<TutorChatMessage> messages) {
        try {
            String lastUserMessage = messages.get(messages.size() - 1).content().strip();
            DemoTutorCatalog.DemoReply reply = demoCatalog.reply(context, lastUserMessage);
            for (String chunk : chunks(reply.text())) {
                if (state.cancelled()) {
                    return;
                }
                send(emitter, state, "delta", Map.of("text", chunk));
                if (chunkDelayMillis > 0) {
                    Thread.sleep(chunkDelayMillis);
                }
            }
            if (!state.cancelled()) {
                send(emitter, state, "done", Map.of("followUps", reply.followUps()));
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException ex) {
            log.warn("AI 助教 demo 推送失败：{}", ex.getClass().getSimpleName());
            pushError(emitter, state, "MODEL_ERROR", MODEL_ERROR_MESSAGE);
        } finally {
            completeQuietly(emitter, state);
        }
    }

    private void pushLive(SseEmitter emitter, StreamState state,
                          TutorContextPayload context, List<TutorChatMessage> messages) {
        Flux<String> tokens = liveStreamer.stream(context, messages).timeout(readTimeout);
        Disposable subscription = tokens.subscribe(
                token -> {
                    if (token != null && !token.isEmpty() && !state.cancelled()) {
                        send(emitter, state, "delta", Map.of("text", token));
                    }
                },
                error -> {
                    if (!state.cancelled()) {
                        if (AiFailures.isTimeout(error)) {
                            pushError(emitter, state, "MODEL_TIMEOUT", MODEL_TIMEOUT_MESSAGE);
                        } else {
                            log.warn("AI 助教上游失败：{}", error.getClass().getSimpleName());
                            pushError(emitter, state, "MODEL_ERROR", MODEL_ERROR_MESSAGE);
                        }
                    }
                    completeQuietly(emitter, state);
                },
                () -> {
                    if (!state.cancelled()) {
                        send(emitter, state, "done", Map.of(
                                "followUps", demoCatalog.followUps(context)));
                    }
                    completeQuietly(emitter, state);
                });
        state.attach(subscription);
    }

    private boolean send(SseEmitter emitter, StreamState state, String event, Object payload) {
        if (state.cancelled()) {
            return false;
        }
        try {
            String json = objectMapper.writeValueAsString(payload);
            emitter.send(SseEmitter.event().name(event).data(json, MediaType.APPLICATION_JSON));
            return true;
        } catch (JsonProcessingException ex) {
            log.warn("AI 助教事件序列化失败：{}", ex.getClass().getSimpleName());
            state.cancel();
            return false;
        } catch (IOException | IllegalStateException ex) {
            // 客户端断开或 emitter 已结束：静默停止，绝不抛给全局异常处理器。
            state.cancel();
            return false;
        }
    }

    private void pushError(SseEmitter emitter, StreamState state, String code, String message) {
        send(emitter, state, "error", Map.of("code", code, "message", message));
    }

    private static void completeQuietly(SseEmitter emitter, StreamState state) {
        if (state.cancelled()) {
            return;
        }
        try {
            emitter.complete();
        } catch (RuntimeException ignored) {
            // 客户端已断开，无需再通知。
        }
    }

    private static List<String> chunks(String text) {
        List<String> result = new ArrayList<>();
        String source = text == null ? "" : text;
        int index = 0;
        while (index < source.length()) {
            int end = Math.min(source.length(), index + CHUNK_SIZE);
            result.add(source.substring(index, end));
            index = end;
        }
        if (result.isEmpty()) {
            result.add("");
        }
        return result;
    }

    private TutorContextPayload normalizeContext(TutorContextPayload context) {
        if (context == null) {
            return TutorContextPayload.free();
        }
        String type = context.type() == null || context.type().isBlank()
                ? TutorContextPayload.FREE
                : context.type().strip().toUpperCase(Locale.ROOT);
        if (!CONTEXT_TYPES.contains(type)) {
            throw ApiException.invalidInput(
                    "context.type 只能为 FREE、WORD、SENTENCE、TRANSLATION 或 WRITING。");
        }
        requireMaxLength("context.title", context.title(), MAX_TITLE_LENGTH);
        requireMaxLength("context.focusText", context.focusText(), MAX_FOCUS_LENGTH);
        requireMaxLength("context.referenceText", context.referenceText(), MAX_REFERENCE_LENGTH);
        requireMaxLength("context.extraNote", context.extraNote(), MAX_EXTRA_NOTE_LENGTH);
        return new TutorContextPayload(type, context.title(), context.focusText(),
                context.referenceText(), context.extraNote());
    }

    private static void requireMaxLength(String field, String value, int maxLength) {
        if (value != null && value.length() > maxLength) {
            throw ApiException.invalidInput(field + " 不能超过 " + maxLength + " 个字符。");
        }
    }

    /** 仅供同包测试把分块间隔置 0。 */
    void setChunkDelayMillis(long millis) {
        this.chunkDelayMillis = Math.max(0, millis);
    }

    /** 单次流式请求的取消状态与订阅句柄。 */
    private static final class StreamState {

        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile Disposable disposable;

        void cancel() {
            cancelled.set(true);
            Disposable current = disposable;
            if (current != null) {
                current.dispose();
            }
        }

        void attach(Disposable subscription) {
            this.disposable = subscription;
            if (cancelled.get()) {
                subscription.dispose();
            }
        }

        boolean cancelled() {
            return cancelled.get();
        }
    }
}
