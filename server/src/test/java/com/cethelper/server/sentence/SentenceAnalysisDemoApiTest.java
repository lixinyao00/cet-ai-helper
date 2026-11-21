package com.cethelper.server.sentence;

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
 * 整句分析接口在 demo 模式下的契约测试。
 *
 * <p>覆盖：匿名/无效凭证 401、内置文章的两句原句、含从句的长句拆解（片段按原句顺序、逐字摘自原句）、
 * 未收录句子的 422、各类非法输入的 400、首尾空白规范化、退出登录后旧凭证失效，
 * 以及成功体形状与凭证脱敏。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("整句分析（demo）")
class SentenceAnalysisDemoApiTest {

    @Autowired
    private MockMvc mockMvc;

    private ApiTestSupport.Student student;

    @BeforeEach
    void createStudent() throws Exception {
        student = ApiTestSupport.newStudent(mockMvc);
    }

    @Test
    @DisplayName("匿名调用与无效凭证都返回 401，错误体只有 code/message")
    void anonymousOrInvalidTokenIsUnauthorized() throws Exception {
        MvcResult anonymous = ApiTestSupport
                .sentenceAnalysis(mockMvc, null, DemoSentenceCatalog.REPORT_SENTENCE)
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertErrorBody(anonymous, "UNAUTHORIZED");

        MvcResult bogus = ApiTestSupport
                .sentenceAnalysis(mockMvc, "definitely-not-a-valid-token", DemoSentenceCatalog.REPORT_SENTENCE)
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertErrorBody(bogus, "UNAUTHORIZED");
    }

    @Test
    @DisplayName("内置文章第一句：返回 200、mode=demo、中文译文与主干，片段按原文顺序")
    void reportSentenceIsAnalyzed() throws Exception {
        JsonNode json = expectAnalysis(DemoSentenceCatalog.REPORT_SENTENCE);

        assertThat(json.get("translation").asText()).as("译文应为中文").contains("报告");
        assertThat(json.get("structure").asText()).as("主干说明应提到主语 The report").contains("The report");

        List<JsonNode> chunks = chunks(json);
        assertThat(chunks).as("该句应有 4 个语法片段").hasSize(4);
        assertThat(chunks.get(0).get("text").asText()).isEqualTo("The report");
        assertThat(chunks.get(1).get("text").asText()).isEqualTo("attributed");
        assertThat(chunks.get(3).get("text").asText()).isEqualTo("to lower demand");
        assertThat(chunks.get(1).get("role").asText()).as("attributed 的语法作用是谓语").contains("谓语");
    }

    @Test
    @DisplayName("内置文章第二句：decline 作动词，译文不出现「下降」")
    void studentsSentenceIsAnalyzed() throws Exception {
        JsonNode json = expectAnalysis(DemoSentenceCatalog.STUDENTS_SENTENCE);

        assertThat(json.get("translation").asText()).contains("谢绝");
        List<JsonNode> chunks = chunks(json);
        assertThat(chunks).as("该句应有 3 个语法片段").hasSize(3);
        assertThat(chunks.get(1).get("text").asText()).isEqualTo("decline");
        assertThat(chunks.get(1).get("explanation").asText()).as("这里 decline 是动词「婉拒」").contains("婉拒");
    }

