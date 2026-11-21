package com.cethelper.server.explain;

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

/**
 * demo 模式内置四六级词表兜底的契约测试。
 *
 * <p>覆盖：非精调句也能查到词表释义（contextual=false）、常见词尾可还原到原形、
 * 词表外的选词才返回 422。所有断言都走 HTTP 契约，不直接调用 catalog。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("内置四六级词表兜底查词")
class DemoExplanationWordFallbackApiTest {

    private static final List<String> FIELDS = List.of(
            "meaning", "explanation", "mode", "phonetic", "partOfSpeech", "lemma", "cetLevel", "contextual");

    @Autowired
    private MockMvc mockMvc;

    private ApiTestSupport.Student student;

    @BeforeEach
    void createStudent() throws Exception {
        student = ApiTestSupport.newStudent(mockMvc);
    }

    @Test
    @DisplayName("非精调句中的常用词：200、contextual=false，并返回音标/词性/原形")
    void knownWordInUnknownSentenceFallsBackToDictionary() throws Exception {
        JsonNode json = explain("decline", "When a physical bookstore closes, many readers decline to pay full price.");

        assertThat(fieldNames(json)).containsExactlyInAnyOrderElementsOf(FIELDS);
        assertThat(json.get("mode").asText()).isEqualTo("demo");
        assertThat(json.get("contextual").asBoolean()).as("词表兜底不是语境释义").isFalse();
        assertThat(json.get("meaning").asText()).isNotBlank();
        assertThat(json.get("explanation").asText()).isNotBlank();
        assertThat(json.get("phonetic").asText()).startsWith("/");
        assertThat(json.get("partOfSpeech").asText()).isNotBlank();
        assertThat(json.get("lemma").asText()).isEqualTo("decline");
        assertThat(json.get("cetLevel").asText()).isEqualTo("CET-4");
    }

    @Test
    @DisplayName("复数/过去式/进行时能还原到原形：students -> student、closes -> close")
    void inflectedFormsResolveToLemma() throws Exception {
        assertThat(explain("students", "Many students read paperbacks in the neighborhood.")
                .get("lemma").asText()).isEqualTo("student");
        assertThat(explain("closes", "The bookstore closes early on Sundays.")
                .get("lemma").asText()).isEqualTo("close");
    }

    @Test
    @DisplayName("内置示例句里的普通单词也能查到（不再是 422）")
    void wordsFromSampleSentencesAreCovered() throws Exception {
        for (String word : List.of("report", "the", "invitation", "reading")) {
            String sentence = "The report mentioned " + word + " once.";
            JsonNode json = explain(word, sentence);
            assertThat(json.get("contextual").asBoolean())
                    .as("选词 %s 应命中内置词表", word)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("词表外的生词仍返回 422 DEMO_UNSUPPORTED")
    void unknownWordStillReturns422() throws Exception {
        MvcResult result = ApiTestSupport
                .explain(mockMvc, student.token(), "quizzacious", "A quizzacious remark ended the talk.")
                .andExpect(status().isUnprocessableEntity())
                .andReturn();

        assertThat(ApiTestSupport.body(result).get("code").asText()).isEqualTo("DEMO_UNSUPPORTED");
    }

    private JsonNode explain(String selection, String sentence) throws Exception {
        return ApiTestSupport.body(ApiTestSupport.explain(mockMvc, student.token(), selection, sentence)
                .andExpect(status().isOk())
                .andReturn());
    }

    private static List<String> fieldNames(JsonNode json) {
        List<String> names = new ArrayList<>();
        json.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
