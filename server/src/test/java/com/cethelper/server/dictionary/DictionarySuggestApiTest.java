package com.cethelper.server.dictionary;

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

/** 词典联想接口契约测试：前缀排序、级别过滤、limit 截断、lemma 去重与非法参数。 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("GET /api/v1/dictionary/suggest")
class DictionarySuggestApiTest {

    @Autowired
    private MockMvc mockMvc;

    private ApiTestSupport.Student student;

    @BeforeEach
    void createStudent() throws Exception {
        student = ApiTestSupport.newStudent(mockMvc);
    }

    @Test
    @DisplayName("前缀命中的 lemma 按字母序返回，字段与 Android 契约一致")
    void prefixMatchesAreSortedAndShaped() throws Exception {
        JsonNode json = suggest("ab", null, null);

        assertThat(json.isArray()).isTrue();
        List<String> words = words(json);
        assertThat(words).contains("abandon", "ability", "absorb", "abstract");
        assertThat(words).isSorted();
        assertThat(words.subList(0, 4)).allMatch(word -> word.startsWith("ab"));
        for (JsonNode item : json) {
            assertThat(fieldNames(item)).containsExactlyInAnyOrder(
                    "word", "phonetic", "partOfSpeech", "cetLevel", "meaning");
            assertThat(item.get("word").asText()).isNotBlank();
            assertThat(item.get("phonetic").asText()).startsWith("/");
            assertThat(item.get("partOfSpeech").asText()).isNotBlank();
            assertThat(item.get("cetLevel").asText()).isIn("CET-4", "CET-6");
            assertThat(item.get("meaning").asText()).isNotBlank();
        }
    }

    @Test
    @DisplayName("level 过滤大小写不敏感，并排除其它级别")
    void levelFilterIsCaseInsensitive() throws Exception {
        JsonNode cet6 = suggest("ab", "CET-6", null);
        List<String> words = words(cet6);
        assertThat(words).contains("abandon", "absorb", "abstract");
        assertThat(words).doesNotContain("ability");
        for (JsonNode item : cet6) {
            assertThat(item.get("cetLevel").asText()).isEqualTo("CET-6");
        }

        JsonNode lowerCase = suggest("ab", "cet-6", null);
        assertThat(words(lowerCase)).isEqualTo(words);

        JsonNode cet4 = suggest("ab", "cet-4", null);
        assertThat(words(cet4)).contains("ability");
        for (JsonNode item : cet4) {
            assertThat(item.get("cetLevel").asText()).isEqualTo("CET-4");
        }
    }

    @Test
    @DisplayName("limit 默认 10、超过 20 截断为 20、小于 1 返回 400")
    void limitIsBounded() throws Exception {
        assertThat(suggest("a", null, null).size()).isEqualTo(10);
        assertThat(suggest("a", null, 3).size()).isEqualTo(3);
        assertThat(suggest("a", null, 20).size()).isEqualTo(20);
        assertThat(suggest("a", null, 21).size()).isEqualTo(20);
        assertThat(suggest("a", null, 999).size()).isEqualTo(20);

        assertInvalid(suggestResponse("a", null, "0"));
        assertInvalid(suggestResponse("a", null, "-3"));
        assertInvalid(suggestResponse("a", null, "abc"));
    }

    @Test
    @DisplayName("按 lemma 去重：变体拼写不会产生重复词条")
    void suggestionsAreDeduplicatedByLemma() throws Exception {
        List<String> stud = words(suggest("stud", null, 20));
        assertThat(stud).contains("student", "study");
        assertThat(stud).doesNotHaveDuplicates();

        List<String> mean = words(suggest("mean", null, 20));
        assertThat(mean).contains("meaning");
        assertThat(mean).doesNotHaveDuplicates();

        List<String> read = words(suggest("read", null, 20));
        assertThat(read).contains("read");
        assertThat(read).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("q 缺失/空白/超长/非法字符与非法 level 都返回 400 INVALID_INPUT")
    void invalidInputIsRejected() throws Exception {
        assertInvalid(suggestResponse(null, null, null));
        assertInvalid(suggestResponse("", null, null));
        assertInvalid(suggestResponse("   ", null, null));
        assertInvalid(suggestResponse("a".repeat(41), null, null));
        assertInvalid(suggestResponse("abandon1", null, null));
        assertInvalid(suggestResponse("放弃", null, null));
        assertInvalid(suggestResponse("ab", "CET-5", null));
    }

    @Test
    @DisplayName("无命中返回空数组")
    void noMatchReturnsEmptyArray() throws Exception {
        JsonNode json = suggest("zzzz", null, null);
        assertThat(json.isArray()).isTrue();
        assertThat(json.size()).isZero();
    }

    @Test
    @DisplayName("未登录返回 401")
    void unauthorized() throws Exception {
        MvcResult result = ApiTestSupport.dictionarySuggest(mockMvc, null, "ab", null, null)
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertThat(ApiTestSupport.body(result).get("code").asText()).isEqualTo("UNAUTHORIZED");
    }

    private JsonNode suggest(String q, String level, Integer limit) throws Exception {
        return ApiTestSupport.body(ApiTestSupport
                .dictionarySuggest(mockMvc, student.token(), q, level, limit)
                .andExpect(status().isOk())
                .andReturn());
    }

    private MvcResult suggestResponse(String q, String level, String limit) throws Exception {
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/v1/dictionary/suggest")
                .header("Authorization", "Bearer " + student.token());
        if (q != null) {
            request = request.param("q", q);
        }
        if (level != null) {
            request = request.param("level", level);
        }
        if (limit != null) {
            request = request.param("limit", limit);
        }
        return mockMvc.perform(request).andReturn();
    }

    private void assertInvalid(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        JsonNode body = ApiTestSupport.body(result);
        assertThat(body.get("code").asText()).isEqualTo("INVALID_INPUT");
        assertThat(body.get("message").asText()).isNotBlank();
    }

    private static List<String> words(JsonNode array) {
        List<String> result = new ArrayList<>();
        array.forEach(item -> result.add(item.get("word").asText()));
        return result;
    }

    private static List<String> fieldNames(JsonNode json) {
        List<String> names = new ArrayList<>();
        json.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
