package com.cethelper.server.auth;

import static com.cethelper.server.support.ApiTestSupport.body;
import static com.cethelper.server.support.ApiTestSupport.json;
import static com.cethelper.server.support.ApiTestSupport.uniqueUsername;
import static org.assertj.core.api.Assertions.assertThat;

import com.cethelper.server.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 注册与登录的严格 JSON 类型校验回归测试。
 *
 * <p>Jackson 默认会把数字、布尔值隐式转成字符串，于是 {@code {"password": 12345678}}
 * 会被当成口令 {@code "12345678"}，既掩盖客户端错误，也可能让"数字形态"的口令通过认证。
 * 本轮要求这类请求一律 400 {@code INVALID_INPUT}，而真正以字符串提交的纯数字口令照常可用。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("注册与登录的严格 JSON 类型校验")
class StrictJsonTypeApiTest {

    /** 只由数字组成的口令：作为字符串合法，作为 JSON 数字必须被拒。 */
    private static final String DIGIT_ONLY_PASSWORD = "12345678";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("口令写成 JSON 数字不会被当成字符串口令，返回 400 且无法登录")
    void numericPasswordIsRejectedAndDoesNotAuthenticate() throws Exception {
        String username = uniqueUsername();

        // 先用"字符串形态"的纯数字口令建号，证明这个口令本身合法。
        register(username, DIGIT_ONLY_PASSWORD).andExpect(status().isCreated());

        // 再用数字形态提交同一个口令：必须 400，而不是登录成功。
        MvcResult numericLogin = loginRaw(username, 12345678).andReturn();
        assertThat(numericLogin.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(numericLogin).get("code").asText()).isEqualTo("INVALID_INPUT");

        // 字符串形态仍然可以正常登录。
        MvcResult stringLogin = login(username, DIGIT_ONLY_PASSWORD).andReturn();
        assertThat(stringLogin.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(stringLogin).get("accessToken").asText()).isNotBlank();
    }

    @Test
    @DisplayName("注册时口令传数字、小数或布尔值：400 INVALID_INPUT，且不会建号")
    void numericOrBooleanPasswordOnRegisterIsRejected() throws Exception {
        for (Object badValue : List.of(12345678, 12.5, true, false)) {
            String username = uniqueUsername();
            MvcResult result = registerRaw(username, badValue).andReturn();

            assertThat(result.getResponse().getStatus())
                    .as("口令必须是 JSON 字符串，实际提交：%s", badValue)
                    .isEqualTo(400);
            JsonNode error = body(result);
            assertThat(error.get("code").asText()).isEqualTo("INVALID_INPUT");
            assertThat(error.get("message").asText()).isNotBlank();

            // 没有账号被创建：用数字形态对应的字符串口令登录会得到 401 而不是 200。
            assertThat(login(username, String.valueOf(badValue)).andReturn().getResponse().getStatus())
                    .as("被拒的注册不应留下账号：%s", badValue)
                    .isEqualTo(401);
        }
    }

    @Test
    @DisplayName("用户名传数字或布尔值：注册与登录都返回 400 INVALID_INPUT")
    void nonStringUsernameIsRejected() throws Exception {
        for (Object badValue : List.of(1001, 3.14, true)) {
            MvcResult registered = mockMvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(payload(badValue, "Passw0rd!23"))))
                    .andReturn();
            assertThat(registered.getResponse().getStatus())
                    .as("注册用户名必须是 JSON 字符串，实际提交：%s", badValue)
                    .isEqualTo(400);
            assertThat(body(registered).get("code").asText()).isEqualTo("INVALID_INPUT");

            MvcResult loggedIn = mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(payload(badValue, "Passw0rd!23"))))
                    .andReturn();
            assertThat(loggedIn.getResponse().getStatus())
                    .as("登录用户名必须是 JSON 字符串，实际提交：%s", badValue)
                    .isEqualTo(400);
            assertThat(body(loggedIn).get("code").asText()).isEqualTo("INVALID_INPUT");
        }
    }

    @Test
    @DisplayName("类型错误响应不回声提交的口令，也不含堆栈")
    void typeErrorResponseDoesNotEchoInput() throws Exception {
        MvcResult result = registerRaw(uniqueUsername(), 987654321).andReturn();

        String text = ApiTestSupport.bodyText(result);
        assertThat(text).doesNotContain("987654321");
        assertThat(text).doesNotContain("Exception", "at com.cethelper", "org.springframework");
    }

    private ResultActions register(String username, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(payload(username, password))));
    }

    private ResultActions login(String username, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(payload(username, password))));
    }

    private ResultActions registerRaw(String username, Object password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(payload(username, password))));
    }

    private ResultActions loginRaw(String username, Object password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(payload(username, password))));
    }

    private static Map<String, Object> payload(Object username, Object password) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("username", username);
        payload.put("password", password);
        return payload;
    }
}
