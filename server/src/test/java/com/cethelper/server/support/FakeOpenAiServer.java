package com.cethelper.server.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本地假上游：模拟兼容 OpenAI Chat Completions 的服务，用于在没有真实模型凭据时验证 live 模式。
 *
 * <p>语境释义用到四种行为：正常返回、上游 500（正文含诱饵密钥）、返回不可解析内容、响应过慢以触发读超时。
 * 整句分析另有五种行为：正常拆解、片段顺序颠倒、片段不在原句中、片段缺字段、chunks 为空。</p>
 */
public final class FakeOpenAiServer implements AutoCloseable {

    public enum Mode {
        /** 返回合法的 chat.completions 结构，content 为约定 JSON。 */
        SUCCESS,
        /** 返回 HTTP 500，正文包含诱饵密钥，用于验证错误响应不会外泄上游内容。 */
        ERROR,
        /** 返回合法结构但 content 不是 JSON，用于验证解析失败按 502 处理。 */
        GARBAGE,
        /** 延迟响应，用于触发客户端读超时。 */
        SLOW,
        /** 整句分析：返回合法的 translation/structure/chunks。 */
        SENTENCE_SUCCESS,
        /** 整句分析：chunks 顺序与原文相反，服务端应重排成原句顺序。 */
        SENTENCE_OUT_OF_ORDER,
        /** 整句分析：某个片段不是原句中的连续原文。 */
        SENTENCE_CHUNK_MISMATCH,
        /** 整句分析：某个片段缺少 explanation 字段。 */
        SENTENCE_MISSING_FIELD,
        /** 整句分析：chunks 为空数组。 */
        SENTENCE_EMPTY_CHUNKS,
        /** 整句分析：同一句里两个 that 各引出一个宾语从句，六个片段按原句顺序返回。 */
        SENTENCE_DOUBLE_THAT
    }

    /** 整句分析用例使用的英文长句：假上游返回的片段全部摘自这一句。 */
    public static final String SENTENCE = "Although the new library was built with a limited budget, "
            + "students who use it every day say that it has become the quietest place on campus.";

    /** 假上游返回的译文，用于核验服务端确实透传了模型结果。 */
    public static final String SENTENCE_TRANSLATION = "虽然新图书馆是在有限的预算下建成的，但每天使用它的学生说，它已经成为校园里最安静的地方。";

    /** 假上游返回的主干说明。 */
    public static final String SENTENCE_STRUCTURE = "主干是 students say that ...；Although 从句交代背景，who 从句修饰 students。";

    /** 双 that 用例的原句：两个 that 分别引出一个并列的宾语从句。 */
    public static final String DOUBLE_THAT_SENTENCE = "They said that practice matters and that patience helps.";

    /** 只有一个 that 的对照句：上游若仍返回两个 that，服务端应判定为无法定位。 */
    public static final String SINGLE_THAT_SENTENCE = "They said that practice matters and patience helps.";

    /** 上游正文里的诱饵密钥：任何响应体都不应出现该字符串。 */
    public static final String LEAK_MARKER = "sk-UPSTREAM-SECRET-should-never-leak";

    /** 流式请求返回的固定答复，供 AI 助教 live 测试断言 delta 拼接结果。 */
    public static final String STREAM_REPLY = "这是本地假上游返回的流式答复。";

    private static final String STREAM_MODEL = "fake-model";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpServer server;
    private final ExecutorService executor;
    private volatile Mode mode = Mode.SUCCESS;
    private volatile long slowMillis = 1500;
    private volatile String lastRequestBody = "";
    private volatile String lastAuthorization = "";
    private volatile String lastRequestPath = "";
    /** 非空时直接用这段文本作为 content，用于在用例里临时构造任意返回内容。 */
    private volatile String customContent;
    private final AtomicInteger requestCount = new AtomicInteger();

    private FakeOpenAiServer(HttpServer server, ExecutorService executor) {
        this.server = server;
        this.executor = executor;
    }