    @Test
    @DisplayName("含从句的长句：片段按原句顺序返回，且能指出逗号后的主句主干")
    void clauseSentenceIsBrokenDownInOriginalOrder() throws Exception {
        String sentence = DemoSentenceCatalog.CLAUSE_SENTENCE;
        JsonNode json = expectAnalysis(sentence);

        assertThat(json.get("structure").asText())
                .as("主干说明应点出主句的主语与谓语")
                .contains("experienced teachers insist");
        assertThat(json.get("translation").asText()).as("译文应为中文且覆盖让步关系").contains("尽管");

        List<JsonNode> chunks = chunks(json);
        assertThat(chunks).as("长句应拆成 6 个片段，含让步从句、定语从句与两个宾语从句").hasSize(6);

        // 片段的 text 必须逐字出自原句，且按原句从前到后排列（不重叠）。
        int cursor = 0;
        for (JsonNode chunk : chunks) {
            String text = chunk.get("text").asText();
            int found = sentence.indexOf(text, cursor);
            assertThat(found).as("片段「%s」应能在原句中按顺序定位", text).isGreaterThanOrEqualTo(0);
            cursor = found + text.length();
        }
        assertThat(cursor).as("片段应覆盖到句末的 vocabulary").isPositive();

        // 长句拆解的关键：先指出主句，再把从句挂回去。
        assertThat(chunks).extracting(chunk -> chunk.get("text").asText())
                .as("应包含主句主语+谓语的片段")
                .contains("experienced teachers insist");
        assertThat(chunks).extracting(chunk -> chunk.get("role").asText())
                .as("应标出定语从句与 what 表语从句")
                .anySatisfy(role -> assertThat(role).contains("定语从句"))
                .anySatisfy(role -> assertThat(role).contains("表语从句"));
    }

    @Test
    @DisplayName("首尾空白（空格、制表符、全角空格）在匹配前被去掉")
    void paddedSentenceStillMatches() throws Exception {
        String padded = "\t " + DemoSentenceCatalog.STUDENTS_SENTENCE + "\u3000 ";
        JsonNode json = expectAnalysis(padded);
        assertThat(json.get("sentence").asText())
                .as("响应里的 sentence 应是去掉首尾空白后的原句")
                .isEqualTo(DemoSentenceCatalog.STUDENTS_SENTENCE);
        assertThat(json.get("mode").asText()).isEqualTo("demo");
    }

    @Test
    @DisplayName("成功体只含约定的五个字段，每个片段只含 text/role/explanation，且不泄露凭证")
    void successBodyShapeAndNoCredentialLeak() throws Exception {
        MvcResult result = ApiTestSupport
                .sentenceAnalysis(mockMvc, student.token(), DemoSentenceCatalog.CLAUSE_SENTENCE)
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = ApiTestSupport.body(result);

        assertThat(fieldNames(json))
                .as("成功体必须恰好是契约约定的五个字段")
                .containsExactlyInAnyOrder("sentence", "translation", "structure", "chunks", "mode");
        for (JsonNode chunk : chunks(json)) {
            assertThat(fieldNames(chunk))
                    .as("每个片段必须只有 text/role/explanation 三个字段")
                    .containsExactlyInAnyOrder("text", "role", "explanation");
            assertThat(chunk.get("text").asText()).isNotBlank();
            assertThat(chunk.get("role").asText()).isNotBlank();
            assertThat(chunk.get("explanation").asText()).isNotBlank();
            assertThat(containsChinese(chunk.get("role").asText())
                    || containsChinese(chunk.get("explanation").asText()))
                    .as("role 与 explanation 应为中文：%s / %s",
                            chunk.get("role").asText(), chunk.get("explanation").asText())
                    .isTrue();
        }

        String text = ApiTestSupport.bodyText(result);
        assertThat(text).doesNotContain(student.password());
        assertThat(text).doesNotContain(student.token());
    }

    @Test
    @DisplayName("demo 未收录但合法的英文句子返回 422 DEMO_UNSUPPORTED")
    void unsupportedSentenceReturnsDemoUnsupported() throws Exception {
        MvcResult result = ApiTestSupport
                .sentenceAnalysis(mockMvc, student.token(),
                        "Reading widely is what really builds vocabulary.")
                .andExpect(status().isUnprocessableEntity())
                .andReturn();
        assertErrorBody(result, "DEMO_UNSUPPORTED");
    }

    @Test
    @DisplayName("句子为空、缺失、null 或只有空白返回 400 INVALID_INPUT")
    void blankOrMissingSentenceReturnsInvalidInput() throws Exception {
        assertBadRequest(ApiTestSupport.sentenceAnalysisRaw(mockMvc, student.token(), "{}"));
        assertBadRequest(ApiTestSupport.sentenceAnalysisRaw(mockMvc, student.token(), "{\"sentence\":null}"));
        assertBadRequest(ApiTestSupport.sentenceAnalysisRaw(mockMvc, student.token(), "{\"sentence\":\"   \"}"));
        assertBadRequest(ApiTestSupport.sentenceAnalysisRaw(mockMvc, student.token(), "{\"sentence\":\"\\t\\n\\u3000\"}"));
        assertBadRequest(ApiTestSupport.sentenceAnalysis(mockMvc, student.token(), ""));
        // 空请求体
        assertBadRequest(ApiTestSupport.sentenceAnalysisRaw(mockMvc, student.token(), ""));
    }

