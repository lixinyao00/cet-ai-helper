package com.cethelper.server.translation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cethelper.server.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 汉译英批改接口在 demo 模式下的契约测试。
 *
 * <p>覆盖：草稿译文的漏译与生硬表达（引用逐字可查）、补全后的译文不再被标出同样的漏译、
 * 准确译文与另一种合理译法返回空问题列表且反馈不同、未收录译文的 422、各类非法输入的 400、
 * 匿名与退出后的 401、成功体不泄露凭证。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("汉译英批改（demo）")
class TranslationReviewDemoApiTest {

    private static final String SOURCE = DemoTranslationCatalog.SOURCE_TEXT;

    /** 草稿里被标为漏译的两处中文原文：对象与程度各一处，依据互相独立。 */
    private static final List<String> DRAFT_OMISSION_QUOTES = List.of("对高校毕业生", "越来越");

    @Autowired
    private MockMvc mockMvc;

    private ApiTestSupport.Student student;

    @BeforeEach
    void createStudent() throws Exception {
        student = ApiTestSupport.newStudent(mockMvc);
    }

    @Test
    @DisplayName("匿名调用与无效凭证都返回 401")
    void anonymousOrInvalidTokenIsUnauthorized() throws Exception {
        MvcResult anonymous = ApiTestSupport.translationReview(mockMvc, null, SOURCE,
                        DemoTranslationCatalog.DRAFT_TRANSLATION)
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertErrorBody(anonymous, "UNAUTHORIZED");

        MvcResult bogus = ApiTestSupport.translationReview(mockMvc, "definitely-not-a-valid-token", SOURCE,
                        DemoTranslationCatalog.DRAFT_TRANSLATION)
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertErrorBody(bogus, "UNAUTHORIZED");
    }

    @Test
    @DisplayName("草稿译文：两条漏译 + 一条不地道搭配，引用都能在对应输入里逐字找到")
    void draftTranslationGetsOmissionAndUnnaturalIssues() throws Exception {
        JsonNode json = expectReview(DemoTranslationCatalog.DRAFT_TRANSLATION);

        assertThat(fieldNames(json))
                .as("批改响应必须恰好是约定的五个字段")
                .containsExactlyInAnyOrder("sourceText", "studentTranslation", "overallFeedback", "issues", "mode");
        assertThat(json.get("mode").asText()).isEqualTo("demo");
        assertThat(json.get("overallFeedback").asText()).isNotBlank();

        List<JsonNode> issues = items(json, "issues");
        assertThat(issues).as("草稿应返回 3 条问题：2 条漏译 + 1 条不地道搭配").hasSize(3);
        assertThat(issues).extracting(issue -> issue.get("type").asText())
                .as("先两条漏译，再一条表达不自然")
                .containsExactly("OMISSION", "OMISSION", "UNNATURAL");

        for (int i = 0; i < issues.size(); i++) {
            JsonNode issue = issues.get(i);
            assertThat(fieldNames(issue))
                    .as("每条问题只有 type/quote/detail/suggestion")
                    .containsExactlyInAnyOrder("type", "quote", "detail", "suggestion");
            assertThat(issue.get("detail").asText()).isNotBlank();
            assertThat(issue.get("suggestion").asText()).isNotBlank();

            String quote = issue.get("quote").asText();
            if ("OMISSION".equals(issue.get("type").asText())) {
                assertThat(SOURCE).as("漏译引用必须是中文原文中的连续原文：%s", quote).contains(quote);
                assertThat(containsChinese(quote)).as("漏译引用应为中文：%s", quote).isTrue();
            } else {
                assertThat(DemoTranslationCatalog.DRAFT_TRANSLATION)
                        .as("表达不自然的引用必须逐字出自学生译文：%s", quote)
                        .contains(quote);
            }
        }
        assertThat(issues).extracting(issue -> issue.get("quote").asText())
                .containsExactlyElementsOf(List.of("对高校毕业生", "越来越", "have good team cooperation"));

        // 三类问题的依据互相独立：漏译引用是中文原文里的片段，不地道搭配引用的是学生译文里的英文片段。
        String unnaturalQuote = issues.get(2).get("quote").asText();
        assertThat(SOURCE).as("UNNATURAL 的引用不应是中文原文片段").doesNotContain(unnaturalQuote);
        assertThat(DemoTranslationCatalog.DRAFT_TRANSLATION).contains(unnaturalQuote);
    }

    @Test
    @DisplayName("补全后的译文：不再返回任何问题，被标出的漏译内容不再出现")
    void completedTranslationHasNoIssuesAndDropsOmissionQuotes() throws Exception {
        String completed = DemoTranslationCatalog.COMPLETED_TRANSLATION;
        JsonNode json = expectReview(completed);

        List<JsonNode> issues = items(json, "issues");
        assertThat(issues).as("补全后不应再有漏译或生硬表达").isEmpty();

        // 逐条核对：草稿里被标为漏译的两处中文原文，不能再出现在任何问题的引用里。
        List<String> quotes = issues.stream().map(issue -> issue.get("quote").asText()).toList();
        assertThat(quotes).doesNotContainAnyElementsOf(DRAFT_OMISSION_QUOTES);
        assertThat(json.get("studentTranslation").asText()).isEqualTo(completed);
        assertThat(json.get("overallFeedback").asText()).isNotBlank();
    }