    public static FakeOpenAiServer start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService executor = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "fake-openai");
            thread.setDaemon(true);
            return thread;
        });
        FakeOpenAiServer fake = new FakeOpenAiServer(server, executor);
        server.createContext("/v1/chat/completions", fake::handle);
        server.setExecutor(executor);
        server.start();
        return fake;
    }

    /** 形如 http://127.0.0.1:PORT/v1，作为 CET_MODEL_BASE_URL 的值。 */
    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    public FakeOpenAiServer mode(Mode newMode) {
        this.mode = newMode;
        return this;
    }

    public FakeOpenAiServer slowMillis(long millis) {
        this.slowMillis = millis;
        return this;
    }

    /** 指定上游 content 原文；传 null 恢复按 mode 生成内容。 */
    public FakeOpenAiServer content(String newContent) {
        this.customContent = newContent;
        return this;
    }

    public String lastRequestBody() {
        return lastRequestBody;
    }

    public String lastAuthorization() {
        return lastAuthorization;
    }

    public String lastRequestPath() {
        return lastRequestPath;
    }

    public int requestCount() {
        return requestCount.get();
    }

    private void handle(HttpExchange exchange) throws IOException {
        lastRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
        lastRequestPath = exchange.getRequestURI().getPath();
        requestCount.incrementAndGet();
        boolean streaming = wantsStreaming(lastRequestBody);

        Mode current = mode;
        try {
            if (current == Mode.SLOW) {
                Thread.sleep(slowMillis);
            }
            if (current == Mode.ERROR) {
                write(exchange, 500, "{\"error\":{\"message\":\"" + LEAK_MARKER + "\",\"type\":\"server_error\"}}");
                return;
            }
            if (streaming) {
                String custom = customContent;
                writeStream(exchange, custom != null ? custom : STREAM_REPLY);
                return;
            }
            if (current == Mode.GARBAGE) {
                write(exchange, 200, envelope("抱歉，这不是约定的 JSON 结构。"));
                return;
            }
            String custom = customContent;
            if (custom != null) {
                write(exchange, 200, envelope(custom));
                return;
            }
            if (isSentenceMode(current)) {
                write(exchange, 200, envelope(sentenceAnalysisContent(current)));
                return;
            }
            write(exchange, 200, envelope("{\"meaning\":\"婉拒\",\"explanation\":\"本地替身上游返回的语境释义。\"}"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // 客户端超时断开时写响应会失败，属于预期情况。
        }
    }

    /** 请求体里 {@code "stream":true} 时走 OpenAI SSE 流式响应。 */
    private static boolean wantsStreaming(String requestBody) {
        if (requestBody == null || requestBody.isBlank()) {
            return false;
        }
        try {
            return MAPPER.readTree(requestBody).path("stream").asBoolean(false);
        } catch (IOException e) {
            return false;
        }
    }

    /** 按 OpenAI Chat Completions 的 SSE 格式分块返回 content，最后以 [DONE] 结束。 */
    private static void writeStream(HttpExchange exchange, String reply) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            for (String piece : split(reply, 6)) {
                Map<String, Object> delta = new LinkedHashMap<>();
                delta.put("content", piece);
                Map<String, Object> choice = new LinkedHashMap<>();
                choice.put("index", 0);
                choice.put("delta", delta);
                choice.put("finish_reason", null);
                Map<String, Object> chunk = new LinkedHashMap<>();
                chunk.put("id", "chatcmpl-fake-stream");
                chunk.put("object", "chat.completion.chunk");
                chunk.put("model", STREAM_MODEL);
                chunk.put("choices", List.of(choice));
                out.write(("data: " + MAPPER.writeValueAsString(chunk) + "\n\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    private static List<String> split(String text, int size) {
        List<String> pieces = new ArrayList<>();
        int index = 0;
        while (index < text.length()) {
            int end = Math.min(text.length(), index + size);
            pieces.add(text.substring(index, end));
            index = end;
        }
        return pieces;
    }

    private static boolean isSentenceMode(Mode mode) {
        return switch (mode) {
            case SENTENCE_SUCCESS, SENTENCE_OUT_OF_ORDER, SENTENCE_CHUNK_MISMATCH,
                 SENTENCE_MISSING_FIELD, SENTENCE_EMPTY_CHUNKS, SENTENCE_DOUBLE_THAT -> true;
            default -> false;
        };
    }

    /**
     * 按模式生成整句分析的 content。
     *
     * <p>片段文本全部摘自 {@link #SENTENCE}；CHUNK_MISMATCH 与 MISSING_FIELD 各故意弄坏一处，
     * OUT_OF_ORDER 把片段倒序，用于验证服务端的排序与校验。</p>
     */
    private static String sentenceAnalysisContent(Mode mode) throws IOException {
        Map<String, Object> chunkOne = chunk("Although the new library was built with a limited budget",
                "让步状语从句", "先用 Although 交代「预算有限」这个背景，读主干时可以跳过。");
        Map<String, Object> chunkTwo = chunk("students who use it every day say",
                "主句主语 + 谓语（含 who 定语从句）", "主干在这里：students 是主语，say 是谓语，who 从句只是补充说明学生。");
        Map<String, Object> chunkThree = chunk("that it has become the quietest place on campus",
                "say 的宾语从句", "学生说出的内容：它已经成为校园里最安静的地方。");

        List<Map<String, Object>> chunks = new ArrayList<>();
        switch (mode) {
            case SENTENCE_OUT_OF_ORDER -> {
                chunks.add(chunkThree);
                chunks.add(chunkTwo);
                chunks.add(chunkOne);
            }
            case SENTENCE_CHUNK_MISMATCH -> {
                chunks.add(chunkOne);
                chunks.add(chunk("this fragment is not in the sentence", "主语", "假上游故意给一个原句里找不到的片段。"));
                chunks.add(chunkThree);
            }
            case SENTENCE_MISSING_FIELD -> {
                chunks.add(chunkOne);
                Map<String, Object> missing = new LinkedHashMap<>();
                missing.put("text", "students who use it every day say");
                missing.put("role", "主句主语 + 谓语");
                chunks.add(missing);
            }
            case SENTENCE_EMPTY_CHUNKS -> {
                // 故意留空
            }
            case SENTENCE_DOUBLE_THAT -> {
                // 两个 that 各引出一个宾语从句，说明文字可区分，便于断言逐段保留。
                chunks.add(chunk("They said", "主句主语 + 谓语",
                        "主句是 They said，后面用两个 that 从句说明「说了什么」。"));
                chunks.add(chunk("that", "第一个宾语从句的引导词",
                        "第一个 that 引出「练习很重要」这个宾语从句。"));
                chunks.add(chunk("practice matters", "第一个宾语从句的主语 + 谓语",
                        "第一个从句的主干：practice 作主语，matters 作谓语，意思是「练习很重要」。"));
                chunks.add(chunk("and", "并列连词",
                        "连接前后两个 that 宾语从句，说明这是并列的两件事。"));
                chunks.add(chunk("that", "第二个宾语从句的引导词",
                        "第二个 that 引出「耐心有帮助」，与第一个 that 是不同的从句。"));
                chunks.add(chunk("patience helps", "第二个宾语从句的主语 + 谓语",
                        "第二个从句的主干：patience 作主语，helps 作谓语，意思是「耐心有帮助」。"));
            }
            default -> {
                chunks.add(chunkOne);
                chunks.add(chunkTwo);
                chunks.add(chunkThree);
            }
        }

        String translation = SENTENCE_TRANSLATION;
        String structure = SENTENCE_STRUCTURE;
        if (mode == Mode.SENTENCE_DOUBLE_THAT) {
            translation = "他们说练习很重要，而且耐心也有帮助。";
            structure = "主干是 They said + 两个由 that 引导的并列宾语从句：第一个说练习很重要，第二个说耐心有帮助。";
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("translation", translation);
        payload.put("structure", structure);
        payload.put("chunks", chunks);
        return MAPPER.writeValueAsString(payload);
    }

    private static Map<String, Object> chunk(String text, String role, String explanation) {
        Map<String, Object> chunk = new LinkedHashMap<>();
        chunk.put("text", text);
        chunk.put("role", role);
        chunk.put("explanation", explanation);
        return chunk;
    }

    private static String envelope(String content) throws IOException {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", content);
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("message", message);
        choice.put("finish_reason", "stop");
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("id", "chatcmpl-fake");
        root.put("object", "chat.completion");
        root.put("model", "fake-model");
        root.put("choices", java.util.List.of(choice));
        return MAPPER.writeValueAsString(root);
    }

    private static void write(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
