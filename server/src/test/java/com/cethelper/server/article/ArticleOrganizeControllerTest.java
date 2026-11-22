package com.cethelper.server.article;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cethelper.server.support.ApiTestSupport;
import com.cethelper.server.support.FakeOpenAiServer;
import com.cethelper.server.support.FakeOpenAiServer.Mode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 文章整理接口在 demo 模式下的契约测试。
 *
 * <p>覆盖：匿名 401、各类非法输入 400、本地规则合并连字符断行并过滤选项、图片单独提交在 demo 下 422。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("文章整理（demo）")
class ArticleOrganizeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private ApiTestSupport.Student student;

    @BeforeEach
    void createStudent() throws Exception {
        student = ApiTestSupport.newStudent(mockMvc);
    }

    @Test
    @DisplayName("匿名请求返回 401 UNAUTHORIZED")
    void anonymousRequestIsUnauthorized() throws Exception {
        MvcResult result = ArticleTestApi
                .organize(mockMvc, null, "The library opens early.", null, null, "reading")
                .andExpect(status().isUnauthorized())
                .andReturn();
        ArticleTestApi.assertErrorBody(result, "UNAUTHORIZED");
    }

    @Test
    @DisplayName("空请求体、两个字段都为空、非法 target、超长 rawText、非法图片参数都返回 400 INVALID_INPUT")
    void invalidInputsReturnInvalidInput() throws Exception {
        assertBadRequest(ArticleTestApi.organizeRaw(mockMvc, student.token(), ""));
        assertBadRequest(ArticleTestApi.organizeRaw(mockMvc, student.token(), "{}"));
        assertBadRequest(ArticleTestApi.organizeRaw(mockMvc, student.token(), "{\"rawText\":null,\"imageBase64\":null}"));
        assertBadRequest(ArticleTestApi.organizeRaw(mockMvc, student.token(), "{\"rawText\":\"   \",\"imageBase64\":\"\"}"));
        assertBadRequest(ArticleTestApi.organize(mockMvc, student.token(), "The library opens early.",
                null, null, "writing"));
        assertBadRequest(ArticleTestApi.organize(mockMvc, student.token(), "a".repeat(50_001),
                null, null, "reading"));

        String image = Base64.getEncoder().encodeToString("not-an-image".getBytes(StandardCharsets.UTF_8));
        assertBadRequest(ArticleTestApi.organize(mockMvc, student.token(), null,
                image, "image/gif", "reading"));
        assertBadRequest(ArticleTestApi.organize(mockMvc, student.token(), null,
                "not-base64!!!", "image/png", "reading"));
    }

    @Test
    @DisplayName("demo 模式合并连字符断行、过滤 Directions/题号/选项，返回 mode=demo")
    void demoCleansHyphenatedLinesAndChoiceOptions() throws Exception {
        String rawText = """
                Directions: Read the following passage carefully.
                1. What is the main idea?
                A) The city is quiet.
                B) The city is noisy.
                2

                The stu-
                dents walked to the library.

                They read books every day.""";

        MvcResult result = ArticleTestApi
                .organize(mockMvc, student.token(), rawText, null, null, "reading")
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = ApiTestSupport.body(result);

        assertThat(ArticleTestApi.fieldNames(json))
                .containsExactlyInAnyOrder("title", "body", "summary", "mode");
        assertThat(json.get("mode").asText()).isEqualTo("demo");
        assertThat(json.get("title").asText()).isEmpty();

        String body = json.get("body").asText();
        assertThat(body).isEqualTo("The students walked to the library.\n\nThey read books every day.");
        assertThat(body).doesNotContain("Directions", "What is the main idea", "A)", "B)");
        assertThat(json.get("summary").asText()).contains("演示模式").contains("断行");
    }

    @Test
    @DisplayName("只提供图片时 demo 模式返回 422 DEMO_UNSUPPORTED")
    void imageOnlyInDemoReturnsDemoUnsupported() throws Exception {
        String image = Base64.getEncoder().encodeToString("fake-image".getBytes(StandardCharsets.UTF_8));

        MvcResult result = ArticleTestApi
                .organize(mockMvc, student.token(), null, image, "image/png", "reading")
                .andExpect(status().isUnprocessableEntity())
                .andReturn();
        JsonNode json = ArticleTestApi.assertErrorBody(result, "DEMO_UNSUPPORTED");
        assertThat(json.get("message").asText()).contains("演示模式", "识图");
    }

    private static void assertBadRequest(ResultActions actions) throws Exception {
        MvcResult result = actions.andExpect(status().isBadRequest()).andReturn();
        ArticleTestApi.assertErrorBody(result, "INVALID_INPUT");
    }
}

