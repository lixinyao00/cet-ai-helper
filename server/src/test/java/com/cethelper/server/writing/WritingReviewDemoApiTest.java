package com.cethelper.server.writing;

import static com.cethelper.server.support.ApiTestSupport.writingReview;
import static com.cethelper.server.support.ApiTestSupport.writingReviewRaw;
import static com.cethelper.server.writing.DemoWritingCatalog.DRAFT;
import static com.cethelper.server.writing.DemoWritingCatalog.PROMPT;
import static com.cethelper.server.writing.DemoWritingCatalog.REVISION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cethelper.server.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class WritingReviewDemoApiTest {

    @Autowired
    private MockMvc mvc;

    private String token;

    @BeforeEach
    void signIn() throws Exception {
        token = ApiTestSupport.newStudent(mvc).token();
    }

    @Test
    void draftAndRevisionAreReviewedIndependently() throws Exception {
        JsonNode draft = ApiTestSupport.body(writingReview(mvc, token, "  " + PROMPT, DRAFT + "\n")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("demo"))
                .andExpect(jsonPath("$.prompt").value(PROMPT))
                .andExpect(jsonPath("$.essay").value(DRAFT))
                .andReturn());

        assertThat(draft.get("issues")).hasSize(2);
        for (JsonNode issue : draft.get("issues")) {
            assertThat(issue.get("type").asText()).isEqualTo("GRAMMAR");
            assertThat(DRAFT).contains(issue.get("quote").asText());
            assertThat(REVISION).doesNotContain(issue.get("quote").asText());
            assertThat(issue.get("detail").asText()).isNotBlank();
            assertThat(issue.get("suggestion").asText()).isNotBlank();
        }
        JsonNode exercise = draft.get("exercise");
        assertThat(exercise.get("issueIndex").intValue()).isZero();
        assertThat(draft.get("issues").get(0).get("quote").asText()).isEqualTo("Many students prefers");
        assertThat(draft.get("issues").get(1).get("quote").asText())
                .isEqualTo("Last semester, our class organize a weekend cleanup");
        assertThat(exercise.get("instruction").asText()).contains("与主语一致", "写下完整句子");
        assertThat(exercise.get("item").asText())
                .contains("The volunteers", "(feeds / feed)").doesNotContain("Many students");

        writingReview(mvc, token, PROMPT, REVISION)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.essay").value(REVISION))
                .andExpect(jsonPath("$.overallFeedback").value(not(draft.get("overallFeedback").asText())))
                .andExpect(jsonPath("$.issues").isEmpty())
                .andExpect(jsonPath("$.exercise").doesNotHaveJsonPath());
    }

    @Test
    void scoresFollowEssayQualityAndBandIsExplained() throws Exception {
        JsonNode draft = ApiTestSupport.body(writingReview(mvc, token, PROMPT, DRAFT)
                .andExpect(status().isOk())
                .andReturn());
        JsonNode revision = ApiTestSupport.body(writingReview(mvc, token, PROMPT, REVISION)
                .andExpect(status().isOk())
                .andReturn());

        assertThat(draft.get("estimatedScore").asInt()).isEqualTo(10);
        assertThat(revision.get("estimatedScore").asInt()).isEqualTo(14);
        assertThat(revision.get("estimatedScore").asInt()).isGreaterThan(draft.get("estimatedScore").asInt());
        assertThat(draft.get("scoreMax").asInt()).isEqualTo(15);
        assertThat(revision.get("scoreMax").asInt()).isEqualTo(15);
        assertThat(draft.get("scoreBand").asText()).isNotBlank().contains("档");
        assertThat(revision.get("scoreBand").asText()).isNotBlank().contains("档");
    }

    @Test
    void unsupportedPromptOrEssayReturnsDemoScope() throws Exception {
        writingReview(mvc, token, "Write about time management.", DRAFT)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DEMO_UNSUPPORTED"));
        writingReview(mvc, token, PROMPT, DRAFT.replace("prefers", "prefer"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DEMO_UNSUPPORTED"))
                .andExpect(jsonPath("$.message").value(containsString("内置草稿和修订稿")));
    }

    @Test
    void anonymousAndRevokedStudentsCannotReview() throws Exception {
        writingReview(mvc, null, PROMPT, DRAFT)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        ApiTestSupport.logout(mvc, token).andExpect(status().isNoContent());
        writingReview(mvc, token, PROMPT, DRAFT)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void bothFieldsMustBeBoundedNonblankStrings() throws Exception {
        for (String badBody : new String[]{"", "{", "{}", "{\"prompt\":5,\"essay\":\"Essay\"}",
                "{\"prompt\":\"Topic\",\"essay\":false}", "{\"prompt\":\"Topic\",\"essay\":\" \"}"}) {
            writingReviewRaw(mvc, token, badBody)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
        writingReview(mvc, token, "a".repeat(2001), DRAFT)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        writingReview(mvc, token, PROMPT, "a".repeat(6001))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }
}
