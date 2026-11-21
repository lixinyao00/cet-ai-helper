package com.cethelper.server.sentence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cethelper.server.support.ApiTestSupport;
import com.cethelper.server.support.FakeOpenAiServer;
import com.cethelper.server.support.FakeOpenAiServer.Mode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
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

/**
 * 整句分析接口在 live 模式下的契约测试：用本地假上游替代真实模型服务。
 *
 * <p>覆盖：上游正常拆解、片段顺序颠倒（服务端重排）、片段不在原句中、片段缺字段、chunks 为空、
 * 上游 500、上返回不可解析内容、读超时，以及鉴权/入参校验先于上游调用、首尾空白规范化。
 * 全部请求指向本地替身，不依赖任何真实模型凭据。</p>
 */
@SpringBootTest(properties = {"cet.explanation.mode=live"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("整句分析（live 假上游）")
class SentenceAnalysisLiveApiTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 服务端应放进 Authorization 头的密钥值（假值，仅用于验证传输方式）。 */
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
        // 读超时 400ms，远小于 SLOW 模式模拟的 1.5s 延迟，可稳定触发 504。
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
    void resetUpstreamAndCreateStudent() throws Exception {
        UPSTREAM.mode(Mode.SENTENCE_SUCCESS).slowMillis(0);
        student = ApiTestSupport.newStudent(mockMvc);
    }

    @Test
    @DisplayName("上游正常返回：200、mode=live、译文与主干透传、片段按原句顺序，密钥走 Authorization 头")
    void successFromUpstreamReturnsLiveAnalysis() throws Exception {
        int before = UPSTREAM.requestCount();

        JsonNode json = expectAnalysis(FakeOpenAiServer.SENTENCE);

        assertThat(json.get("translation").asText()).isEqualTo(FakeOpenAiServer.SENTENCE_TRANSLATION);
        assertThat(json.get("structure").asText()).isEqualTo(FakeOpenAiServer.SENTENCE_STRUCTURE);

        List<String> texts = chunkTexts(json);
        assertThat(texts).as("片段应保持上游给出的原句顺序").containsExactly(
                "Although the new library was built with a limited budget",
                "students who use it every day say",
                "that it has become the quietest place on campus");

        assertThat(UPSTREAM.requestCount()).as("一次分析应只调用一次上游").isEqualTo(before + 1);
        String upstreamBody = UPSTREAM.lastRequestBody();
        assertThat(upstreamBody).as("上游请求应包含原句").contains("students who use it every day say");
        assertThat(upstreamBody).as("密钥不得以明文出现在上游请求体里").doesNotContain(FAKE_API_KEY);
        assertThat(UPSTREAM.lastAuthorization())
                .as("密钥应通过 Authorization 头传给上游")
                .isEqualTo("Bearer " + FAKE_API_KEY);
    }

    @Test
    @DisplayName("上游把片段顺序写反时，服务端按原句位置重排后再返回")
    void outOfOrderChunksAreReordered() throws Exception {
        UPSTREAM.mode(Mode.SENTENCE_OUT_OF_ORDER);

        JsonNode json = expectAnalysis(FakeOpenAiServer.SENTENCE);

        assertThat(chunkTexts(json)).as("片段必须按原句顺序返回").containsExactly(
                "Although the new library was built with a limited budget",
                "students who use it every day say",
                "that it has become the quietest place on campus");
    }

    @Test
    @DisplayName("同一句两个 that：按上游顺序逐段定位，200 且两段各自的说明保留")
    void duplicatedMarkersKeepUpstreamOrderAndExplanations() throws Exception {
        UPSTREAM.mode(Mode.SENTENCE_DOUBLE_THAT);

        JsonNode json = expectAnalysis(FakeOpenAiServer.DOUBLE_THAT_SENTENCE);

        assertThat(chunkTexts(json))
                .as("双 that 句的片段应按上游给出的顺序原样返回")
                .containsExactly("They said", "that", "practice matters", "and", "that", "patience helps");

        List<JsonNode> chunks = chunks(json);
        assertThat(chunks).as("该句应返回 6 个片段").hasSize(6);
        assertThat(chunks.get(1).get("role").asText()).as("第一个 that 的语法作用").contains("第一个");
        assertThat(chunks.get(4).get("role").asText()).as("第二个 that 的语法作用").contains("第二个");
        assertThat(chunks.get(1).get("explanation").asText()).as("第一个 that 的说明").contains("练习");
        assertThat(chunks.get(4).get("explanation").asText()).as("第二个 that 的说明").contains("耐心");
        assertThat(chunks.get(1).get("explanation").asText())
                .as("两个 that 的说明必须可区分，且不能互换")
                .isNotEqualTo(chunks.get(4).get("explanation").asText());
        assertThat(json.get("mode").asText()).isEqualTo("live");
    }

    @Test
    @DisplayName("原句只有一个 that、上游却返回两个：502 MODEL_ERROR，不硬塞片段")
    void duplicatedMarkerWithoutSecondOccurrenceReturnsModelError() throws Exception {
        UPSTREAM.mode(Mode.SENTENCE_DOUBLE_THAT);

        MvcResult result = ApiTestSupport
                .sentenceAnalysis(mockMvc, student.token(), FakeOpenAiServer.SINGLE_THAT_SENTENCE)
                .andExpect(status().isBadGateway())
                .andReturn();
        assertErrorBody(result, "MODEL_ERROR");
        assertThat(ApiTestSupport.bodyText(result)).doesNotContain(FakeOpenAiServer.LEAK_MARKER);
    }

    @Test
    @DisplayName("片段不是原句中的连续原文：502 MODEL_ERROR，且不外泄模型原文")
    void chunkNotInSentenceReturnsModelError() throws Exception {
        UPSTREAM.mode(Mode.SENTENCE_CHUNK_MISMATCH);
        assertErrorBody(expectError(status().isBadGateway()), "MODEL_ERROR");
    }

    @Test
    @DisplayName("片段缺少 explanation 字段：502 MODEL_ERROR")
    void chunkMissingFieldReturnsModelError() throws Exception {
        UPSTREAM.mode(Mode.SENTENCE_MISSING_FIELD);
        assertErrorBody(expectError(status().isBadGateway()), "MODEL_ERROR");
    }

    @Test
    @DisplayName("chunks 为空数组：502 MODEL_ERROR（不把空结果交给前端）")
    void emptyChunksReturnsModelError() throws Exception {
        UPSTREAM.mode(Mode.SENTENCE_EMPTY_CHUNKS);
        assertErrorBody(expectError(status().isBadGateway()), "MODEL_ERROR");
    }

    @Test
    @DisplayName("上游返回 500：502 MODEL_ERROR，且不外泄上游正文、密钥与堆栈")
    void upstreamErrorReturnsModelError() throws Exception {
        UPSTREAM.mode(Mode.ERROR);

        MvcResult result = expectError(status().isBadGateway());
        assertErrorBody(result, "MODEL_ERROR");

        String text = ApiTestSupport.bodyText(result);
        assertThat(text).as("不得回传上游正文中的诱饵密钥").doesNotContain(FakeOpenAiServer.LEAK_MARKER);
        assertThat(text).as("不得出现任何密钥样式的内容").doesNotContain("sk-");
        assertThat(text).as("不得回传异常类型").doesNotContain("Exception");
        assertThat(text).as("不得回传堆栈帧").doesNotContain("at com.cethelper");
        assertThat(text).as("不得回传实现细节").doesNotContain("java.");
    }

    @Test
    @DisplayName("上游 content 不是约定 JSON：502 MODEL_ERROR")
    void unparsableUpstreamContentReturnsModelError() throws Exception {
        UPSTREAM.mode(Mode.GARBAGE);
        assertErrorBody(expectError(status().isBadGateway()), "MODEL_ERROR");
    }

    @Test
    @DisplayName("上游响应超过读超时：504 MODEL_TIMEOUT，并把延迟复原")
    void slowUpstreamReturnsModelTimeout() throws Exception {
        UPSTREAM.mode(Mode.SLOW).slowMillis(1500);

        MvcResult result = expectError(status().isGatewayTimeout());
        assertErrorBody(result, "MODEL_TIMEOUT");

        UPSTREAM.slowMillis(0).mode(Mode.SENTENCE_SUCCESS);
    }

    @Test
    @DisplayName("匿名请求与非法报文在调用上游之前就被拒绝")
    void requestsRejectedBeforeUpstream() throws Exception {
        int before = UPSTREAM.requestCount();

        MvcResult anonymous = ApiTestSupport.sentenceAnalysis(mockMvc, null, FakeOpenAiServer.SENTENCE)
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertErrorBody(anonymous, "UNAUTHORIZED");

        MvcResult blank = ApiTestSupport.sentenceAnalysis(mockMvc, student.token(), "   ")
                .andExpect(status().isBadRequest())
                .andReturn();
        assertErrorBody(blank, "INVALID_INPUT");

        MvcResult wrongType = ApiTestSupport.sentenceAnalysisRaw(mockMvc, student.token(), "{\"sentence\":42}")
                .andExpect(status().isBadRequest())
                .andReturn();
        assertErrorBody(wrongType, "INVALID_INPUT");

        assertThat(UPSTREAM.requestCount())
                .as("鉴权与入参校验都应先于上游调用，假上游的请求数不应增加")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("首尾空白在调用上游前被去掉，内部空格原样保留")
    void upstreamReceivesNormalizedInput() throws Exception {
        String padded = "\t" + FakeOpenAiServer.SENTENCE + "\u3000 ";

        JsonNode json = expectAnalysis(padded);
        assertThat(json.get("sentence").asText()).isEqualTo(FakeOpenAiServer.SENTENCE);

        JsonNode request = MAPPER.readTree(UPSTREAM.lastRequestBody());
        String userContent = request.path("messages").path(1).path("content").asText();
        assertThat(userContent)
                .as("上游应当收到去掉首尾空白后的原句")
                .isEqualTo("原句：" + FakeOpenAiServer.SENTENCE);
    }

    /** 成功调用并把响应体里的 sentence/mode/字段形状断言一遍。 */
    private JsonNode expectAnalysis(String sentence) throws Exception {
        MvcResult result = ApiTestSupport.sentenceAnalysis(mockMvc, student.token(), sentence)
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = ApiTestSupport.body(result);

        assertThat(fieldNames(json))
                .as("成功体必须恰好是契约约定的五个字段")
                .containsExactlyInAnyOrder("sentence", "translation", "structure", "chunks", "mode");
        assertThat(json.get("mode").asText()).as("live 模式下 mode 必须为 live").isEqualTo("live");
        assertThat(json.get("sentence").asText()).isEqualTo(sentence.strip());
        assertThat(json.get("translation").asText()).isNotBlank();
        assertThat(json.get("structure").asText()).isNotBlank();

        List<JsonNode> chunks = chunks(json);
        assertThat(chunks).as("chunks 不能为空").isNotEmpty();
        for (JsonNode chunk : chunks) {
            assertThat(fieldNames(chunk))
                    .as("每个片段必须只有 text/role/explanation 三个字段")
                    .containsExactlyInAnyOrder("text", "role", "explanation");
            assertThat(chunk.get("text").asText()).isNotBlank();
            assertThat(chunk.get("role").asText()).isNotBlank();
            assertThat(chunk.get("explanation").asText()).isNotBlank();
        }
        return json;
    }

    private MvcResult expectError(org.springframework.test.web.servlet.ResultMatcher matcher) throws Exception {
        return ApiTestSupport.sentenceAnalysis(mockMvc, student.token(), FakeOpenAiServer.SENTENCE)
                .andExpect(matcher)
                .andReturn();
    }

    private static List<String> chunkTexts(JsonNode json) {
        List<String> texts = new ArrayList<>();
        chunks(json).forEach(chunk -> texts.add(chunk.get("text").asText()));
        return texts;
    }

    private static List<JsonNode> chunks(JsonNode json) {
        List<JsonNode> chunks = new ArrayList<>();
        json.get("chunks").forEach(chunks::add);
        return chunks;
    }

    private static void assertErrorBody(MvcResult result, String expectedCode) throws Exception {
        JsonNode json = ApiTestSupport.body(result);
        assertThat(fieldNames(json))
                .as("错误体必须只有 code 和 message 两个字段")
                .containsExactlyInAnyOrder("code", "message");
        assertThat(json.get("code").asText()).isEqualTo(expectedCode);

        String message = json.get("message").asText();
        assertThat(message).as("错误提示不能为空").isNotBlank();
        assertThat(containsChinese(message)).as("错误提示应为中文：%s", message).isTrue();
    }

    private static List<String> fieldNames(JsonNode json) {
        List<String> names = new ArrayList<>();
        json.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static boolean containsChinese(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return text.codePoints().anyMatch(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }
}
