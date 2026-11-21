package com.cethelper.server.writing;

import static com.cethelper.server.support.ApiTestSupport.writingReview;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cethelper.server.support.ApiTestSupport;
import com.cethelper.server.support.FakeOpenAiServer;
import com.cethelper.server.support.FakeOpenAiServer.Mode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(properties = "cet.explanation.mode=live")
@ActiveProfiles("test")
@AutoConfigureMockMvc
class WritingReviewLiveApiTest {

    private static final String PROMPT = "Should cities invest in bike lanes?";
    private static final String ESSAY = "Safe lanes makes  cycling easier.\n\nLast year, our city add a bike lane.";
    private static final String PAST_TENSE_QUOTE = "Last year, our city add";
    private static final String FAKE_API_KEY = "test-writing-key";
    private static final String VALID_REVIEW = """
            {
              "overallFeedback":"短文给出了骑行的好处，先改清楚主谓关系和时间。",
              "issues":[
                {"type":"GRAMMAR","quote":"lanes makes  cycling","detail":"lanes 是复数，makes 与主语不一致。","suggestion":"把 makes 改为 make。"},
                {"type":"GRAMMAR","quote":"Last year, our city add","detail":"Last year 表示过去，add 的形式没有体现过去时。","suggestion":"把 add 改为 added。"}
              ],
              "exercise":{"issueIndex":1,"instruction":"用括号内动词的正确形式填空。","item":"Last Monday, the baker ___ (open) the shop at six."}
            }
            """;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final FakeOpenAiServer UPSTREAM = startUpstream();

    private static FakeOpenAiServer startUpstream() {
        try {
            return FakeOpenAiServer.start();
        } catch (IOException ex) {
            throw new IllegalStateException("本地模拟上游启动失败", ex);
        }
    }

    @DynamicPropertySource
    static void upstreamProperties(DynamicPropertyRegistry registry) {
        registry.add("cet.explanation.base-url", UPSTREAM::baseUrl);
        registry.add("cet.explanation.model", () -> "fake-writing-model");
        registry.add("cet.explanation.api-key", () -> FAKE_API_KEY);
        registry.add("cet.explanation.read-timeout", () -> "PT0.5S");
    }

    @AfterAll
    static void stopUpstream() {
        UPSTREAM.close();
    }

    @Autowired
    private MockMvc mvc;

    private String token;

    @BeforeEach
    void signInAndResetUpstream() throws Exception {
        token = ApiTestSupport.newStudent(mvc).token();
        UPSTREAM.mode(Mode.SUCCESS).slowMillis(0).content(VALID_REVIEW);
    }

