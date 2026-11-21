package com.cethelper.server.explain;

import com.cethelper.server.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
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

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 释义接口在 demo 模式下的契约测试。
 *
 * <p>覆盖：匿名/无效凭证的 401、三条内置示例（同一单词不同语境）、未收录组合的 422、
 * 选词与原句不匹配及各形态非法报文的 400、退出登录后旧凭证失效、成功体不泄露账号与凭证。</p>
 *
 * <p>全部用例只通过 MockMvc 走 HTTP 契约，不注入任何 repository/service，保证与真实调用一致。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ExplainDemoApiTest {

    /** 与内置示例同结构但把 decline 换成 increase 的句子：合法英文，但 "decline" 不是它的子串。 */
    private static final String SENTENCE_WITHOUT_DECLINE = "The report attributed the increase to higher demand.";

    @Autowired
    private MockMvc mockMvc;

    /** 每个用例独立注册并登录一名学生，避免凭证状态互相影响。 */
    private ApiTestSupport.Student student;

    @BeforeEach
    void createStudent() throws Exception {
        student = ApiTestSupport.newStudent(mockMvc);
    }

    @Test
    @DisplayName("匿名调用与无效凭证都返回 401，错误体只有 code/message 且提示为中文")
    void anonymousOrInvalidTokenIsUnauthorized() throws Exception {
        MvcResult anonymous = ApiTestSupport
                .explain(mockMvc, null, "decline", ApiTestSupport.SENTENCE_DECLINE_NOUN)
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertErrorBody(anonymous, "UNAUTHORIZED");

        MvcResult bogusToken = ApiTestSupport
                .explain(mockMvc, "definitely-not-a-valid-token", "decline", ApiTestSupport.SENTENCE_DECLINE_NOUN)
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertErrorBody(bogusToken, "UNAUTHORIZED");
    }

    @Test
    @DisplayName("内置示例一：decline 在第一句作名词，释义为「下降」")
    void declineAsNounMeansDecrease() throws Exception {
        expectExplanation("decline", ApiTestSupport.SENTENCE_DECLINE_NOUN, "下降");
    }

    @Test
    @DisplayName("内置示例二：decline 在第二句作动词，释义为「婉拒」")
    void declineAsVerbMeansPolitelyRefuse() throws Exception {
        expectExplanation("decline", ApiTestSupport.SENTENCE_DECLINE_VERB, "婉拒");
    }

    @Test
    @DisplayName("内置示例三：词组 lower demand 释义为「较低的需求」")
    void lowerDemandMeansLowerDemand() throws Exception {
        expectExplanation("lower demand", ApiTestSupport.SENTENCE_DECLINE_NOUN, "较低的需求");
    }

    @Test
    @DisplayName("同一单词 decline 在两句话里得到不同释义（下降 / 婉拒）")
    void sameWordGetsDifferentMeaningPerSentence() throws Exception {
        JsonNode noun = ApiTestSupport.body(ApiTestSupport
                .explain(mockMvc, student.token(), "decline", ApiTestSupport.SENTENCE_DECLINE_NOUN)
                .andExpect(status().isOk())
                .andReturn());
        JsonNode verb = ApiTestSupport.body(ApiTestSupport
                .explain(mockMvc, student.token(), "decline", ApiTestSupport.SENTENCE_DECLINE_VERB)
                .andExpect(status().isOk())
                .andReturn());

        assertThat(noun.get("meaning").asText()).isEqualTo("下降");
        assertThat(verb.get("meaning").asText()).isEqualTo("婉拒");
        assertThat(noun.get("meaning").asText())
                .as("同一单词在不同语境下必须给出不同释义")
                .isNotEqualTo(verb.get("meaning").asText());
    }

    @Test
    @DisplayName("demo 未收录但合法的组合返回 422 DEMO_UNSUPPORTED")
    void unsupportedCombinationReturnsDemoUnsupported() throws Exception {
        // "invitation" 确实是第二句的连续子串，只是不在内置示例表里，属于「合法但未收录」。
        MvcResult result = ApiTestSupport
                .explain(mockMvc, student.token(), "invitation", ApiTestSupport.SENTENCE_DECLINE_VERB)
                .andExpect(status().isUnprocessableEntity())
                .andReturn();
        assertErrorBody(result, "DEMO_UNSUPPORTED");
    }

    @Test
    @DisplayName("selection 不是 sentence 的连续子串返回 400 INVALID_INPUT")
    void selectionNotInSentenceReturnsInvalidInput() throws Exception {
        MvcResult result = ApiTestSupport
                .explain(mockMvc, student.token(), "decline", SENTENCE_WITHOUT_DECLINE)
                .andExpect(status().isBadRequest())
                .andReturn();
        assertErrorBody(result, "INVALID_INPUT");
    }

    @Test
    @DisplayName("selection / sentence 为空白返回 400 INVALID_INPUT")
    void blankFieldsReturnInvalidInput() throws Exception {
        assertBadRequest(ApiTestSupport.explain(mockMvc, student.token(), "   ", ApiTestSupport.SENTENCE_DECLINE_NOUN));
        assertBadRequest(ApiTestSupport.explain(mockMvc, student.token(), "", ApiTestSupport.SENTENCE_DECLINE_NOUN));
        assertBadRequest(ApiTestSupport.explain(mockMvc, student.token(), "decline", "  "));
    }

    @Test
    @DisplayName("字段缺失、字段为 null、JSON 语法错误、请求体为空都返回 400 INVALID_INPUT")
    void malformedBodiesReturnInvalidInput() throws Exception {
        // 缺少 sentence
        assertBadRequest(ApiTestSupport.explainRaw(mockMvc, student.token(), "{\"selection\":\"decline\"}"));
        // 缺少 selection
        assertBadRequest(ApiTestSupport.explainRaw(mockMvc, student.token(),
                "{\"sentence\":\"" + ApiTestSupport.SENTENCE_DECLINE_VERB + "\"}"));
        // 字段显式为 null
        assertBadRequest(ApiTestSupport.explainRaw(mockMvc, student.token(),
                "{\"selection\":null,\"sentence\":\"" + ApiTestSupport.SENTENCE_DECLINE_VERB + "\"}"));
        // JSON 语法错误（缺少收尾的大括号）
        assertBadRequest(ApiTestSupport.explainRaw(mockMvc, student.token(), "{\"selection\":\"decline\","));
        // 空请求体
        assertBadRequest(ApiTestSupport.explainRaw(mockMvc, student.token(), ""));
    }

    @Test
    @DisplayName("退出登录后复用旧凭证查词返回 401 UNAUTHORIZED")
    void loggedOutTokenIsRejected() throws Exception {
        ApiTestSupport.explain(mockMvc, student.token(), "decline", ApiTestSupport.SENTENCE_DECLINE_NOUN)
                .andExpect(status().isOk());

        // 退出登录的状态码按常见实现为 200 或 204，这里只要求 2xx。
        ApiTestSupport.logout(mockMvc, student.token()).andExpect(status().is2xxSuccessful());

        MvcResult result = ApiTestSupport
                .explain(mockMvc, student.token(), "decline", ApiTestSupport.SENTENCE_DECLINE_NOUN)
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertErrorBody(result, "UNAUTHORIZED");
    }

    @Test
    @DisplayName("成功响应体只含 meaning/explanation/mode，不泄露口令或凭证")
    void successBodyDoesNotExposeCredentials() throws Exception {
        MvcResult result = ApiTestSupport
                .explain(mockMvc, student.token(), "decline", ApiTestSupport.SENTENCE_DECLINE_VERB)
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = ApiTestSupport.body(result);

        assertThat(fieldNames(json))
                .as("成功体必须恰好是契约约定的三个字段")
                .containsExactlyInAnyOrder("meaning", "explanation", "mode");
        assertThat(json.has("password")).isFalse();
        assertThat(json.has("token")).isFalse();
        assertThat(json.has("accessToken")).isFalse();

        String text = ApiTestSupport.bodyText(result);
        assertThat(text).doesNotContain(student.password());
        assertThat(text).doesNotContain(student.token());
    }

    /** 断言一次成功查词：状态码 200、mode 固定 demo、释义精确匹配、解释为非空中文。 */
    private JsonNode expectExplanation(String selection, String sentence, String expectedMeaning) throws Exception {
        MvcResult result = ApiTestSupport.explain(mockMvc, student.token(), selection, sentence)
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = ApiTestSupport.body(result);

        assertThat(fieldNames(json))
                .as("成功体必须恰好是契约约定的三个字段")
                .containsExactlyInAnyOrder("meaning", "explanation", "mode");
        assertThat(json.get("meaning").asText())
                .as("选词 %s 在 %s 中的释义", selection, sentence)
                .isEqualTo(expectedMeaning);
        assertThat(json.get("mode").asText()).as("demo 模式下 mode 必须为 demo").isEqualTo("demo");

        String explanation = json.get("explanation").asText();
        assertThat(explanation).as("explanation 不能为空").isNotBlank();
        assertThat(containsChinese(explanation)).as("explanation 应为中文：%s", explanation).isTrue();
        return json;
    }

    /** 断言 400 且错误码为 INVALID_INPUT。 */
    private static void assertBadRequest(ResultActions actions) throws Exception {
        MvcResult result = actions.andExpect(status().isBadRequest()).andReturn();
        assertErrorBody(result, "INVALID_INPUT");
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

    /** 判断文本是否包含汉字，用于验证释义与错误提示使用中文。 */
    private static boolean containsChinese(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return text.codePoints().anyMatch(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }
}
