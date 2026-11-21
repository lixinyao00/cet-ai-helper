package com.cethelper.server.translation;

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
import org.springframework.test.web.servlet.ResultMatcher;

/**
 * 汉译英提示与批改在 live 模式下的契约测试：用本地假上游替代真实模型服务。
 *
 * <p>覆盖：三类提示的成功返回、提示里中文片段不在原文时 502、缺字段/非 JSON/空结果 502、
 * 批改的空问题列表与带问题列表、漏译引用不在中文原文时 502、表达不自然引用不在学生译文时 502、
 * 上游 500、读超时 504、鉴权与入参校验先于上游调用、首尾空白规范化。
 * 全部请求指向本地替身，不依赖任何真实模型凭据。</p>
 */
@SpringBootTest(properties = {"cet.explanation.mode=live"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("汉译英提示与批改（live 假上游）")
class TranslationLiveApiTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SOURCE = DemoTranslationCatalog.SOURCE_TEXT;

    private static final String FAKE_API_KEY = "test-key-not-real";

    /** 学生另写的一段译文，用于验证批改引用取自学生译文。 */
    private static final String STUDENT_TRANSLATION = "The digital economy develops quickly. Companies hire young people.";

    private static final String KEYWORD_CONTENT = "{\"keywords\":[{\"source\":\"数字经济\","
            + "\"expressions\":[\"the digital economy\",\"digital economy\"],\"note\":\"作介词宾语时通常带定冠词。\"}]}";

    private static final String PHRASE_CONTENT = "{\"phrases\":[{\"source\":\"越来越重要\","
            + "\"expressions\":[\"increasingly important\"],\"note\":\"四六级作文常用。\"}]}";

    private static final String STRUCTURE_CONTENT = "{\"mainClause\":\"第一句主干是「掌握基本的编程能力"
            + "对高校毕业生越来越重要」。\",\"clauses\":[{\"source\":\"随着数字经济的快速发展\",\"role\":\"时间状语\","
            + "\"guidance\":\"用 With ... 或 As ... 引导，放在句首。\"}],\"connectives\":[{\"source\":\"因此\","
            + "\"suggestion\":\"Therefore, 或 As a result,\"}]}";

    private static final String CLEAN_REVIEW_CONTENT = "{\"overallFeedback\":\"意思准确、表达自然，可以提交。\","
            + "\"issues\":[]}";

    private static final String ISSUE_REVIEW_CONTENT = "{\"overallFeedback\":\"有一处漏译和一处表达不自然。\","
            + "\"issues\":[{\"type\":\"OMISSION\",\"quote\":\"对高校毕业生越来越重要\",\"detail\":\"漏掉对象与程度。\","
            + "\"suggestion\":\"补成 increasingly important for college graduates。\"},"
            + "{\"type\":\"UNNATURAL\",\"quote\":\"Companies hire young people\",\"detail\":\"语气太平。\","
            + "\"suggestion\":\"改为 are more willing to hire young people。\"}]}";

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
    @DisplayName("keyword 提示：200、mode=live、英文表达透传，密钥走 Authorization 头")
    void keywordHintFromUpstreamReturnsLiveResult() throws Exception {
        UPSTREAM.content(KEYWORD_CONTENT);
        int before = UPSTREAM.requestCount();

        JsonNode json = expectHint("keyword");

        assertThat(json.get("mode").asText()).isEqualTo("live");
        List<JsonNode> keywords = items(json, "keywords");
        assertThat(keywords).hasSize(1);
        assertThat(keywords.get(0).get("source").asText()).isEqualTo("数字经济");
        assertThat(keywords.get(0).get("expressions")).hasSize(2);
        assertThat(keywords.get(0).get("note").asText()).isNotBlank();

        assertThat(UPSTREAM.requestCount()).isEqualTo(before + 1);
        assertThat(UPSTREAM.lastRequestBody()).contains(SOURCE).doesNotContain(FAKE_API_KEY);
        assertThat(UPSTREAM.lastAuthorization()).isEqualTo("Bearer " + FAKE_API_KEY);
    }

    @Test
    @DisplayName("phrase 提示：200、mode=live、短语与表达透传")
    void phraseHintFromUpstreamReturnsLiveResult() throws Exception {
        UPSTREAM.content(PHRASE_CONTENT);

        JsonNode json = expectHint("phrase");

        assertThat(json.get("mode").asText()).isEqualTo("live");
        List<JsonNode> phrases = items(json, "phrases");
        assertThat(phrases).hasSize(1);
        assertThat(phrases.get(0).get("source").asText()).isEqualTo("越来越重要");
        assertThat(UPSTREAM.lastRequestBody()).contains("phrase");
    }

    @Test
    @DisplayName("structure 提示：200、主干/分句/连接方式透传")
    void structureHintFromUpstreamReturnsLiveResult() throws Exception {
        UPSTREAM.content(STRUCTURE_CONTENT);

        JsonNode json = expectHint("structure");

        assertThat(json.get("mode").asText()).isEqualTo("live");
        JsonNode structure = json.get("structure");
        assertThat(structure.get("mainClause").asText()).contains("主干");
        assertThat(items(structure, "clauses")).hasSize(1);
        assertThat(items(structure, "connectives")).hasSize(1);
    }

    @Test
    @DisplayName("提示里的中文片段不在原文中：502 MODEL_ERROR，不把编造的片段交给前端")
    void hintSourceNotInMaterialReturnsModelError() throws Exception {
        UPSTREAM.content("{\"keywords\":[{\"source\":\"人工智能\",\"expressions\":[\"AI\"],\"note\":\"原文没有这个词。\"}]}");

        MvcResult result = expectHintError(status().isBadGateway());
        assertErrorBody(result, "MODEL_ERROR");
        assertThat(ApiTestSupport.bodyText(result)).doesNotContain(FakeOpenAiServer.LEAK_MARKER);
    }

    @Test
    @DisplayName("提示缺少所选类型的字段：502 MODEL_ERROR")
    void hintMissingSelectedFieldReturnsModelError() throws Exception {
        // 请求 keyword，上游却只给了 phrases
        UPSTREAM.content(PHRASE_CONTENT);
        assertErrorBody(expectHintError(status().isBadGateway()), "MODEL_ERROR");
    }

    @Test
    @DisplayName("提示的 keywords 为空数组或内容不是 JSON：502 MODEL_ERROR")
    void hintEmptyOrUnparsableReturnsModelError() throws Exception {
        UPSTREAM.content("{\"keywords\":[]}");
        assertErrorBody(expectHintError(status().isBadGateway()), "MODEL_ERROR");

        UPSTREAM.content("抱歉，这不是约定的 JSON。");
        assertErrorBody(expectHintError(status().isBadGateway()), "MODEL_ERROR");
    }

    @Test
    @DisplayName("批改：译文合格时返回空问题列表，200 且 mode=live")
    void cleanReviewKeepsEmptyIssues() throws Exception {
        UPSTREAM.content(CLEAN_REVIEW_CONTENT);

        JsonNode json = expectReview();

        assertThat(json.get("mode").asText()).isEqualTo("live");
        assertThat(json.get("overallFeedback").asText()).isNotBlank();
        assertThat(items(json, "issues")).as("译文合格时 issues 必须是空数组").isEmpty();
    }

    @Test
    @DisplayName("批改：模型返回评分与参考译文时透传，未返回时字段省略")
    void optionalScoreFieldsArePassedThroughOrOmitted() throws Exception {
        UPSTREAM.content("{\"overallFeedback\":\"意思准确、表达自然，可以提交。\",\"issues\":[],"
                + "\"estimatedScore\":13,\"scoreMax\":15,"
                + "\"scoreBand\":\"第一档（13-15分）：译文准确，表达流畅。\","
                + "\"referenceTranslation\":\"As the digital economy develops rapidly, programming skills are "
                + "becoming increasingly important for college graduates.\"}");

        JsonNode scored = expectReview();
        assertThat(scored.get("estimatedScore").asInt()).isEqualTo(13);
        assertThat(scored.get("scoreMax").asInt()).isEqualTo(15);
        assertThat(scored.get("scoreBand").asText()).contains("第一档");
        assertThat(scored.get("referenceTranslation").asText()).contains("digital economy");

        // 模型没给评分时字段省略，保证旧客户端与旧契约不受影响。
        UPSTREAM.content(CLEAN_REVIEW_CONTENT);
        JsonNode plain = expectReview();
        assertThat(plain.has("estimatedScore")).isFalse();
        assertThat(plain.has("scoreBand")).isFalse();
        assertThat(plain.has("referenceTranslation")).isFalse();
    }

    @Test
    @DisplayName("批改：漏译引用中文原文、表达不自然引用学生译文，逐条透传")
    void reviewWithIssuesKeepsQuotesAndOrder() throws Exception {
        UPSTREAM.content(ISSUE_REVIEW_CONTENT);

        JsonNode json = expectReview();

        List<JsonNode> issues = items(json, "issues");
        assertThat(issues).hasSize(2);
        assertThat(issues.get(0).get("type").asText()).isEqualTo("OMISSION");
        assertThat(SOURCE).contains(issues.get(0).get("quote").asText());
        assertThat(issues.get(1).get("type").asText()).isEqualTo("UNNATURAL");
        assertThat(STUDENT_TRANSLATION).contains(issues.get(1).get("quote").asText());
        assertThat(issues).extracting(issue -> issue.get("quote").asText())
                .containsExactly("对高校毕业生越来越重要", "Companies hire young people");
        assertThat(UPSTREAM.lastRequestBody()).contains(STUDENT_TRANSLATION);
    }

    @Test
    @DisplayName("提示的 expressions 含 null 或数字：keyword 与 phrase 都返回 502 MODEL_ERROR（不是 500）")
    void hintWithInvalidExpressionElementsReturnsModelError() throws Exception {
        UPSTREAM.content("{\"keywords\":[{\"source\":\"数字经济\",\"expressions\":[null],\"note\":\"元素是 null。\"}]}");
        MvcResult keywordNull = ApiTestSupport.translationHint(mockMvc, student.token(), SOURCE, "keyword")
                .andExpect(status().isBadGateway())
                .andReturn();
        assertErrorBody(keywordNull, "MODEL_ERROR");

        UPSTREAM.content("{\"phrases\":[{\"source\":\"越来越重要\",\"expressions\":[123],\"note\":\"元素是数字。\"}]}");
        MvcResult phraseNumber = ApiTestSupport.translationHint(mockMvc, student.token(), SOURCE, "phrase")
                .andExpect(status().isBadGateway())
                .andReturn();
        assertErrorBody(phraseNumber, "MODEL_ERROR");
    }

    @Test
    @DisplayName("expressions 是合法字符串数组时，keyword 与 phrase 仍返回 200")
    void hintWithTextualExpressionsStillReturnsOk() throws Exception {
        UPSTREAM.content(KEYWORD_CONTENT);
        assertThat(expectHint("keyword").get("mode").asText()).isEqualTo("live");

        UPSTREAM.content(PHRASE_CONTENT);
        assertThat(expectHint("phrase").get("mode").asText()).isEqualTo("live");
    }

    @Test
    @DisplayName("引用乱序：上游先给「明天」再给「今天」，200 且两条引用都能按类型独立定位")
    void outOfOrderQuotesStayLocatable() throws Exception {
        String source = "今天学生读书，明天老师备课。";
        String translation = "Students read books and teachers prepare lessons.";
        UPSTREAM.content("{\"overallFeedback\":\"两处时间都没译出来。\",\"issues\":["
                + "{\"type\":\"OMISSION\",\"quote\":\"明天\",\"detail\":\"漏掉明天。\",\"suggestion\":\"补上 tomorrow。\"},"
                + "{\"type\":\"OMISSION\",\"quote\":\"今天\",\"detail\":\"漏掉今天。\",\"suggestion\":\"补上 today。\"}]}");

        MvcResult result = ApiTestSupport
                .translationReview(mockMvc, student.token(), source, translation)
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = ApiTestSupport.body(result);

        assertThat(items(json, "issues")).extracting(issue -> issue.get("quote").asText())
                .as("问题顺序保留上游顺序，不按原文位置重排")
                .containsExactly("明天", "今天");

        // README 的定位约定：每条引用独立从 0 开始 indexOf（按类型选原文或学生译文），
        // 所以乱序引用也能各自找到正确位置。
        assertThat(source.indexOf("明天")).isEqualTo(7);
        assertThat(source.indexOf("今天")).isEqualTo(0);

        // 旧的「沿用上一条结束位置」写法会失败，这正是本轮要修掉的错误高亮来源。
        int cursor = source.indexOf("明天") + "明天".length();
        assertThat(source.indexOf("今天", cursor))
                .as("顺序查找会返回 -1，因此必须逐条独立定位")
                .isEqualTo(-1);
    }

    @Test
    @DisplayName("同一引文在输入中出现多次：200，服务端不猜位置，由客户端按文档标记多处")
    void ambiguousDuplicateQuoteIsStillReturned() throws Exception {
        String source = "读书读书，读书有益。";
        UPSTREAM.content("{\"overallFeedback\":\"有一处漏译。\",\"issues\":[{\"type\":\"OMISSION\","
                + "\"quote\":\"读书\",\"detail\":\"漏掉一处读书。\",\"suggestion\":\"补齐。\"}]}");

        JsonNode json = ApiTestSupport.body(ApiTestSupport
                .translationReview(mockMvc, student.token(), source, "Reading is useful.")
                .andExpect(status().isOk())
                .andReturn());

        String quote = items(json, "issues").get(0).get("quote").asText();
        assertThat(quote).isEqualTo("读书");
        assertThat(source.indexOf(quote))
                .as("同一引文出现多次时 indexOf 与 lastIndexOf 不同，客户端应标记全部出现处")
                .isNotEqualTo(source.lastIndexOf(quote));
    }

    @Test
    @DisplayName("漏译引用不在中文原文里：502 MODEL_ERROR")
    void reviewOmissionQuoteNotInSourceReturnsModelError() throws Exception {
        UPSTREAM.content("{\"overallFeedback\":\"有漏译。\",\"issues\":[{\"type\":\"OMISSION\","
                + "\"quote\":\"人工智能的发展\",\"detail\":\"漏译。\",\"suggestion\":\"补上。\"}]}");

        MvcResult result = expectReviewError(status().isBadGateway());
        assertErrorBody(result, "MODEL_ERROR");
        assertThat(ApiTestSupport.bodyText(result)).doesNotContain("人工智能");
    }

    @Test
    @DisplayName("表达不自然的引用不在学生译文里：502 MODEL_ERROR")
    void reviewUnnaturalQuoteNotInTranslationReturnsModelError() throws Exception {
        UPSTREAM.content("{\"overallFeedback\":\"有生硬表达。\",\"issues\":[{\"type\":\"UNNATURAL\","
                + "\"quote\":\"They hire people\",\"detail\":\"不自然。\",\"suggestion\":\"改一下。\"}]}");

        assertErrorBody(expectReviewError(status().isBadGateway()), "MODEL_ERROR");
    }

    @Test
    @DisplayName("批改缺少 overallFeedback 或 issues 不是数组：502 MODEL_ERROR")
    void reviewMissingFieldsReturnsModelError() throws Exception {
        UPSTREAM.content("{\"issues\":[]}");
        assertErrorBody(expectReviewError(status().isBadGateway()), "MODEL_ERROR");

        UPSTREAM.content("{\"overallFeedback\":\"还行。\",\"issues\":\"none\"}");
        assertErrorBody(expectReviewError(status().isBadGateway()), "MODEL_ERROR");
    }

    @Test
    @DisplayName("上游 500：502 MODEL_ERROR，且不外泄上游正文、密钥与堆栈")
    void upstreamErrorReturnsModelError() throws Exception {
        UPSTREAM.mode(Mode.ERROR);

        MvcResult result = expectReviewError(status().isBadGateway());
        assertErrorBody(result, "MODEL_ERROR");

        String text = ApiTestSupport.bodyText(result);
        assertThat(text).doesNotContain(FakeOpenAiServer.LEAK_MARKER);
        assertThat(text).doesNotContain("sk-");
        assertThat(text).doesNotContain("Exception");
        assertThat(text).doesNotContain("at com.cethelper");
        assertThat(text).doesNotContain("java.");
    }

    @Test
    @DisplayName("上游超过读超时：504 MODEL_TIMEOUT，并把延迟复原")
    void slowUpstreamReturnsModelTimeout() throws Exception {
        UPSTREAM.mode(Mode.SLOW).slowMillis(1500);

        MvcResult result = expectHintError(status().isGatewayTimeout());
        assertErrorBody(result, "MODEL_TIMEOUT");

        UPSTREAM.slowMillis(0).mode(Mode.SUCCESS);
    }

    @Test
    @DisplayName("匿名与非法输入在调用上游之前就被拒绝")
    void requestsRejectedBeforeUpstream() throws Exception {
        int before = UPSTREAM.requestCount();

        MvcResult anonymous = ApiTestSupport.translationHint(mockMvc, null, SOURCE, "keyword")
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertErrorBody(anonymous, "UNAUTHORIZED");

        assertErrorBody(ApiTestSupport.translationHint(mockMvc, student.token(), "   ", "keyword")
                .andExpect(status().isBadRequest()).andReturn(), "INVALID_INPUT");
        assertErrorBody(ApiTestSupport.translationHint(mockMvc, student.token(), SOURCE, "grammar")
                .andExpect(status().isBadRequest()).andReturn(), "INVALID_INPUT");
        assertErrorBody(ApiTestSupport.translationReview(mockMvc, student.token(), SOURCE, "  ")
                .andExpect(status().isBadRequest()).andReturn(), "INVALID_INPUT");
        assertErrorBody(ApiTestSupport.translationReviewRaw(mockMvc, student.token(),
                        "{\"sourceText\":\"" + SOURCE + "\"}")
                .andExpect(status().isBadRequest()).andReturn(), "INVALID_INPUT");

        assertThat(UPSTREAM.requestCount())
                .as("鉴权与入参校验都应先于上游调用")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("首尾空白在调用上游前被去掉，提示类型一并传给模型")
    void upstreamReceivesNormalizedInput() throws Exception {
        UPSTREAM.content(KEYWORD_CONTENT);
        String paddedSource = "\t" + SOURCE + "\u3000 ";
        ApiTestSupport.translationHint(mockMvc, student.token(), paddedSource, " KEYWORD ")
                .andExpect(status().isOk());

        JsonNode request = MAPPER.readTree(UPSTREAM.lastRequestBody());
        assertThat(request.path("messages").path(1).path("content").asText())
                .isEqualTo("中文原文：" + SOURCE + "\n提示类型：keyword（关键词）");

        UPSTREAM.content(CLEAN_REVIEW_CONTENT);
        ApiTestSupport.translationReview(mockMvc, student.token(), paddedSource, "  " + STUDENT_TRANSLATION + "\t ")
                .andExpect(status().isOk());
        JsonNode reviewRequest = MAPPER.readTree(UPSTREAM.lastRequestBody());
        assertThat(reviewRequest.path("messages").path(1).path("content").asText())
                .isEqualTo("中文原文：" + SOURCE + "\n学生译文：" + STUDENT_TRANSLATION);
    }

    private JsonNode expectHint(String hintType) throws Exception {
        MvcResult result = ApiTestSupport.translationHint(mockMvc, student.token(), SOURCE, hintType)
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = ApiTestSupport.body(result);
        assertThat(json.get("sourceText").asText()).isEqualTo(SOURCE);
        assertThat(json.get("hintType").asText()).isEqualTo(hintType);
        return json;
    }

    private MvcResult expectHintError(ResultMatcher matcher) throws Exception {
        return ApiTestSupport.translationHint(mockMvc, student.token(), SOURCE, "keyword")
                .andExpect(matcher)
                .andReturn();
    }

    private JsonNode expectReview() throws Exception {
        MvcResult result = ApiTestSupport
                .translationReview(mockMvc, student.token(), SOURCE, STUDENT_TRANSLATION)
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = ApiTestSupport.body(result);
        assertThat(json.get("sourceText").asText()).isEqualTo(SOURCE);
        assertThat(json.get("studentTranslation").asText()).isEqualTo(STUDENT_TRANSLATION);
        return json;
    }

    private MvcResult expectReviewError(ResultMatcher matcher) throws Exception {
        return ApiTestSupport.translationReview(mockMvc, student.token(), SOURCE, STUDENT_TRANSLATION)
                .andExpect(matcher)
                .andReturn();
    }

    private static List<JsonNode> items(JsonNode json, String field) {
        List<JsonNode> items = new ArrayList<>();
        json.get(field).forEach(items::add);
        return items;
    }

    private static void assertErrorBody(MvcResult result, String expectedCode) throws Exception {
        JsonNode json = ApiTestSupport.body(result);
        assertThat(fieldNames(json)).containsExactlyInAnyOrder("code", "message");
        assertThat(json.get("code").asText()).isEqualTo(expectedCode);
        String message = json.get("message").asText();
        assertThat(message).isNotBlank();
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
        return text.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN);
    }
}
