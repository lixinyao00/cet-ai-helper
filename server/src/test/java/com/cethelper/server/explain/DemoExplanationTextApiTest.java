package com.cethelper.server.explain;

import static com.cethelper.server.support.ApiTestSupport.SENTENCE_DECLINE_NOUN;
import static com.cethelper.server.support.ApiTestSupport.SENTENCE_DECLINE_VERB;
import static com.cethelper.server.support.ApiTestSupport.body;
import static com.cethelper.server.support.ApiTestSupport.explain;
import static com.cethelper.server.support.ApiTestSupport.newStudent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

/**
 * 内置示例释义文案的回归测试。
 *
 * <p>报告原句里的两条解释是学生直接看到的内容，措辞被验收过，逐字锁定，
 * 避免后续改动悄悄回退成旧文案；`meaning` 与 `mode` 同时保持不变。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("内置示例的解释文案")
class DemoExplanationTextApiTest {

    private static final String REPORT_DECLINE_EXPLANATION = "这里作名词，指报告提到的下降；报告将这一下降归因于需求走低";
    private static final String LOWER_DEMAND_EXPLANATION = "需求较低；在这句话里，它是报告给出的下降原因";
    private static final String STUDENTS_DECLINE_EXPLANATION = "这里作动词，指学生谢绝邀请。";

    @Autowired
    private MockMvc mockMvc;

    private ApiTestSupport.Student student;

    @BeforeEach
    void createStudent() throws Exception {
        student = newStudent(mockMvc);
    }

    @Test
    @DisplayName("报告句中的 decline：meaning=下降，解释逐字匹配")
    void reportSentenceDeclineExplanation() throws Exception {
        JsonNode json = body(explain(mockMvc, student.token(), "decline", SENTENCE_DECLINE_NOUN)
                .andExpect(status().isOk())
                .andReturn());

        assertThat(json.get("meaning").asText()).isEqualTo("下降");
        assertThat(json.get("explanation").asText())
                .as("报告句 decline 的解释必须逐字一致")
                .isEqualTo(REPORT_DECLINE_EXPLANATION);
        assertThat(json.get("mode").asText()).isEqualTo("demo");
    }

    @Test
    @DisplayName("报告句中的 lower demand：meaning=较低的需求，解释逐字匹配")
    void reportSentenceLowerDemandExplanation() throws Exception {
        JsonNode json = body(explain(mockMvc, student.token(), "lower demand", SENTENCE_DECLINE_NOUN)
                .andExpect(status().isOk())
                .andReturn());

        assertThat(json.get("meaning").asText()).isEqualTo("较低的需求");
        assertThat(json.get("explanation").asText())
                .as("lower demand 的解释必须逐字一致")
                .isEqualTo(LOWER_DEMAND_EXPLANATION);
        assertThat(json.get("mode").asText()).isEqualTo("demo");
    }

    @Test
    @DisplayName("学生句中的 decline 仍为婉拒，解释未被本轮改动影响")
    void studentsSentenceDeclineUnchanged() throws Exception {
        JsonNode json = body(explain(mockMvc, student.token(), "decline", SENTENCE_DECLINE_VERB)
                .andExpect(status().isOk())
                .andReturn());

        assertThat(json.get("meaning").asText()).isEqualTo("婉拒");
        assertThat(json.get("explanation").asText()).isEqualTo(STUDENTS_DECLINE_EXPLANATION);
    }

    @Test
    @DisplayName("同一句的 decline 与 lower demand 解释互不相同，不会串用")
    void twoEntriesOnSameSentenceHaveDistinctExplanations() throws Exception {
        String declineExplanation = body(explain(mockMvc, student.token(), "decline", SENTENCE_DECLINE_NOUN)
                .andExpect(status().isOk())
                .andReturn()).get("explanation").asText();
        String lowerDemandExplanation = body(explain(mockMvc, student.token(), "lower demand", SENTENCE_DECLINE_NOUN)
                .andExpect(status().isOk())
                .andReturn()).get("explanation").asText();

        assertThat(declineExplanation).isNotEqualTo(lowerDemandExplanation);
        assertThat(declineExplanation).contains("下降");
        assertThat(lowerDemandExplanation).contains("下降原因");
    }
}