    @Test
    @DisplayName("准确译文与另一种合理译法都返回空问题列表，但反馈内容不同")
    void accurateAndAlternateTranslationsBothPassWithDifferentFeedback() throws Exception {
        JsonNode accurate = expectReview(DemoTranslationCatalog.ACCURATE_TRANSLATION);
        JsonNode alternate = expectReview(DemoTranslationCatalog.ALTERNATE_TRANSLATION);

        assertThat(items(accurate, "issues")).as("准确译文不应被挑错").isEmpty();
        assertThat(items(alternate, "issues")).as("另一种合理译法同样不应被挑错").isEmpty();
        assertThat(accurate.get("overallFeedback").asText())
                .as("不同译文不能拿到同一份反馈")
                .isNotEqualTo(alternate.get("overallFeedback").asText());
    }

    @Test
    @DisplayName("未收录的译文返回 422 DEMO_UNSUPPORTED，不会套用内置批改")
    void unsupportedTranslationReturnsDemoUnsupported() throws Exception {
        String other = "The digital economy is important. Companies hire young people.";
        MvcResult result = ApiTestSupport.translationReview(mockMvc, student.token(), SOURCE, other)
                .andExpect(status().isUnprocessableEntity())
                .andReturn();
        assertErrorBody(result, "DEMO_UNSUPPORTED");
    }

    @Test
    @DisplayName("内置译文之外，改一个词也不再命中 demo（匹配是逐字比较）")
    void nearMissTranslationIsUnsupported() throws Exception {
        String nearMiss = DemoTranslationCatalog.COMPLETED_TRANSLATION.replace("As a result", "Therefore");
        MvcResult result = ApiTestSupport.translationReview(mockMvc, student.token(), SOURCE, nearMiss)
                .andExpect(status().isUnprocessableEntity())
                .andReturn();
        assertErrorBody(result, "DEMO_UNSUPPORTED");
    }

    @Test
    @DisplayName("原文或译文缺失、空白、类型错误、超长都返回 400 INVALID_INPUT")
    void invalidInputReturnsInvalidInput() throws Exception {
        assertBadRequest(ApiTestSupport.translationReview(mockMvc, student.token(), "", DemoTranslationCatalog.DRAFT_TRANSLATION));
        assertBadRequest(ApiTestSupport.translationReview(mockMvc, student.token(), "   ", DemoTranslationCatalog.DRAFT_TRANSLATION));
        assertBadRequest(ApiTestSupport.translationReview(mockMvc, student.token(), SOURCE, ""));
        assertBadRequest(ApiTestSupport.translationReview(mockMvc, student.token(), SOURCE, "  \t "));
        assertBadRequest(ApiTestSupport.translationReviewRaw(mockMvc, student.token(),
                "{\"sourceText\":\"" + SOURCE + "\"}"));
        assertBadRequest(ApiTestSupport.translationReviewRaw(mockMvc, student.token(),
                "{\"studentTranslation\":\"Some English.\"}"));
        assertBadRequest(ApiTestSupport.translationReviewRaw(mockMvc, student.token(),
                "{\"sourceText\":\"" + SOURCE + "\",\"studentTranslation\":null}"));
        assertBadRequest(ApiTestSupport.translationReviewRaw(mockMvc, student.token(),
                "{\"sourceText\":\"" + SOURCE + "\",\"studentTranslation\":42}"));
        assertBadRequest(ApiTestSupport.translationReviewRaw(mockMvc, student.token(),
                "{\"sourceText\":\"" + SOURCE + "\",\"studentTranslation\":\"abc\","));
        assertBadRequest(ApiTestSupport.translationReviewRaw(mockMvc, student.token(), ""));
        // 原文超过 2000 字符
        assertBadRequest(ApiTestSupport.translationReview(mockMvc, student.token(), "汉".repeat(2001),
                DemoTranslationCatalog.DRAFT_TRANSLATION));
        // 译文超过 3000 字符
        assertBadRequest(ApiTestSupport.translationReview(mockMvc, student.token(), SOURCE,
                "a".repeat(3001)));
    }

    @Test
    @DisplayName("退出登录后复用旧凭证返回 401")
    void loggedOutTokenIsRejected() throws Exception {
        ApiTestSupport.translationReview(mockMvc, student.token(), SOURCE, DemoTranslationCatalog.DRAFT_TRANSLATION)
                .andExpect(status().isOk());
        ApiTestSupport.logout(mockMvc, student.token()).andExpect(status().is2xxSuccessful());

        MvcResult result = ApiTestSupport
                .translationReview(mockMvc, student.token(), SOURCE, DemoTranslationCatalog.DRAFT_TRANSLATION)
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertErrorBody(result, "UNAUTHORIZED");
    }

    @Test
    @DisplayName("成功体不泄露口令与凭证")
    void successBodyDoesNotExposeCredentials() throws Exception {
        MvcResult result = ApiTestSupport
                .translationReview(mockMvc, student.token(), SOURCE, DemoTranslationCatalog.DRAFT_TRANSLATION)
                .andExpect(status().isOk())
                .andReturn();
        String text = ApiTestSupport.bodyText(result);
        assertThat(text).doesNotContain(student.password());
        assertThat(text).doesNotContain(student.token());
    }

    private JsonNode expectReview(String studentTranslation) throws Exception {
        MvcResult result = ApiTestSupport
                .translationReview(mockMvc, student.token(), SOURCE, studentTranslation)
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = ApiTestSupport.body(result);
        assertThat(json.get("sourceText").asText()).isEqualTo(SOURCE);
        assertThat(json.get("studentTranslation").asText()).isEqualTo(studentTranslation);
        return json;
    }

    private static List<JsonNode> items(JsonNode json, String field) {
        List<JsonNode> items = new ArrayList<>();
        json.get(field).forEach(items::add);
        return items;
    }

    private static void assertBadRequest(ResultActions actions) throws Exception {
        MvcResult result = actions.andExpect(status().isBadRequest()).andReturn();
        assertErrorBody(result, "INVALID_INPUT");
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
