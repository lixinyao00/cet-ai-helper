package com.cethelper.server.writing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cethelper.server.support.ApiTestSupport;
import com.cethelper.server.support.FakeOpenAiServer;
import com.cethelper.server.support.FakeOpenAiServer.Mode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(properties = "cet.explanation.mode=live")
@ActiveProfiles("test")
@AutoConfigureMockMvc
class WritingPracticeLiveApiTest {

    private static final String EXERCISE = "/writing-exercise-attempts";
    private static final String NEW_RETEST = "/writing-retests";
    private static final String RETEST_ATTEMPT = "/writing-retest-attempts";
    private static final String KEY = "local-practice-test-key";
    private static final String CONTEXT = """
            {
              "issue": {
                "type": "GRAMMAR",
                "quote": "Last Friday, our class visit a museum",
                "detail": "Last Friday 是过去的时间，visit 应用过去式。",
                "suggestion": "把 visit 改为 visited。"
              },
              "exercise": {
                "issueIndex": 1,
                "instruction": "用括号内动词的正确形式填空。",
                "item": "Last night, the nurse ___ (arrive) early."
              }
            }
            """;
    private static final String TASK = """
            {
              "instruction": "用一般过去时写一句你昨天在图书馆做的事。",
              "item": "Imagine you were at the library yesterday. Write one sentence about what you did."
            }
            """;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final FakeOpenAiServer UPSTREAM = startUpstream();

    @Autowired
    private MockMvc mvc;

