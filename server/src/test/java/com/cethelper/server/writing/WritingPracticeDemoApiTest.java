package com.cethelper.server.writing;

import static com.cethelper.server.support.ApiTestSupport.writingReview;
import static com.cethelper.server.writing.DemoWritingCatalog.DRAFT;
import static com.cethelper.server.writing.DemoWritingCatalog.PROMPT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cethelper.server.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class WritingPracticeDemoApiTest {

    private static final String EXERCISE = "/writing-exercise-attempts";
    private static final String NEW_RETEST = "/writing-retests";
    private static final String RETEST_ATTEMPT = "/writing-retest-attempts";
    private static final String FEED = "The volunteers at the animal shelter feed the dogs every morning.";
    private static final String TARGET_USED = "The student hosts check the microphones every Friday.";
    private static final String SAME_ERROR = "The student hosts checks the microphones every Friday.";
    private static final String AVOIDED_TARGET = "The station opens at noon every Friday.";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper mapper;

    private String token;
    private ObjectNode context;

    @BeforeEach
    void reviewDraft() throws Exception {
        token = ApiTestSupport.newStudent(mvc).token();
        JsonNode review = ApiTestSupport.body(writingReview(mvc, token, PROMPT, DRAFT)
                .andExpect(status().isOk()).andReturn());
        JsonNode exercise = review.path("exercise");
        context = mapper.createObjectNode();
        context.set("issue", review.path("issues").get(exercise.path("issueIndex").intValue()));
        context.set("exercise", exercise);
    }

    @Test
    void followsReviewThroughExerciseAndAllThreeRetestOutcomes() throws Exception {
        JsonNode exercise = response(submit(EXERCISE, attempt(" " + FEED + "\n")));
        assertThat(exercise.path("issue")).isEqualTo(context.path("issue"));
        assertThat(exercise.path("mode").asText()).isEqualTo("demo");
        assertThat(exercise.path("answer").asText()).isEqualTo(FEED);
        assertThat(exercise.path("verdict").asText()).isEqualTo("CORRECT");
        assertThat(exercise.path("feedback").asText()).contains("volunteers", "feed");
        assertThat(exercise.path("nextStep").asText()).contains("复数主语");

        JsonNode retest = response(submit(NEW_RETEST, context));
        assertThat(retest.path("issue")).isEqualTo(context.path("issue"));
        JsonNode task = retest.path("task");
        assertThat(task.path("instruction").asText()).contains("复数主语", "一般现在时", "写一句英文");
        assertThat(task.path("item").asText()).contains("school radio station").doesNotContain("animal shelter");

        assertRetest(task, TARGET_USED, "TARGET_USED", "The student hosts check");
        assertRetest(task, SAME_ERROR, "SAME_ERROR", "The student hosts checks");
        assertRetest(task, AVOIDED_TARGET, "INSUFFICIENT_EVIDENCE", null);
    }

    @Test
    void acceptsAlternateWordingAndExplainsPartialOrIncorrectAnswers() throws Exception {
        submit(EXERCISE, attempt("Every morning, the volunteers at the animal shelter feed the dogs."))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("CORRECT"));
        submit(EXERCISE, attempt("The volunteers at the animal shelter feeds the dogs every morning."))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("NEEDS_REVISION"))
                .andExpect(jsonPath("$.feedback").value(containsString("句子完整")))
                .andExpect(jsonPath("$.nextStep").value(containsString("把 feeds 改成 feed")));
        submit(EXERCISE, attempt("feed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("NEEDS_REVISION"))
                .andExpect(jsonPath("$.feedback").value(containsString("feed 选对了")))
                .andExpect(jsonPath("$.feedback").value(containsString("完整句子")));
        JsonNode task = response(submit(NEW_RETEST, context)).path("task");
        assertRetest(task, "Every Friday, the student hosts test their microphones.",
                "TARGET_USED", "the student hosts test");
    }

    @Test
    void validInputsOutsideDemoCatalogAreExplicitlyUnsupported() throws Exception {
        submit(EXERCISE, attempt("We feed the animals each day."))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DEMO_UNSUPPORTED"))
                .andExpect(jsonPath("$.message").value(containsString("动物收容所练习答案")));
        ObjectNode wrongContext = context.deepCopy();
        ((ObjectNode) wrongContext.path("issue")).put("quote", "An unrelated quote");
        submit(NEW_RETEST, wrongContext)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DEMO_UNSUPPORTED"))
                .andExpect(jsonPath("$.message").value(containsString("主谓一致问题")));
        JsonNode task = response(submit(NEW_RETEST, context)).path("task");
        submit(RETEST_ATTEMPT, retestAttempt(task, "The hosts plan their shows each week."))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DEMO_UNSUPPORTED"));
        ObjectNode wrongTask = (ObjectNode) task.deepCopy();
        wrongTask.put("item", "Write about the school chess club.");
        submit(RETEST_ATTEMPT, retestAttempt(wrongTask, TARGET_USED))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DEMO_UNSUPPORTED"));
    }

    @Test
    void rejectsMissingBlankWrongTypeOversizeAndReusedTask() throws Exception {
        assertInvalid(NEW_RETEST, mapper.createObjectNode());
        ObjectNode invalid = context.deepCopy();
        ((ObjectNode) invalid.path("issue")).put("type", 0);
        assertInvalid(NEW_RETEST, invalid);
        ((ObjectNode) invalid.path("issue")).put("type", "UNKNOWN");
        assertInvalid(NEW_RETEST, invalid);
        ((ObjectNode) invalid.path("issue")).put("type", "GRAMMAR").put("quote", "x".repeat(241));
        assertInvalid(NEW_RETEST, invalid);
        assertInvalid(EXERCISE, attempt("\n\t"));
        assertInvalid(EXERCISE, attempt("a".repeat(2001)));
        assertInvalid(EXERCISE, attempt(FEED).put("answer", true));
        assertInvalid(RETEST_ATTEMPT, attempt(TARGET_USED));
        assertInvalid(RETEST_ATTEMPT, retestAttempt(context.path("exercise"), TARGET_USED));
        JsonNode task = response(submit(NEW_RETEST, context)).path("task");
        assertInvalid(RETEST_ATTEMPT, retestAttempt(task, ""));
    }

    @Test
    void allEndpointsRequireStudentAuthentication() throws Exception {
        for (String path : new String[]{EXERCISE, NEW_RETEST, RETEST_ATTEMPT}) {
            mvc.perform(post("/api/v1" + path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        }
        ApiTestSupport.logout(mvc, token).andExpect(status().isNoContent());
        submit(NEW_RETEST, context).andExpect(status().isUnauthorized());
    }

    private ObjectNode attempt(String answer) {
        ObjectNode attempt = mapper.createObjectNode().put("answer", answer);
        attempt.set("context", context);
        return attempt;
    }

    private ObjectNode retestAttempt(JsonNode task, String answer) {
        ObjectNode attempt = attempt(answer);
        attempt.set("task", task);
        return attempt;
    }

    private void assertRetest(JsonNode task, String answer, String verdict, String quote) throws Exception {
        JsonNode result = response(submit(RETEST_ATTEMPT, retestAttempt(task, answer)));
        assertThat(result.path("issue")).isEqualTo(context.path("issue"));
        assertThat(result.path("verdict").asText()).isEqualTo(verdict);
        assertThat(result.path("answer").asText()).isEqualTo(answer);
        assertThat(result.path("feedback").asText()).isNotBlank();
        assertThat(result.path("nextStep").asText()).isNotBlank();
        if (quote == null) {
            assertThat(result.has("quote")).isFalse();
        } else {
            assertThat(result.path("quote").asText()).isEqualTo(quote);
            assertThat(answer).contains(quote);
        }
    }

    private void assertInvalid(String path, JsonNode body) throws Exception {
        submit(path, body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    private ResultActions submit(String path, JsonNode body) throws Exception {
        return mvc.perform(post("/api/v1" + path).contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + token).content(body.toString()));
    }

    private static JsonNode response(ResultActions result) throws Exception {
        return ApiTestSupport.body(result.andExpect(status().isOk()).andReturn());
    }
}
