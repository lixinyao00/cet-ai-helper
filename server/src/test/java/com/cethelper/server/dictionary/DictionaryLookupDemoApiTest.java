package com.cethelper.server.dictionary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cethelper.server.explain.DemoExplanationCatalog;
import com.cethelper.server.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** demo 模式查词契约测试：原形/变形词还原、双语例句与未收录 422。 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("POST /api/v1/dictionary/lookup（demo）")
class DictionaryLookupDemoApiTest {

    private static final List<String> FIELDS = List.of(
            "word", "lemma", "phonetic", "partOfSpeech", "cetLevel", "meaning",
            "explanation", "exampleSentence", "exampleTranslation", "mode");

    @Autowired
    private MockMvc mockMvc;

    private ApiTestSupport.Student student;

    @BeforeEach
    void createStudent() throws Exception {
        student = ApiTestSupport.newStudent(mockMvc);
    }

    @Test
    @DisplayName("原形命中：返回完整字段、双语例句与 mode=demo")
    void lookupLemmaReturnsFullEntry() throws Exception {
        JsonNode json = lookup("abandon");

        assertThat(fieldNames(json)).containsExactlyInAnyOrderElementsOf(FIELDS);
        assertThat(json.get("word").asText()).isEqualTo("abandon");
        assertThat(json.get("lemma").asText()).isEqualTo("abandon");
        assertThat(json.get("phonetic").asText()).startsWith("/");
        assertThat(json.get("partOfSpeech").asText()).isNotBlank();
        assertThat(json.get("cetLevel").asText()).isEqualTo("CET-6");
        assertThat(json.get("meaning").asText()).isNotBlank();
        assertThat(json.get("explanation").asText()).isNotBlank();
        assertThat(json.get("exampleSentence").asText()).isNotBlank()
                .containsIgnoringCase("abandon");
        assertThat(json.get("exampleTranslation").asText()).isNotBlank();
        assertThat(json.get("mode").asText()).isEqualTo("demo");
    }

    @Test
    @DisplayName("变形词还原：abandoned → abandon、studies → study，word 保留用户输入")
    void inflectedFormResolvesToLemma() throws Exception {
        JsonNode abandoned = lookup("abandoned");
        assertThat(abandoned.get("word").asText()).isEqualTo("abandoned");
        assertThat(abandoned.get("lemma").asText()).isEqualTo("abandon");
        assertThat(abandoned.get("exampleSentence").asText()).isNotBlank();

        JsonNode studies = lookup("studies");
        assertThat(studies.get("word").asText()).isEqualTo("studies");
        assertThat(studies.get("lemma").asText()).isEqualTo("study");

        JsonNode mixedCase = lookup("Abandoning");
        assertThat(mixedCase.get("word").asText()).isEqualTo("abandoning");
        assertThat(mixedCase.get("lemma").asText()).isEqualTo("abandon");
    }

    @Test
    @DisplayName("内置词表全部词条都带非空双语例句，且英文例句包含词形")
    void allCatalogEntriesHaveExamples() {
        DemoExplanationCatalog catalog = new DemoExplanationCatalog();
        var seen = new java.util.HashSet<String>();
        for (DemoExplanationCatalog.WordEntry entry : catalog.words().values()) {
            if (!seen.add(entry.lemma())) {
                continue;
            }
            assertThat(entry.exampleSentence())
                    .as("词条 %s 的英文例句不能为空", entry.lemma())
                    .isNotBlank();
            assertThat(entry.exampleTranslation())
                    .as("词条 %s 的中文翻译不能为空", entry.lemma())
                    .isNotBlank();
            assertThat(entry.exampleSentence().toLowerCase(Locale.ROOT))
                    .as("词条 %s 的例句应包含该词", entry.lemma())
                    .contains(entry.lemma().toLowerCase(Locale.ROOT));
        }
        assertThat(seen.size()).as("内置词表至少 120 个 lemma").isGreaterThanOrEqualTo(120);
    }

    @Test
    @DisplayName("未收录的词返回 422 DEMO_UNSUPPORTED，并给出可操作提示")
    void unknownWordReturnsDemoUnsupported() throws Exception {
        MvcResult result = ApiTestSupport.dictionaryLookup(mockMvc, student.token(), "quizzacious")
                .andExpect(status().isUnprocessableEntity())
                .andReturn();
        JsonNode json = ApiTestSupport.body(result);
        assertThat(json.get("code").asText()).isEqualTo("DEMO_UNSUPPORTED");
        assertThat(json.get("message").asText())
                .isEqualTo("演示模式暂未收录该词，请换一个四六级常见词。");
    }

    @Test
    @DisplayName("word 缺失/空白/超长/非法字符/请求体缺失都返回 400 INVALID_INPUT")
    void invalidInputIsRejected() throws Exception {
        assertInvalid(ApiTestSupport.dictionaryLookupRaw(mockMvc, student.token(), "{}")
                .andReturn());
        assertInvalid(ApiTestSupport.dictionaryLookupRaw(mockMvc, student.token(), "{\"word\":\"\"}")
                .andReturn());
        assertInvalid(ApiTestSupport.dictionaryLookupRaw(mockMvc, student.token(),
                "{\"word\":\"" + "a".repeat(41) + "\"}").andReturn());
        assertInvalid(ApiTestSupport.dictionaryLookupRaw(mockMvc, student.token(),
                "{\"word\":\"abc123\"}").andReturn());
        assertInvalid(ApiTestSupport.dictionaryLookupRaw(mockMvc, student.token(), "not-json")
                .andReturn());

        MvcResult missingBody = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .post("/api/v1/dictionary/lookup")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Authorization", "Bearer " + student.token()))
                .andReturn();
        assertInvalid(missingBody);
    }

    @Test
    @DisplayName("未登录返回 401")
    void unauthorized() throws Exception {
        MvcResult result = ApiTestSupport.dictionaryLookup(mockMvc, null, "abandon")
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertThat(ApiTestSupport.body(result).get("code").asText()).isEqualTo("UNAUTHORIZED");
    }

    private JsonNode lookup(String word) throws Exception {
        return ApiTestSupport.body(ApiTestSupport.dictionaryLookup(mockMvc, student.token(), word)
                .andExpect(status().isOk())
                .andReturn());
    }

    private static void assertInvalid(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        JsonNode body = ApiTestSupport.body(result);
        assertThat(body.get("code").asText()).isEqualTo("INVALID_INPUT");
        assertThat(body.get("message").asText()).isNotBlank();
    }

    private static List<String> fieldNames(JsonNode json) {
        List<String> names = new ArrayList<>();
        json.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
