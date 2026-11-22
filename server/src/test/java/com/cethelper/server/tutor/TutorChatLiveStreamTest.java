package com.cethelper.server.tutor;

import static org.assertj.core.api.Assertions.assertThat;

import com.cethelper.server.support.ApiTestSupport;
import com.cethelper.server.support.FakeOpenAiServer;
import com.cethelper.server.support.FakeOpenAiServer.Mode;
import com.cethelper.server.support.RealServerSupport;
import com.cethelper.server.support.SseClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** live 模式 AI 助教 SSE 测试：真实端口 + 假上游的 OpenAI 流式 chunk。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"cet.explanation.mode=live"})
@ActiveProfiles("test")
@DisplayName("POST /api/v1/tutor/chat/stream（live）")
class TutorChatLiveStreamTest {

    private static final String FAKE_API_KEY = "test-key-not-real";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private static final FakeOpenAiServer UPSTREAM = startUpstream();

    private static FakeOpenAiServer startUpstream() {
        try {
            return FakeOpenAiServer.start();
        } catch (IOException e) {
            throw new IllegalStateException("本地假上游启动失败", e);
        }
    }

    @DynamicPropertySource
    static void upstreamProperties(DynamicPropertyRegistry registry) {
        registry.add("cet.explanation.base-url", UPSTREAM::baseUrl);
        registry.add("cet.explanation.model", () -> "fake-model");
        registry.add("cet.explanation.api-key", () -> FAKE_API_KEY);
        registry.add("cet.explanation.read-timeout", () -> "PT1S");
        registry.add("cet.explanation.connect-timeout", () -> "PT1S");
    }

    @AfterAll
    static void stopUpstream() {
        UPSTREAM.close();
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    private RealServerSupport.Student student;

    @BeforeEach
    void prepare() {
        UPSTREAM.mode(Mode.SUCCESS).slowMillis(0).content(null);
        student = RealServerSupport.newStudent(rest);
    }

    @Test
    @DisplayName("delta 拼接结果等于上游内容，事件顺序 meta → delta → done")
    void streamsUpstreamDeltasInOrder() throws Exception {
        int before = UPSTREAM.requestCount();

        SseClient.Response response = SseClient.post(HTTP, url(), student.token(), wordPayload());

        assertThat(response.status()).isEqualTo(200);
        List<String> names = response.names();
        assertThat(names.get(0)).isEqualTo("meta");
        assertThat(names.get(names.size() - 1)).isEqualTo("done");
        assertThat(names.subList(1, names.size() - 1)).allMatch("delta"::equals);

        JsonNode meta = response.first("meta").json();
        assertThat(meta.get("mode").asText()).isEqualTo("live");
        assertThat(meta.get("contextType").asText()).isEqualTo("WORD");

        assertThat(response.deltaText()).isEqualTo(FakeOpenAiServer.STREAM_REPLY);
        assertThat(response.followUps()).isNotEmpty();

        assertThat(UPSTREAM.requestCount()).isEqualTo(before + 1);
        assertThat(UPSTREAM.lastRequestPath())
                .as("请求必须命中 /v1/chat/completions，不能出现 /v1/v1")
                .isEqualTo("/v1/chat/completions");
        assertThat(UPSTREAM.lastRequestBody()).contains("\"stream\":true");
        assertThat(UPSTREAM.lastRequestBody()).contains("abandon");
        assertThat(UPSTREAM.lastAuthorization()).isEqualTo("Bearer " + FAKE_API_KEY);
        assertThat(UPSTREAM.lastRequestBody()).doesNotContain(FAKE_API_KEY);
    }

    @Test
    @DisplayName("上游返回 500 时推送 event: error（MODEL_ERROR），HTTP 仍为 200 且无 done")
    void upstreamErrorPushesErrorEvent() throws Exception {
        UPSTREAM.mode(Mode.ERROR);

        SseClient.Response response = SseClient.post(HTTP, url(), student.token(), wordPayload());

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.names()).contains("meta", "error");
        assertThat(response.names()).doesNotContain("done");
        assertThat(response.names().get(response.names().size() - 1)).isEqualTo("error");

        JsonNode error = response.first("error").json();
        assertThat(error.get("code").asText()).isEqualTo("MODEL_ERROR");
        assertThat(error.get("message").asText()).isNotBlank();
        assertThat(error.get("message").asText()).contains("AI 助教");
    }

    @Test
    @DisplayName("上游超时推送 MODEL_TIMEOUT")
    void upstreamTimeoutPushesTimeoutEvent() throws Exception {
        UPSTREAM.mode(Mode.SLOW).slowMillis(2500);

        SseClient.Response response = SseClient.post(HTTP, url(), student.token(), wordPayload());

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.names()).contains("meta", "error");
        assertThat(response.names()).doesNotContain("done");
        JsonNode error = response.first("error").json();
        assertThat(error.get("code").asText()).isEqualTo("MODEL_TIMEOUT");
        assertThat(error.get("message").asText()).isEqualTo("AI 助教响应超时，请稍后重试。");

        UPSTREAM.slowMillis(0).mode(Mode.SUCCESS);
    }

    private String url() {
        return "http://127.0.0.1:" + port + "/api/v1/tutor/chat/stream";
    }

    private static String wordPayload() {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("type", "WORD");
        context.put("focusText", "abandon");
        context.put("referenceText", "They had to abandon the car in the snow.");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messages", List.of(
                Map.of("role", "user", "content", "abandon 怎么用？")));
        payload.put("context", context);
        return ApiTestSupport.json(payload);
    }
}
