package com.cethelper.server.dictionary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cethelper.server.support.ApiTestSupport;
import com.cethelper.server.support.FakeOpenAiServer;
import com.cethelper.server.support.FakeOpenAiServer.Mode;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** live 模式查词契约测试：本地命中不请求上游、未命中走假上游、上游错误/超时映射。 */
@SpringBootTest(properties = {"cet.explanation.mode=live"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("POST /api/v1/dictionary/lookup（live）")
class DictionaryLookupLiveApiTest {

    private static final String FAKE_API_KEY = "test-key-not-real";

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
        registry.add("cet.explanation.read-timeout", () -> "PT0.4S");
        registry.add("cet.explanation.connect-timeout", () -> "PT1S");
    }

    @AfterAll
    static void stopUpstream() {
        UPSTREAM.close();
    }

    @Autowired
    private MockMvc mockMvc;

    private ApiTestSupport.Student student;

    @BeforeEach
    void reset() throws Exception {
        UPSTREAM.mode(Mode.SUCCESS).slowMillis(0).content(null);
        student = ApiTestSupport.newStudent(mockMvc);
    }

    @Test
    @DisplayName("本地词表命中时直接返回 mode=live，不调用上游")
    void localHitDoesNotCallUpstream() throws Exception {
        int before = UPSTREAM.requestCount();

        JsonNode json = ApiTestSupport.body(ApiTestSupport
                .dictionaryLookup(mockMvc, student.token(), "decline")
                .andExpect(status().isOk())
                .andReturn());

        assertThat(json.get("mode").asText()).isEqualTo("live");
        assertThat(json.get("lemma").asText()).isEqualTo("decline");
        assertThat(json.get("exampleSentence").asText()).isNotBlank();
        assertThat(UPSTREAM.requestCount())
                .as("本地命中不应访问上游")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("本地未命中时调用假上游，路径为 /v1/chat/completions，密钥放在 Authorization 头")
    void localMissCallsUpstreamWithCorrectPath() throws Exception {
        int before = UPSTREAM.requestCount();

        JsonNode json = ApiTestSupport.body(ApiTestSupport
                .dictionaryLookup(mockMvc, student.token(), "quizzacious")
                .andExpect(status().isOk())
                .andReturn());

        assertThat(json.get("word").asText()).isEqualTo("quizzacious");
        assertThat(json.get("lemma").asText()).isEqualTo("quizzacious");
        assertThat(json.get("mode").asText()).isEqualTo("live");
        assertThat(json.get("meaning").asText()).isNotBlank();
        assertThat(json.get("explanation").asText()).isNotBlank();

        assertThat(UPSTREAM.requestCount()).isEqualTo(before + 1);
        assertThat(UPSTREAM.lastRequestPath())
                .as("baseUrl 以 /v1 结尾时不得拼出 /v1/v1/chat/completions")
                .isEqualTo("/v1/chat/completions");
        assertThat(UPSTREAM.lastAuthorization()).isEqualTo("Bearer " + FAKE_API_KEY);
        assertThat(UPSTREAM.lastRequestBody()).contains("quizzacious");
        assertThat(UPSTREAM.lastRequestBody()).doesNotContain(FAKE_API_KEY);
    }

    @Test
    @DisplayName("上游返回完整 JSON（含代码块包裹）时透传全部字段")
    void upstreamPayloadIsParsed() throws Exception {
        UPSTREAM.content("```json\n{\"word\":\"zephyr\",\"lemma\":\"zephyr\",\"phonetic\":\"/ˈzefə/\","
                + "\"partOfSpeech\":\"n.\",\"cetLevel\":\"cet-6\",\"meaning\":\"和风；西风\","
                + "\"explanation\":\"文学语境中常指轻柔的风。\","
                + "\"exampleSentence\":\"A warm zephyr blew across the field.\","
                + "\"exampleTranslation\":\"一阵温暖的和风拂过田野。\"}\n```");

        JsonNode json = ApiTestSupport.body(ApiTestSupport
                .dictionaryLookup(mockMvc, student.token(), "zephyr")
                .andExpect(status().isOk())
                .andReturn());

        assertThat(json.get("lemma").asText()).isEqualTo("zephyr");
        assertThat(json.get("phonetic").asText()).isEqualTo("/ˈzefə/");
        assertThat(json.get("partOfSpeech").asText()).isEqualTo("n.");
        assertThat(json.get("cetLevel").asText()).isEqualTo("CET-6");
        assertThat(json.get("meaning").asText()).isEqualTo("和风；西风");
        assertThat(json.get("exampleSentence").asText()).contains("zephyr");
        assertThat(json.get("exampleTranslation").asText()).isNotBlank();
        assertThat(json.get("mode").asText()).isEqualTo("live");
    }

    @Test
    @DisplayName("上游 500 → 502 MODEL_ERROR，且不外泄上游正文与密钥")
    void upstreamErrorReturnsModelError() throws Exception {
        UPSTREAM.mode(Mode.ERROR);

        MvcResult result = ApiTestSupport.dictionaryLookup(mockMvc, student.token(), "quizzacious")
                .andExpect(status().isBadGateway())
                .andReturn();
        assertErrorBody(result, "MODEL_ERROR");
        assertThat(ApiTestSupport.bodyText(result))
                .doesNotContain(FakeOpenAiServer.LEAK_MARKER)
                .doesNotContain(FAKE_API_KEY)
                .doesNotContain("Exception");
    }

    @Test
    @DisplayName("上游返回不可解析内容 → 502 MODEL_ERROR")
    void garbageContentReturnsModelError() throws Exception {
        UPSTREAM.mode(Mode.GARBAGE);

        MvcResult result = ApiTestSupport.dictionaryLookup(mockMvc, student.token(), "quizzacious")
                .andExpect(status().isBadGateway())
                .andReturn();
        assertErrorBody(result, "MODEL_ERROR");
    }

    @Test
    @DisplayName("上游响应超过读超时 → 504 MODEL_TIMEOUT")
    void slowUpstreamReturnsModelTimeout() throws Exception {
        UPSTREAM.mode(Mode.SLOW).slowMillis(1500);

        MvcResult result = ApiTestSupport.dictionaryLookup(mockMvc, student.token(), "quizzacious")
                .andExpect(status().isGatewayTimeout())
                .andReturn();
        assertErrorBody(result, "MODEL_TIMEOUT");

        UPSTREAM.slowMillis(0).mode(Mode.SUCCESS);
    }

    private static void assertErrorBody(MvcResult result, String expectedCode) throws Exception {
        JsonNode json = ApiTestSupport.body(result);
        assertThat(json.get("code").asText()).isEqualTo(expectedCode);
        assertThat(json.get("message").asText()).isNotBlank();
    }
}