    @Test
    void customSubmissionKeepsExactQuotesAndSecondIssueExercise() throws Exception {
        writingReview(mvc, token, " " + PROMPT + "\n", ESSAY + "\n")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prompt").value(PROMPT))
                .andExpect(jsonPath("$.essay").value(ESSAY))
                .andExpect(jsonPath("$.mode").value("live"))
                .andExpect(jsonPath("$.issues.length()").value(2))
                .andExpect(jsonPath("$.issues[0].quote").value("lanes makes  cycling"))
                .andExpect(jsonPath("$.issues[1].quote").value(PAST_TENSE_QUOTE))
                .andExpect(jsonPath("$.exercise.issueIndex").value(1))
                .andExpect(jsonPath("$.exercise.instruction").value("用括号内动词的正确形式填空。"))
                .andExpect(jsonPath("$.exercise.item").value("Last Monday, the baker ___ (open) the shop at six."));

        JsonNode payload = MAPPER.readTree(UPSTREAM.lastRequestBody());
        assertThat(payload.path("model").textValue()).isEqualTo("fake-writing-model");
        JsonNode input = MAPPER.readTree(payload.path("messages").path(1).path("content").textValue());
        assertThat(input.path("prompt").textValue()).isEqualTo(PROMPT);
        assertThat(input.path("essay").textValue()).isEqualTo(ESSAY);
        assertThat(UPSTREAM.lastAuthorization()).isEqualTo("Bearer " + FAKE_API_KEY);
        assertThat(UPSTREAM.lastRequestBody()).doesNotContain(FAKE_API_KEY);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ",\"exercise\":null"})
    void cleanEssayReturnsEmptyIssuesAndNoExercise(String optionalExercise) throws Exception {
        UPSTREAM.content("{\"overallFeedback\":\"表达清楚，事例切题。\",\"issues\":[]"
                + optionalExercise + "}");
        writingReview(mvc, token, PROMPT, "Bike lanes can make more people feel safe enough to cycle.")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issues").isEmpty())
                .andExpect(jsonPath("$.exercise").doesNotHaveJsonPath());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Should cities", "lanes makes cycling", "Lanes makes  cycling",
            " Last year, our city add", ""})
    void rejectsQuoteThatIsNotVerbatimEssayText(String quote) throws Exception {
        assertRejected(payload -> ((ObjectNode) payload.path("issues").get(0)).put("quote", quote));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"-1", "2", "\"1\"", "1.0", "null"})
    void rejectsMissingExerciseOrInvalidIssueIndex(String index) throws Exception {
        JsonNode indexNode = index == null ? null : MAPPER.readTree(index);
        assertRejected(payload -> {
            if (indexNode == null) {
                payload.remove("exercise");
            } else {
                ((ObjectNode) payload.path("exercise")).set("issueIndex", indexNode);
            }
        });
    }

    @Test
    void rejectsInvalidRequiredFieldsAndAnExerciseWithNoIssues() throws Exception {
        assertRejected(payload -> payload.remove("overallFeedback"));
        assertRejected(payload -> payload.put("overallFeedback", "English only"));
        assertRejected(payload -> payload.putNull("issues"));
        assertRejected(payload -> ((ObjectNode) payload.path("issues").get(0)).put("type", "UNKNOWN"));
        assertRejected(payload -> ((ObjectNode) payload.path("issues").get(0)).putNull("detail"));
        assertRejected(payload -> ((ObjectNode) payload.path("issues").get(0)).put("suggestion", false));
        assertRejected(payload -> ((ArrayNode) payload.path("issues")).removeAll());
        assertRejected(payload -> ((ObjectNode) payload.path("exercise")).put("instruction", ""));
        assertRejected(payload -> ((ObjectNode) payload.path("exercise")).remove("item"));
        assertRejected(payload -> {
            ArrayNode issues = (ArrayNode) payload.path("issues");
            issues.add(issues.get(0)).add(issues.get(1));
        });
    }

    @Test
    void malformedContentAndProviderErrorUseSanitized502() throws Exception {
        for (String malformed : new String[]{"```json\n```", "[]", VALID_REVIEW + "{}"}) {
            UPSTREAM.content(malformed);
            expectModelError(review());
        }
        UPSTREAM.mode(Mode.ERROR);
        expectModelError(review()).andExpect(content().string(not(containsString(FakeOpenAiServer.LEAK_MARKER))));
    }

    @Test
    void slowProviderReturns504() throws Exception {
        UPSTREAM.mode(Mode.SLOW).slowMillis(1500);
        review()
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("MODEL_TIMEOUT"));
    }

    @Test
    void invalidAndAnonymousRequestsNeverReachProvider() throws Exception {
        int before = UPSTREAM.requestCount();
        writingReview(mvc, null, PROMPT, ESSAY).andExpect(status().isUnauthorized());
        writingReview(mvc, token, "", ESSAY).andExpect(status().isBadRequest());
        assertThat(UPSTREAM.requestCount()).isEqualTo(before);
    }

    private void assertRejected(Consumer<ObjectNode> mutation) throws Exception {
        ObjectNode payload = (ObjectNode) MAPPER.readTree(VALID_REVIEW);
        mutation.accept(payload);
        UPSTREAM.content(payload.toString());
        expectModelError(review());
    }

    private ResultActions review() throws Exception {
        return writingReview(mvc, token, PROMPT, ESSAY);
    }

    private static ResultActions expectModelError(ResultActions result) throws Exception {
        return result.andExpect(status().isBadGateway())
                .andExpect(content().json("""
                        {"code":"MODEL_ERROR","message":"在线写作批改服务返回异常，请稍后重试。"}
                        """, JsonCompareMode.STRICT));
    }
}
