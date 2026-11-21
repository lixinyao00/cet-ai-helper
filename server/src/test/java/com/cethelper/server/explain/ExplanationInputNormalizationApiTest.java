package com.cethelper.server.explain;

import static com.cethelper.server.support.ApiTestSupport.SENTENCE_DECLINE_NOUN;
import static com.cethelper.server.support.ApiTestSupport.SENTENCE_DECLINE_VERB;
import static com.cethelper.server.support.ApiTestSupport.body;
import static com.cethelper.server.support.ApiTestSupport.explain;
import static com.cethelper.server.support.ApiTestSupport.explainRaw;
import static com.cethelper.server.support.ApiTestSupport.json;
import static com.cethelper.server.support.ApiTestSupport.newStudent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cethelper.server.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 查词输入的规范化与类型校验回归测试。
 *
 * <p>覆盖本轮两项修复：首尾空白先去再校验（demo 与 live 共用同一份输入、内部空格保留），
 * 以及数字/布尔值不能被当成字符串接受。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("查词输入的规范化与类型校验")
class ExplanationInputNormalizationApiTest {

    /** 全角空格，用于确认首尾空白按 Unicode 语义处理而不只是 ASCII 空格。 */
    private static final String IDEOGRAPHIC_SPACE = "\u3000";

    @Autowired
    private MockMvc mockMvc;

    private ApiTestSupport.Student student;

    @BeforeEach
    void createStudent() throws Exception {
        student = newStudent(mockMvc);
    }

    @Test
    @DisplayName("三条内置示例带首尾空白（空格、制表符、全角空格）仍命中 200")
    void paddedSelectionsAndSentencesAreNormalized() throws Exception {
        String sentence = "\t " + SENTENCE_DECLINE_NOUN + IDEOGRAPHIC_SPACE + " ";
        String studentsSentence = "  " + SENTENCE_DECLINE_VERB + "\t";

        JsonNode declineNoun = body(explain(mockMvc, student.token(), "   decline ", sentence)
                .andExpect(status().isOk())
                .andReturn());
        assertThat(declineNoun.get("meaning").asText()).isEqualTo("下降");
        assertThat(declineNoun.get("mode").asText()).isEqualTo("demo");

        JsonNode declineVerb = body(explain(mockMvc, student.token(), "\tdecline\n", studentsSentence)
                .andExpect(status().isOk())
                .andReturn());
        assertThat(declineVerb.get("meaning").asText()).isEqualTo("婉拒");

        JsonNode lowerDemand = body(explain(mockMvc, student.token(), "  lower demand  ", sentence)
                .andExpect(status().isOk())
                .andReturn());
        assertThat(lowerDemand.get("meaning").asText()).isEqualTo("较低的需求");
    }

    @Test
    @DisplayName("去空白后仍按原句做连续子串校验：句内双空格不会被打平")
    void internalWhitespaceIsPreserved() throws Exception {
        // 双空格落在选词区间内部（lower 与 demand 之间）
        String doubledSpaceSentence = "The report attributed the decline to lower  demand.";

        // 句内多了一个空格，选词 lower demand 不再是连续原文，必须 400 而不是被"修正"成 200。
        MvcResult result = explain(mockMvc, student.token(), "lower demand", doubledSpaceSentence).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(result).get("code").asText()).isEqualTo("INVALID_INPUT");
    }

    @Test
    @DisplayName("全空白选词去空白后为空：400 INVALID_INPUT")
    void blankSelectionAfterTrimmingReturns400() throws Exception {
        MvcResult result = explain(mockMvc, student.token(), "  \t" + IDEOGRAPHIC_SPACE, SENTENCE_DECLINE_VERB)
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(result).get("code").asText()).isEqualTo("INVALID_INPUT");
        assertThat(body(result).get("message").asText()).contains("选词");
    }

    @Test
    @DisplayName("长度按去空白后的文本计算：200 字通过、201 字被拒")
    void lengthIsMeasuredAfterTrimming() throws Exception {
        String longSentence = "a".repeat(250);

        JsonNode ok = body(explain(mockMvc, student.token(), "  " + "a".repeat(200) + "  ", longSentence)
                .andExpect(status().isUnprocessableEntity())
                .andReturn());
        // demo 未收录该组合，但已经通过长度与非空校验 -> 422 而不是 400
        assertThat(ok.get("code").asText()).isEqualTo("DEMO_UNSUPPORTED");

        MvcResult tooLong = explain(mockMvc, student.token(), "a".repeat(201), longSentence).andReturn();
        assertThat(tooLong.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(tooLong).get("message").asText()).contains("选词过长");
    }

    @Test
    @DisplayName("selection 传数字或布尔值：400 INVALID_INPUT")
    void nonStringSelectionIsRejected() throws Exception {
        for (Object badValue : List.of(123, 12.5, true)) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("selection", badValue);
            payload.put("sentence", SENTENCE_DECLINE_VERB);

            MvcResult result = explainRaw(mockMvc, student.token(), json(payload)).andReturn();

            assertThat(result.getResponse().getStatus())
                    .as("selection 必须是 JSON 字符串，实际提交：%s", badValue)
                    .isEqualTo(400);
            assertThat(body(result).get("code").asText()).isEqualTo("INVALID_INPUT");
        }
    }

    @Test
    @DisplayName("sentence 传数字：400 INVALID_INPUT")
    void nonStringSentenceIsRejected() throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("selection", "decline");
        payload.put("sentence", 42);

        MvcResult result = explainRaw(mockMvc, student.token(), json(payload)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(result).get("code").asText()).isEqualTo("INVALID_INPUT");
    }

    @Test
    @DisplayName("纯数字字符串仍是合法的 selection 文本，按内容校验而非类型拒绝")
    void numericStringSelectionIsStillTextual() throws Exception {
        MvcResult result = explain(mockMvc, student.token(), "12345", "The code 12345 is printed here.")
                .andReturn();

        // 类型合法、也在原句中，只是 demo 未收录 -> 422
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(result).get("code").asText()).isEqualTo("DEMO_UNSUPPORTED");
    }
}
