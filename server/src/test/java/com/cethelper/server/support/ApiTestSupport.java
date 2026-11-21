package com.cethelper.server.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 账号与释义接口测试的公共工具：固定示例语句、随机用户名、常用请求封装。
 *
 * <p>所有用例都通过 {@code mockMvc} 走完整的过滤器链，因此鉴权行为与真实 HTTP 调用一致。</p>
 */
public final class ApiTestSupport {

    /** 满足密码规则的最短可用口令（长度 8–72，UTF-8 不超过 72 字节）。 */
    public static final String VALID_PASSWORD = "Passw0rd!23";

    /** 内置样例第一段的原句，用于核对 decline 作名词「下降」。 */
    public static final String SENTENCE_DECLINE_NOUN = "The report attributed the decline to lower demand.";

    /** 内置样例第二段的原句，用于核对 decline 作动词「婉拒」。 */
    public static final String SENTENCE_DECLINE_VERB = "Some students decline the invitation.";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ApiTestSupport() {
    }

    /** 生成符合用户名规则（字母开头，3–32 位字母数字下划线）且各用例互不冲突的用户名。 */
    public static String uniqueUsername() {
        return "s" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    public static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static JsonNode body(MvcResult result) throws Exception {
        String text = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return MAPPER.readTree(text);
    }

    public static String bodyText(MvcResult result) {
        try {
            return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static ResultActions register(MockMvc mvc, String username, String password) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("username", username);
        payload.put("password", password);
        return mvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(payload)));
    }

    public static ResultActions login(MockMvc mvc, String username, String password) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("username", username);
        payload.put("password", password);
        return mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(payload)));
    }

    public static ResultActions me(MockMvc mvc, String token) throws Exception {
        var request = get("/api/v1/me");
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        return mvc.perform(request);
    }

    public static ResultActions logout(MockMvc mvc, String token) throws Exception {
        var request = post("/api/v1/auth/logout");
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        return mvc.perform(request);
    }

    public static ResultActions explain(MockMvc mvc, String token, String selection, String sentence) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("selection", selection);
        payload.put("sentence", sentence);
        return explainRaw(mvc, token, json(payload));
    }

    /** 发送任意 JSON 报文，用于覆盖字段缺失、类型错误等非法输入。 */
    public static ResultActions explainRaw(MockMvc mvc, String token, String rawJson) throws Exception {
        var request = post("/api/v1/explanations").contentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        return mvc.perform(request.content(rawJson));
    }

    public static ResultActions sentenceAnalysis(MockMvc mvc, String token, String sentence) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sentence", sentence);
        return sentenceAnalysisRaw(mvc, token, json(payload));
    }

    /** 整句分析的任意报文入口，用于覆盖字段缺失、类型错误、超长原句等非法输入。 */
    public static ResultActions sentenceAnalysisRaw(MockMvc mvc, String token, String rawJson) throws Exception {
        var request = post("/api/v1/sentence-analyses").contentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        return mvc.perform(request.content(rawJson));
    }

    public static ResultActions translationHint(MockMvc mvc, String token, String sourceText, String hintType)
            throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sourceText", sourceText);
        payload.put("hintType", hintType);
        return translationHintRaw(mvc, token, json(payload));
    }

    public static ResultActions translationHintRaw(MockMvc mvc, String token, String rawJson) throws Exception {
        var request = post("/api/v1/translation-hints").contentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        return mvc.perform(request.content(rawJson));
    }

    public static ResultActions translationReview(MockMvc mvc, String token, String sourceText,
                                                  String studentTranslation) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sourceText", sourceText);
        payload.put("studentTranslation", studentTranslation);
        return translationReviewRaw(mvc, token, json(payload));
    }

    public static ResultActions translationReviewRaw(MockMvc mvc, String token, String rawJson) throws Exception {
        var request = post("/api/v1/translation-reviews").contentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        return mvc.perform(request.content(rawJson));
    }

    public static ResultActions writingReview(MockMvc mvc, String token, String prompt, String essay)
            throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("prompt", prompt);
        payload.put("essay", essay);
        return writingReviewRaw(mvc, token, json(payload));
    }

    public static ResultActions writingReviewRaw(MockMvc mvc, String token, String rawJson) throws Exception {
        var request = post("/api/v1/writing-reviews").contentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        return mvc.perform(request.content(rawJson));
    }

    /** 登录并取出 accessToken。 */
    public static String accessToken(MockMvc mvc, String username, String password) throws Exception {
        MvcResult result = login(mvc, username, password).andExpect(status().isOk()).andReturn();
        return body(result).get("accessToken").asText();
    }

    /** 注册并登录一名新学生，返回其身份与凭证。 */
    public static Student newStudent(MockMvc mvc) throws Exception {
        String username = uniqueUsername();
        MvcResult registered = register(mvc, username, VALID_PASSWORD)
                .andExpect(status().isCreated())
                .andReturn();
        long id = body(registered).get("id").asLong();
        String token = accessToken(mvc, username, VALID_PASSWORD);
        return new Student(id, username, VALID_PASSWORD, token);
    }

    public record Student(long id, String username, String password, String token) {
    }
}