/**
 * 文章整理接口在 live 模式下的契约测试：用本地假上游替代真实模型服务。
 *
 * <p>覆盖：纯文本整理、图片多模态 content 块、请求体关闭 thinking、上游 500/不可解析/超时映射，
 * 以及错误响应不泄漏上游正文与密钥。</p>
 */
@SpringBootTest(properties = {
        "cet.explanation.mode=live",
        // 与配置的读超时一致，保证 SLOW 用例稳定触发 504，而不是被 45 秒覆盖值拖长。
        "cet.article.read-timeout=PT0.4S"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("文章整理（live）")
class ArticleOrganizeLiveControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 假密钥，仅用于验证 Authorization 头，不写入任何真实配置。 */
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
    void resetUpstreamAndCreateStudent() throws Exception {
        UPSTREAM.mode(Mode.SUCCESS).slowMillis(0).content(null);
        student = ApiTestSupport.newStudent(mockMvc);
    }

    @Test
    @DisplayName("纯文本整理：返回模型标题/正文/说明，并在请求体中关闭 thinking")
    void textOnlyOrganizationReturnsLiveArticle() throws Exception {
        UPSTREAM.content(ArticleTestApi.articleJson("A Quiet Campus",
                "The library opens early.\n\nStudents arrive before class.",
                "已提取阅读正文，过滤题目并重新分段"));

        MvcResult result = ArticleTestApi
                .organize(mockMvc, student.token(),
                        "Directions: Read the passage.\nThe library opens early. Students arrive before class.",
                        null, null, "reading")
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = ApiTestSupport.body(result);

        assertThat(ArticleTestApi.fieldNames(json))
                .containsExactlyInAnyOrder("title", "body", "summary", "mode");
        assertThat(json.get("mode").asText()).isEqualTo("live");
        assertThat(json.get("title").asText()).isEqualTo("A Quiet Campus");
        assertThat(json.get("body").asText())
                .isEqualTo("The library opens early.\n\nStudents arrive before class.");
        assertThat(json.get("summary").asText()).isNotBlank();

        JsonNode request = MAPPER.readTree(UPSTREAM.lastRequestBody());
        assertThat(request.path("thinking").path("type").asText())
                .as("长文提取应显式关闭思考，避免推理模型延迟与超时")
                .isEqualTo("disabled");
        assertThat(request.path("temperature").asDouble()).isEqualTo(0.1);
        JsonNode userContent = request.path("messages").path(1).path("content");
        assertThat(userContent.isTextual()).as("纯文本请求的 user content 应为字符串").isTrue();
        assertThat(userContent.asText()).contains("The library opens early");
        assertThat(UPSTREAM.lastAuthorization()).isEqualTo("Bearer " + FAKE_API_KEY);
    }

    @Test
    @DisplayName("图片整理：user content 使用 text + image_url 多模态内容块")
    void imageOrganizationSendsMultimodalContent() throws Exception {
        String image = "data:image/png;base64,"
                + Base64.getEncoder().encodeToString("fake-png".getBytes(StandardCharsets.UTF_8));
        UPSTREAM.content(ArticleTestApi.articleJson("Picture Passage",
                "First paragraph from the picture.\n\nSecond paragraph from the picture.",
                "已提取图片中的阅读正文并分段"));

        MvcResult result = ArticleTestApi
                .organize(mockMvc, student.token(), null, image, null, "reading")
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = ApiTestSupport.body(result);

        assertThat(json.get("mode").asText()).isEqualTo("live");
        assertThat(json.get("title").asText()).isEqualTo("Picture Passage");
        assertThat(json.get("body").asText()).contains("First paragraph", "\n\n", "Second paragraph");

        JsonNode request = MAPPER.readTree(UPSTREAM.lastRequestBody());
        JsonNode userContent = request.path("messages").path(1).path("content");
        assertThat(userContent.isArray()).as("图片请求的 content 应为内容块数组").isTrue();
        assertThat(userContent.get(0).path("type").asText()).isEqualTo("text");
        assertThat(userContent.get(0).path("text").asText()).contains("图片");
        assertThat(userContent.get(1).path("type").asText()).isEqualTo("image_url");
        assertThat(userContent.get(1).path("image_url").path("url").asText()).isEqualTo(image);
    }

    @Test
    @DisplayName("translation 模式固定返回空标题，正文合并断行后仍为非空")
    void translationOrganizationReturnsEmptyTitle() throws Exception {
        UPSTREAM.content(ArticleTestApi.articleJson("should be ignored",
                "中国的传统节日承载着丰富的文化内涵。\n\n人们在节日里与家人团聚。",
                "已提取翻译原文并整理分段"));

        JsonNode json = ApiTestSupport.body(ArticleTestApi
                .organize(mockMvc, student.token(), "中国的传统节日承载着丰富的文化内涵。", null, null, "translation")
                .andExpect(status().isOk())
                .andReturn());

        assertThat(json.get("mode").asText()).isEqualTo("live");
        assertThat(json.get("title").asText()).isEmpty();
        assertThat(json.get("body").asText()).contains("传统节日", "\n\n", "家人团聚");
    }

    @Test
    @DisplayName("上游 500：502 MODEL_ERROR，且不泄漏 LEAK_MARKER、密钥与堆栈")
    void upstreamErrorReturnsModelErrorWithoutLeaking() throws Exception {
        UPSTREAM.mode(Mode.ERROR);

        MvcResult result = ArticleTestApi
                .organize(mockMvc, student.token(), "The library opens early.", null, null, "reading")
                .andExpect(status().isBadGateway())
                .andReturn();
        ArticleTestApi.assertErrorBody(result, "MODEL_ERROR");

        String text = ApiTestSupport.bodyText(result);
        assertThat(text).doesNotContain(FakeOpenAiServer.LEAK_MARKER);
        assertThat(text).doesNotContain("sk-");
        assertThat(text).doesNotContain("Exception", "Caused by", "at com.cethelper", "java.");
    }

    @Test
    @DisplayName("上游返回不可解析内容：502 MODEL_ERROR")
    void unparsableUpstreamContentReturnsModelError() throws Exception {
        UPSTREAM.mode(Mode.GARBAGE);

        MvcResult result = ArticleTestApi
                .organize(mockMvc, student.token(), "The library opens early.", null, null, "reading")
                .andExpect(status().isBadGateway())
                .andReturn();
        ArticleTestApi.assertErrorBody(result, "MODEL_ERROR");
        assertThat(ApiTestSupport.bodyText(result)).doesNotContain(FakeOpenAiServer.LEAK_MARKER);
    }

    @Test
    @DisplayName("上游响应超过读超时：504 MODEL_TIMEOUT")
    void slowUpstreamReturnsModelTimeout() throws Exception {
        UPSTREAM.mode(Mode.SLOW).slowMillis(1_500);

        MvcResult result = ArticleTestApi
                .organize(mockMvc, student.token(), "The library opens early.", null, null, "reading")
                .andExpect(status().isGatewayTimeout())
                .andReturn();
        ArticleTestApi.assertErrorBody(result, "MODEL_TIMEOUT");

        UPSTREAM.slowMillis(0).mode(Mode.SUCCESS);
    }
}

/** 文章整理测试的公共请求封装与断言工具。 */
final class ArticleTestApi {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ArticleTestApi() {
    }

    static ResultActions organize(MockMvc mvc, String token, String rawText, String imageBase64,
                                  String imageMimeType, String target) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("rawText", rawText);
        payload.put("imageBase64", imageBase64);
        payload.put("imageMimeType", imageMimeType);
        payload.put("target", target);
        return organizeRaw(mvc, token, ApiTestSupport.json(payload));
    }

    static ResultActions organizeRaw(MockMvc mvc, String token, String rawJson) throws Exception {
        var request = post("/api/v1/articles/organize").contentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        return mvc.perform(request.content(rawJson));
    }

    static String articleJson(String title, String body, String summary) {
        try {
            return MAPPER.writeValueAsString(Map.of("title", title, "body", body, "summary", summary));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static JsonNode assertErrorBody(MvcResult result, String expectedCode) throws Exception {
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

    static List<String> fieldNames(JsonNode json) {
        List<String> names = new ArrayList<>();
        json.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static boolean containsChinese(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return text.codePoints()
                .anyMatch(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }
}
