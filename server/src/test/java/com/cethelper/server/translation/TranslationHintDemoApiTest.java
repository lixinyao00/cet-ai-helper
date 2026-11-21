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
 * 汉译英提示接口在 demo 模式下的契约测试。
 *
 * <p>覆盖：三类提示各自只返回自己的字段、提示里的中文片段逐字出自原文、不返回整段参考译文、
 * 提示类型非法与原文缺失的 400、未收录材料的 422、首尾空白规范化、匿名与退出后的 401。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("汉译英提示（demo）")
class TranslationHintDemoApiTest {

    private static final String SOURCE = DemoTranslationCatalog.SOURCE_TEXT;

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
        MvcResult anonymous = ApiTestSupport.translationHint(mockMvc, null, SOURCE, "keyword")
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertErrorBody(anonymous, "UNAUTHORIZED");

        MvcResult bogus = ApiTestSupport.translationHint(mockMvc, "definitely-not-a-valid-token", SOURCE, "keyword")
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertErrorBody(bogus, "UNAUTHORIZED");
    }

    @Test
    @DisplayName("keyword：只返回 keywords，中文关键词逐字出自原文，并给出英文表达与说明")
    void keywordHintReturnsKeywordsOnly() throws Exception {
        JsonNode json = expectHint("keyword");

        assertThat(fieldNames(json))
                .as("keyword 提示只应包含约定字段，不含 phrases/structure")
                .containsExactlyInAnyOrder("sourceText", "hintType", "keywords", "mode");
        assertThat(json.get("hintType").asText()).isEqualTo("keyword");
        assertThat(json.get("mode").asText()).isEqualTo("demo");
        assertThat(json.has("phrases")).isFalse();
        assertThat(json.has("structure")).isFalse();

        List<JsonNode> keywords = items(json, "keywords");
        assertThat(keywords).as("内置关键词应为 5 个").hasSize(5);
        for (JsonNode keyword : keywords) {
            assertThat(fieldNames(keyword))
                    .as("每个关键词条目只有 source/expressions/note")
                    .containsExactlyInAnyOrder("source", "expressions", "note");
            String source = keyword.get("source").asText();
            assertThat(SOURCE).as("关键词「%s」必须逐字出现在中文原文里", source).contains(source);
            assertThat(keyword.get("expressions").size())
                    .as("每个关键词都要给英文表达").isGreaterThan(0);
            assertThat(keyword.get("note").asText()).isNotBlank();
        }
        assertThat(responseText(json)).as("提示不能直接给出整段参考译文").doesNotContain(
                DemoTranslationCatalog.ACCURATE_TRANSLATION);
    }

    @Test
    @DisplayName("phrase：只返回 phrases，含「越来越重要」「既能……又能……」等短语提示")
    void phraseHintReturnsPhrasesOnly() throws Exception {
        JsonNode json = expectHint("phrase");

        assertThat(fieldNames(json))
                .containsExactlyInAnyOrder("sourceText", "hintType", "phrases", "mode");
        assertThat(json.has("keywords")).isFalse();
        assertThat(json.has("structure")).isFalse();

        List<JsonNode> phrases = items(json, "phrases");
        assertThat(phrases).as("内置短语应为 4 个").hasSize(4);
        for (JsonNode phrase : phrases) {
            String source = phrase.get("source").asText();
            assertThat(SOURCE).as("短语「%s」必须逐字出现在中文原文里", source).contains(source);
            assertThat(phrase.get("expressions").size()).isGreaterThan(0);
        }
        assertThat(phrases).extracting(phrase -> phrase.get("source").asText())
                .contains("越来越重要", "既能独立思考又能团队协作");
        assertThat(responseText(json)).as("提示不能直接给出整段参考译文").doesNotContain(
                DemoTranslationCatalog.ACCURATE_TRANSLATION);
    }

    @Test
    @DisplayName("structure：返回主干、分句与连接方式，分句片段逐字出自原文")
    void structureHintReturnsMainClauseAndClauses() throws Exception {
        JsonNode json = expectHint("structure");

        assertThat(fieldNames(json))
                .containsExactlyInAnyOrder("sourceText", "hintType", "structure", "mode");
        assertThat(json.has("keywords")).isFalse();
        assertThat(json.has("phrases")).isFalse();

        JsonNode structure = json.get("structure");
        assertThat(fieldNames(structure))
                .containsExactlyInAnyOrder("mainClause", "clauses", "connectives");
        assertThat(structure.get("mainClause").asText())
                .as("主干说明应点出主语与「对……越来越重要」").contains("主干").contains("越来越重要");

        List<JsonNode> clauses = items(structure, "clauses");
        assertThat(clauses).as("内置结构提示应有 2 个分句").hasSize(2);
        for (JsonNode clause : clauses) {
            String source = clause.get("source").asText();
            assertThat(SOURCE).as("分句片段「%s」必须逐字出现在中文原文里", source).contains(source);
            assertThat(clause.get("role").asText()).isNotBlank();
            assertThat(clause.get("guidance").asText()).isNotBlank();
        }
        List<JsonNode> connectives = items(structure, "connectives");
        assertThat(connectives).as("内置结构提示应有 2 处连接方式").hasSize(2);
        for (JsonNode connective : connectives) {
            String source = connective.get("source").asText();
            assertThat(SOURCE).contains(source);
            assertThat(connective.get("suggestion").asText()).isNotBlank();
        }
        assertThat(responseText(json)).as("结构提示也不能给出整段参考译文").doesNotContain(
                DemoTranslationCatalog.ACCURATE_TRANSLATION);
    }

    @Test
    @DisplayName("三类提示互为不同的返回内容，不会一次返回全部")
    void eachHintTypeReturnsItsOwnPart() throws Exception {
        String keyword = responseText(ApiTestSupport.body(ApiTestSupport
                .translationHint(mockMvc, student.token(), SOURCE, "keyword")
                .andExpect(status().isOk()).andReturn()));
        String phrase = responseText(ApiTestSupport.body(ApiTestSupport
                .translationHint(mockMvc, student.token(), SOURCE, "phrase")
                .andExpect(status().isOk()).andReturn()));
        String structure = responseText(ApiTestSupport.body(ApiTestSupport
                .translationHint(mockMvc, student.token(), SOURCE, "structure")
                .andExpect(status().isOk()).andReturn()));

        assertThat(keyword).isNotEqualTo(phrase).isNotEqualTo(structure);
        assertThat(phrase).isNotEqualTo(structure);
    }

    @Test
    @DisplayName("提示类型非法返回 400 INVALID_INPUT")
    void invalidHintTypeReturnsInvalidInput() throws Exception {
        assertBadRequest(ApiTestSupport.translationHint(mockMvc, student.token(), SOURCE, "grammar"));
        assertBadRequest(ApiTestSupport.translationHint(mockMvc, student.token(), SOURCE, ""));
        assertBadRequest(ApiTestSupport.translationHint(mockMvc, student.token(), SOURCE, "   "));
        assertBadRequest(ApiTestSupport.translationHintRaw(mockMvc, student.token(),
                "{\"sourceText\":\"" + SOURCE + "\"}"));
        assertBadRequest(ApiTestSupport.translationHintRaw(mockMvc, student.token(),
                "{\"sourceText\":\"" + SOURCE + "\",\"hintType\":null}"));
        assertBadRequest(ApiTestSupport.translationHintRaw(mockMvc, student.token(),
                "{\"sourceText\":\"" + SOURCE + "\",\"hintType\":123}"));
    }

    @Test
    @DisplayName("原文缺失、空白、超长返回 400 INVALID_INPUT")
    void invalidSourceTextReturnsInvalidInput() throws Exception {
        assertBadRequest(ApiTestSupport.translationHint(mockMvc, student.token(), "", "keyword"));
        assertBadRequest(ApiTestSupport.translationHint(mockMvc, student.token(), "   ", "keyword"));
        assertBadRequest(ApiTestSupport.translationHintRaw(mockMvc, student.token(), "{\"hintType\":\"keyword\"}"));
        assertBadRequest(ApiTestSupport.translationHintRaw(mockMvc, student.token(),
                "{\"sourceText\":null,\"hintType\":\"keyword\"}"));
        assertBadRequest(ApiTestSupport.translationHintRaw(mockMvc, student.token(),
                "{\"sourceText\":123,\"hintType\":\"keyword\"}"));
        // 请求体为空
        assertBadRequest(ApiTestSupport.translationHintRaw(mockMvc, student.token(), ""));
        // 超过 2000 字符
        assertBadRequest(ApiTestSupport.translationHint(mockMvc, student.token(), "汉".repeat(2001), "keyword"));
    }

    @Test
    @DisplayName("demo 未收录的材料返回 422 DEMO_UNSUPPORTED")
    void unsupportedMaterialReturnsDemoUnsupported() throws Exception {
        String other = "阅读是获取知识的重要途径。";
        MvcResult result = ApiTestSupport.translationHint(mockMvc, student.token(), other, "keyword")
                .andExpect(status().isUnprocessableEntity())
                .andReturn();
        assertErrorBody(result, "DEMO_UNSUPPORTED");
    }

    @Test
    @DisplayName("首尾空白在匹配前被去掉，响应回显去掉空白后的原文")
    void paddedSourceTextIsNormalized() throws Exception {
        String padded = "\t" + SOURCE + "\u3000 ";
        JsonNode json = ApiTestSupport.body(ApiTestSupport
                .translationHint(mockMvc, student.token(), padded, " keyword ")
                .andExpect(status().isOk())
                .andReturn());
        assertThat(json.get("sourceText").asText()).isEqualTo(SOURCE);
        assertThat(json.get("hintType").asText()).isEqualTo("keyword");
    }

    @Test
    @DisplayName("退出登录后复用旧凭证返回 401")
    void loggedOutTokenIsRejected() throws Exception {
        ApiTestSupport.translationHint(mockMvc, student.token(), SOURCE, "keyword").andExpect(status().isOk());
        ApiTestSupport.logout(mockMvc, student.token()).andExpect(status().is2xxSuccessful());

        MvcResult result = ApiTestSupport.translationHint(mockMvc, student.token(), SOURCE, "keyword")
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertErrorBody(result, "UNAUTHORIZED");
    }

    @Test
    @DisplayName("成功体不泄露口令与凭证")
    void successBodyDoesNotExposeCredentials() throws Exception {
        MvcResult result = ApiTestSupport.translationHint(mockMvc, student.token(), SOURCE, "structure")
                .andExpect(status().isOk())
                .andReturn();
        String text = ApiTestSupport.bodyText(result);
        assertThat(text).doesNotContain(student.password());
        assertThat(text).doesNotContain(student.token());
    }

    private JsonNode expectHint(String hintType) throws Exception {
        MvcResult result = ApiTestSupport.translationHint(mockMvc, student.token(), SOURCE, hintType)
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = ApiTestSupport.body(result);
        assertThat(json.get("sourceText").asText()).isEqualTo(SOURCE);
        return json;
    }

    private static String responseText(JsonNode json) {
        return json.toString();
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