    @Test
    @DisplayName("字段类型错误、JSON 语法错误返回 400 INVALID_INPUT")
    void malformedJsonReturnsInvalidInput() throws Exception {
        // 数字不会被当成字符串
        assertBadRequest(ApiTestSupport.sentenceAnalysisRaw(mockMvc, student.token(), "{\"sentence\":123}"));
        assertBadRequest(ApiTestSupport.sentenceAnalysisRaw(mockMvc, student.token(), "{\"sentence\":true}"));
        // 缺少收尾的大括号
        assertBadRequest(ApiTestSupport.sentenceAnalysisRaw(mockMvc, student.token(), "{\"sentence\":\"abc\","));
        // sentence 传成数组
        assertBadRequest(ApiTestSupport.sentenceAnalysisRaw(mockMvc, student.token(), "{\"sentence\":[\"abc\"]}"));
    }

    @Test
    @DisplayName("超长原句（超过 2000 字符）返回 400 INVALID_INPUT")
    void tooLongSentenceReturnsInvalidInput() throws Exception {
        String longSentence = "The report attributed the decline to lower demand. ".repeat(45);
        assertThat(longSentence.length()).isGreaterThan(2000);
        assertBadRequest(ApiTestSupport.sentenceAnalysis(mockMvc, student.token(), longSentence));
    }

    @Test
    @DisplayName("退出登录后复用旧凭证返回 401 UNAUTHORIZED")
    void loggedOutTokenIsRejected() throws Exception {
        ApiTestSupport.sentenceAnalysis(mockMvc, student.token(), DemoSentenceCatalog.REPORT_SENTENCE)
                .andExpect(status().isOk());

        ApiTestSupport.logout(mockMvc, student.token()).andExpect(status().is2xxSuccessful());

        MvcResult result = ApiTestSupport
                .sentenceAnalysis(mockMvc, student.token(), DemoSentenceCatalog.REPORT_SENTENCE)
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertErrorBody(result, "UNAUTHORIZED");
    }

    /** 断言一次成功分析：200、mode=demo、sentence 回显、译文与主干为中文、片段非空。 */
    private JsonNode expectAnalysis(String sentence) throws Exception {
        MvcResult result = ApiTestSupport.sentenceAnalysis(mockMvc, student.token(), sentence)
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = ApiTestSupport.body(result);

        assertThat(fieldNames(json))
                .as("成功体必须恰好是契约约定的五个字段")
                .containsExactlyInAnyOrder("sentence", "translation", "structure", "chunks", "mode");
        assertThat(json.get("sentence").asText())
                .as("响应应回显被分析的原句")
                .isEqualTo(sentence.strip());
        assertThat(json.get("mode").asText()).as("demo 模式下 mode 必须为 demo").isEqualTo("demo");

        String translation = json.get("translation").asText();
        String structure = json.get("structure").asText();
        assertThat(translation).as("translation 不能为空").isNotBlank();
        assertThat(structure).as("structure 不能为空").isNotBlank();
        assertThat(containsChinese(translation)).as("translation 应为中文：%s", translation).isTrue();
        assertThat(containsChinese(structure)).as("structure 应为中文：%s", structure).isTrue();
        assertThat(chunks(json)).as("chunks 不能为空").isNotEmpty();
        return json;
    }

    private static List<JsonNode> chunks(JsonNode json) {
        List<JsonNode> chunks = new ArrayList<>();
        json.get("chunks").forEach(chunks::add);
        return chunks;
    }

    private static void assertBadRequest(ResultActions actions) throws Exception {
        MvcResult result = actions.andExpect(status().isBadRequest()).andReturn();
        assertErrorBody(result, "INVALID_INPUT");
    }

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