    private String token;
    private JsonNode context;
    private JsonNode task;

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
        registry.add("cet.explanation.model", () -> "fake-practice-model");
        registry.add("cet.explanation.api-key", () -> KEY);
        registry.add("cet.explanation.read-timeout", () -> "PT0.5S");
    }

    @AfterAll
    static void stopUpstream() {
        UPSTREAM.close();
    }

    @BeforeEach
    void signIn() throws Exception {
        token = ApiTestSupport.newStudent(mvc).token();
        context = MAPPER.readTree(CONTEXT);
        task = MAPPER.readTree(TASK);
        UPSTREAM.mode(Mode.SUCCESS).slowMillis(0).content(null);
    }

    @Test
    void sendsTheOriginalIssueAndExerciseWithTheAnswer() throws Exception {
        UPSTREAM.content(assessment("CORRECT").toString());
        JsonNode graded = body(submit(EXERCISE, attempt("  arrived\n", false)));
        assertThat(graded.path("mode").asText()).isEqualTo("live");
        assertThat(graded.path("verdict").asText()).isEqualTo("CORRECT");
        assertThat(graded.path("answer").asText()).isEqualTo("arrived");
        assertThat(graded.path("issue")).isEqualTo(context.path("issue"));
        JsonNode modelInput = upstreamInput();
        assertThat(modelInput.path("context").path("issue")).isEqualTo(context.path("issue"));
        assertThat(modelInput.path("context").path("exercise").path("instruction").asText())
                .isEqualTo(context.path("exercise").path("instruction").asText());
        assertThat(modelInput.path("context").path("exercise").path("item").asText())
                .isEqualTo(context.path("exercise").path("item").asText());
        assertThat(modelInput.path("answer").asText()).isEqualTo("arrived");
        assertThat(UPSTREAM.lastAuthorization()).isEqualTo("Bearer " + KEY);
        assertThat(UPSTREAM.lastRequestBody()).doesNotContain(KEY);

        UPSTREAM.content(assessment("NEEDS_REVISION").toString());
        submit(EXERCISE, attempt("arrive", false))
                .andExpect(status().isOk()).andExpect(jsonPath("$.verdict").value("NEEDS_REVISION"));
    }

    @Test
    void generatesANewTaskFromTheSpecificIssueInLiveMode() throws Exception {
        UPSTREAM.content(TASK);
        JsonNode retest = body(submit(NEW_RETEST, context));
        assertThat(retest.path("mode").asText()).isEqualTo("live");
        assertThat(retest.path("issue")).isEqualTo(context.path("issue"));
        assertThat(retest.path("task")).isEqualTo(task);
        assertThat(upstreamInput().path("issue").path("quote").asText()).contains("visit a museum");
        assertThat(upstreamInput().path("exercise").path("item").asText()).contains("nurse");
    }

    @ParameterizedTest
    @CsvSource({
            "TARGET_USED, I borrowed  a book yesterday., I borrowed  a book",
            "SAME_ERROR, I borrow a book yesterday., I borrow a book",
            "INSUFFICIENT_EVIDENCE, I like the library.,"
    })
    void returnsAllRetestVerdictsWithEvidenceOnlyWhenRequired(String verdict, String answer, String quote)
            throws Exception {
        UPSTREAM.content(assessment(verdict).put("quote", quote).toString());
        JsonNode graded = body(submit(RETEST_ATTEMPT, attempt(answer, true)));
        assertThat(graded.path("verdict").asText()).isEqualTo(verdict);
        assertThat(graded.path("answer").asText()).isEqualTo(answer);
        assertThat(graded.path("issue")).isEqualTo(context.path("issue"));
        assertThat(graded.path("quote").textValue()).isEqualTo(quote);
        assertThat(upstreamInput().path("task")).isEqualTo(task);
        assertThat(upstreamInput().path("context").path("issue")).isEqualTo(context.path("issue"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Last Friday, our class visit a museum", "at the library yesterday",
            "I borrowed a book", "i borrowed  a book", ""})
    void rejectsAlteredQuotesAndQuotesFromTheIssueOrTask(String quote) throws Exception {
        UPSTREAM.content(assessment("TARGET_USED").put("quote", quote).toString());
        expectModelError(submit(RETEST_ATTEMPT, attempt("I borrowed  a book yesterday.", true)));
    }

    @Test
    void rejectsInconsistentEvidenceAndIncorrectFieldTypes() throws Exception {
        ObjectNode request = attempt("I borrow a book yesterday.", true);
        for (JsonNode bad : new JsonNode[]{
                assessment("SAME_ERROR"),
                assessment("SAME_ERROR").put("quote", false),
                assessment("INSUFFICIENT_EVIDENCE").put("quote", "I borrow"),
                assessment("PASSED").put("quote", "I borrow"),
                assessment("SAME_ERROR").put("quote", "I borrow").putNull("feedback"),
                assessment("SAME_ERROR").put("quote", "I borrow").put("nextStep", "English only")}) {
            UPSTREAM.content(bad.toString());
            expectModelError(submit(RETEST_ATTEMPT, request));
        }
        UPSTREAM.content(assessment("CORRECT").put("verdict", 0).toString());
        expectModelError(submit(EXERCISE, attempt("arrived", false)));
        UPSTREAM.content(assessment("CORRECT").put("nextStep", "").toString());
        expectModelError(submit(EXERCISE, attempt("arrived", false)));
    }

    @Test
    void rejectsMalformedOrRepeatedGeneratedTasks() throws Exception {
        for (String bad : new String[]{
                "[]", TASK + "{}", "{\"instruction\":\"写一句。\",\"item\":false}",
                "{\"instruction\":\"写一句。\"}",
                MAPPER.createObjectNode().put("instruction", "换一道题。")
                        .put("item", "Example: LAST NIGHT,\n the nurse ___ (arrive) early.").toString()}) {
            UPSTREAM.content(bad);
            expectModelError(submit(NEW_RETEST, context));
        }
    }

    @Test
    void mapsUpstreamErrorsAndTimeoutsWithoutLeakingUpstreamBody() throws Exception {
        UPSTREAM.mode(Mode.ERROR);
        expectModelError(submit(NEW_RETEST, context))
                .andExpect(content().string(not(containsString(FakeOpenAiServer.LEAK_MARKER))));
        UPSTREAM.mode(Mode.SLOW).slowMillis(1500);
        submit(EXERCISE, attempt("arrived", false))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("MODEL_TIMEOUT"));
    }

    @Test
    void rejectsRequestsBeforeCallingTheModel() throws Exception {
        int before = UPSTREAM.requestCount();
        submit(EXERCISE, attempt("", false)).andExpect(status().isBadRequest());
        submit(RETEST_ATTEMPT, attempt("I read a book yesterday.", false)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1" + NEW_RETEST).contentType(MediaType.APPLICATION_JSON).content(CONTEXT))
                .andExpect(status().isUnauthorized());
        assertThat(UPSTREAM.requestCount()).isEqualTo(before);
    }

    private ObjectNode attempt(String answer, boolean withTask) {
        ObjectNode request = MAPPER.createObjectNode().put("answer", answer);
        request.set("context", context);
        if (withTask) {
            request.set("task", task);
        }
        return request;
    }

    private JsonNode upstreamInput() throws Exception {
        JsonNode payload = MAPPER.readTree(UPSTREAM.lastRequestBody());
        assertThat(payload.path("model").asText()).isEqualTo("fake-practice-model");
        return MAPPER.readTree(payload.path("messages").path(1).path("content").textValue());
    }

    private ResultActions submit(String path, JsonNode request) throws Exception {
        return mvc.perform(post("/api/v1" + path).contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + token).content(request.toString()));
    }

    private static JsonNode body(ResultActions result) throws Exception {
        return ApiTestSupport.body(result.andExpect(status().isOk()).andReturn());
    }

    private static ObjectNode assessment(String verdict) {
        return MAPPER.createObjectNode().put("verdict", verdict)
                .put("feedback", "已根据题目检查动词形式。").put("nextStep", "再检查动词是否符合时间。");
    }

    private static ResultActions expectModelError(ResultActions result) throws Exception {
        return result.andExpect(status().isBadGateway()).andExpect(content().json("""
                {"code":"MODEL_ERROR","message":"在线写作练习服务返回异常，请稍后重试。"}
                """, JsonCompareMode.STRICT));
    }
}
