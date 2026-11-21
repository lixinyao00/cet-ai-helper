package com.cethelper.server.explain;

import com.cethelper.server.support.ApiTestSupport;
import com.cethelper.server.support.FakeOpenAiServer;
import com.cethelper.server.support.FakeOpenAiServer.Mode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 释义接口在 live 模式下的契约测试：用本地假上游替代真实模型服务。
 *
 * <p>覆盖：上游正常返回、上游 500、上游返回不可解析内容、上游超时，以及鉴权/入参校验先于上游调用。
 * 假上游通过 {@link DynamicPropertySource} 注入，因此服务端配置的 base-url / model / api-key
 * 全部指向本地替身，不依赖任何真实凭据。</p>
 */
@SpringBootTest(properties = {"cet.explanation.mode=live"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ExplainLiveApiTest {

    /** 假上游返回的固定释义，用于核对服务端确实把模型结果透传出来。 */
    private static final String UPSTREAM_MEANING = "婉拒";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 服务端应放进 Authorization 头的密钥值（假值，仅用于验证传输方式）。 */
    private static final String FAKE_API_KEY = "test-key-not-real";

    /**
     * 本地假上游。静态字段的初始化早于 Spring 上下文创建，
     * 因此 {@link DynamicPropertySource} 里可以直接读取它的随机端口。
     */
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
        // 注意使用 ISO-8601 时长写法（PT0.4S），java.time.Duration.parse 不接受 "PT400ms"。
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
        // 与用例执行顺序无关：每个用例开始都把假上游恢复为「正常返回、无延迟」。
        UPSTREAM.mode(Mode.SUCCESS).slowMillis(0).content(null);
        student = ApiTestSupport.newStudent(mockMvc);
    }

    @Test
    @DisplayName("上游正常返回：200、mode=live、释义透传，且密钥放在 Authorization 头而非请求体")
    void successFromUpstreamReturnsLiveExplanation() throws Exception {
        int before = UPSTREAM.requestCount();

        MvcResult result = ApiTestSupport
                .explain(mockMvc, student.token(), "decline", ApiTestSupport.SENTENCE_DECLINE_VERB)
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = ApiTestSupport.body(result);

        assertThat(fieldNames(json))
                .as("未返回可选字段时成功体只有契约约定字段与 contextual")
                .containsExactlyInAnyOrder("meaning", "explanation", "mode", "contextual");
        assertThat(json.get("mode").asText()).as("live 模式下 mode 必须为 live").isEqualTo("live");
        assertThat(json.get("meaning").asText()).isEqualTo(UPSTREAM_MEANING);
        assertThat(json.get("explanation").asText()).isNotBlank();

        // 一次查词只应触发一次上游调用。
        assertThat(UPSTREAM.requestCount()).as("一次查词应只调用一次上游").isEqualTo(before + 1);

        String upstreamBody = UPSTREAM.lastRequestBody();
        assertThat(upstreamBody).as("上游请求应包含选词").contains("decline");
        assertThat(upstreamBody).as("上游请求应包含原句").contains(ApiTestSupport.SENTENCE_DECLINE_VERB);
        assertThat(upstreamBody).as("密钥不得以明文出现在上游请求体里").doesNotContain(FAKE_API_KEY);
        assertThat(UPSTREAM.lastAuthorization())
                .as("密钥应通过 Authorization 头传给上游")
                .isEqualTo("Bearer " + FAKE_API_KEY);
    }

    @Test
    @DisplayName("上游返回音标/词性/原形时透传扩展字段，contextual=true；未返回时字段省略")
    void extendedFieldsFromUpstreamArePassedThrough() throws Exception {
        UPSTREAM.content("{\"meaning\":\"婉拒\",\"explanation\":\"这里作动词，指学生谢绝邀请。\","
                + "\"phonetic\":\"/dɪˈklaɪn/\",\"partOfSpeech\":\"v.\",\"lemma\":\"decline\",\"cetLevel\":\"cet-4\"}");

        JsonNode json = ApiTestSupport.body(ApiTestSupport
                .explain(mockMvc, student.token(), "decline", ApiTestSupport.SENTENCE_DECLINE_VERB)
                .andExpect(status().isOk())
                .andReturn());

        assertThat(fieldNames(json)).containsExactlyInAnyOrder("meaning", "explanation", "mode",
                "phonetic", "partOfSpeech", "lemma", "cetLevel", "contextual");
        assertThat(json.get("phonetic").asText()).isEqualTo("/dɪˈklaɪn/");
        assertThat(json.get("partOfSpeech").asText()).isEqualTo("v.");
        assertThat(json.get("lemma").asText()).isEqualTo("decline");
        assertThat(json.get("cetLevel").asText()).as("cetLevel 统一规范为大写").isEqualTo("CET-4");
        assertThat(json.get("contextual").asBoolean()).as("live 释义始终贴合原句语境").isTrue();
    }

    @Test
    @DisplayName("上游只给 meaning/explanation 时，可选字段不出现在响应里（旧契约不变）")
    void missingOptionalFieldsAreOmitted() throws Exception {
        JsonNode json = ApiTestSupport.body(ApiTestSupport
                .explain(mockMvc, student.token(), "decline", ApiTestSupport.SENTENCE_DECLINE_VERB)
                .andExpect(status().isOk())
                .andReturn());

        assertThat(fieldNames(json)).containsExactlyInAnyOrder("meaning", "explanation", "mode", "contextual");
        assertThat(json.has("phonetic")).isFalse();
        assertThat(json.has("partOfSpeech")).isFalse();
        assertThat(json.has("lemma")).isFalse();
        assertThat(json.has("cetLevel")).isFalse();
    }

    @Test
    @DisplayName("健康检查在 live 模式下返回 mode=live，且无需凭证")
    void healthReportsLiveMode() throws Exception {
        JsonNode json = ApiTestSupport.body(ApiTestSupport.health(mockMvc, null)
                .andExpect(status().isOk())
                .andReturn());

        assertThat(fieldNames(json)).containsExactlyInAnyOrder("status", "mode", "version");
        assertThat(json.get("status").asText()).isEqualTo("UP");
        assertThat(json.get("mode").asText()).isEqualTo("live");
        assertThat(json.get("version").asText()).isEqualTo("0.2.0");
    }

    @Test
    @DisplayName("上游返回 500：502 MODEL_ERROR，且不外泄上游正文、密钥与堆栈")
    void upstreamErrorReturnsModelError() throws Exception {
        UPSTREAM.mode(Mode.ERROR);

        MvcResult result = ApiTestSupport
                .explain(mockMvc, student.token(), "decline", ApiTestSupport.SENTENCE_DECLINE_VERB)
                .andExpect(status().isBadGateway())
                .andReturn();
        assertErrorBody(result, "MODEL_ERROR");

        String text = ApiTestSupport.bodyText(result);
        assertThat(text).as("不得回传上游正文中的诱饵密钥").doesNotContain(FakeOpenAiServer.LEAK_MARKER);
        assertThat(text).as("不得出现任何密钥样式的内容").doesNotContain("sk-");
        assertThat(text).as("不得回传异常类型").doesNotContain("Exception");
        assertThat(text).as("不得回传异常链").doesNotContain("Caused by");
        assertThat(text).as("不得回传堆栈帧").doesNotContain("at com.cethelper");
        assertThat(text).as("不得回传包名等实现细节").doesNotContain("java.");
    }

    @Test
    @DisplayName("上游返回无法解析的内容：502 MODEL_ERROR")
    void unparsableUpstreamContentReturnsModelError() throws Exception {
        UPSTREAM.mode(Mode.GARBAGE);

        MvcResult result = ApiTestSupport
                .explain(mockMvc, student.token(), "decline", ApiTestSupport.SENTENCE_DECLINE_VERB)
                .andExpect(status().isBadGateway())
                .andReturn();
        assertErrorBody(result, "MODEL_ERROR");
        assertThat(ApiTestSupport.bodyText(result)).doesNotContain(FakeOpenAiServer.LEAK_MARKER);
    }

    @Test
    @DisplayName("上游响应超过读超时：504 MODEL_TIMEOUT，并把延迟复原")
    void slowUpstreamReturnsModelTimeout() throws Exception {
        UPSTREAM.mode(Mode.SLOW).slowMillis(1500);

        MvcResult result = ApiTestSupport
                .explain(mockMvc, student.token(), "decline", ApiTestSupport.SENTENCE_DECLINE_VERB)
                .andExpect(status().isGatewayTimeout())
                .andReturn();
        assertErrorBody(result, "MODEL_TIMEOUT");

        // 复原延迟与模式，确保后续用例（无论执行顺序）不受影响。
        UPSTREAM.slowMillis(0).mode(Mode.SUCCESS);
    }

    @Test
    @DisplayName("匿名请求与非法报文在调用上游之前就被拒绝")
    void requestsRejectedBeforeUpstream() throws Exception {
        int before = UPSTREAM.requestCount();

        MvcResult anonymous = ApiTestSupport
                .explain(mockMvc, null, "decline", ApiTestSupport.SENTENCE_DECLINE_VERB)
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertErrorBody(anonymous, "UNAUTHORIZED");

        MvcResult invalidInput = ApiTestSupport
                .explain(mockMvc, student.token(), "decline",
                        "The report attributed the increase to higher demand.")
                .andExpect(status().isBadRequest())
                .andReturn();
        assertErrorBody(invalidInput, "INVALID_INPUT");

        assertThat(UPSTREAM.requestCount())
                .as("鉴权与入参校验都应先于上游调用，假上游的请求数不应增加")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("首尾空白在调用上游前被去掉，内部空格原样保留")
    void upstreamReceivesNormalizedInput() throws Exception {
        String paddedSelection = "  decline  ";
        String paddedSentence = "\t" + ApiTestSupport.SENTENCE_DECLINE_VERB + "\u3000 ";

        ApiTestSupport.explain(mockMvc, student.token(), paddedSelection, paddedSentence)
                .andExpect(status().isOk());

        JsonNode request = MAPPER.readTree(UPSTREAM.lastRequestBody());
        String userContent = request.path("messages").path(1).path("content").asText();
        assertThat(userContent)
                .as("上游应当收到去掉首尾空白后的原句与选词，内部空格保留")
                .isEqualTo("原句：" + ApiTestSupport.SENTENCE_DECLINE_VERB + "\n选词：decline");
    }

    /** 断言错误体固定为 {"code","message"} 两个字段，错误码匹配，提示为中文非空。 */
    private static JsonNode assertErrorBody(MvcResult result, String expectedCode) throws Exception {
        JsonNode json = ApiTestSupport.body(result);
        assertThat(fieldNames(json))
                .as("错误体必须只有 code 和 message 两个字段")
                .containsExactlyInAnyOrder("code", "message");
        assertThat(json.get("code").asText()).isEqualTo(expectedCode);

        String message = json.get("message").asText();
        assertThat(message).as("错误提示不能为空").isNotBlank();
        assertThat(containsChinese(message)).as("错误提示应为中文：%s", message).isTrue();
        return json;
    }

    /** 取出 JSON 对象的全部字段名，用于精确核对响应体形状。 */
    private static List<String> fieldNames(JsonNode json) {
        List<String> names = new ArrayList<>();
        json.fieldNames().forEachRemaining(names::add);
        return names;
    }

    /** 判断文本是否包含汉字，用于验证错误提示使用中文。 */
    private static boolean containsChinese(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return text.codePoints().anyMatch(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }
}
