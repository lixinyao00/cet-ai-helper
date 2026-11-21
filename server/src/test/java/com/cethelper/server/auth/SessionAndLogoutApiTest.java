package com.cethelper.server.auth;

import com.cethelper.server.support.ApiTestSupport.Student;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.util.stream.Stream;

import static com.cethelper.server.auth.AuthContractAssertions.CODE_UNAUTHORIZED;
import static com.cethelper.server.auth.AuthContractAssertions.assertAccountBody;
import static com.cethelper.server.auth.AuthContractAssertions.assertError;
import static com.cethelper.server.support.ApiTestSupport.SENTENCE_DECLINE_NOUN;
import static com.cethelper.server.support.ApiTestSupport.accessToken;
import static com.cethelper.server.support.ApiTestSupport.body;
import static com.cethelper.server.support.ApiTestSupport.bodyText;
import static com.cethelper.server.support.ApiTestSupport.explain;
import static com.cethelper.server.support.ApiTestSupport.logout;
import static com.cethelper.server.support.ApiTestSupport.me;
import static com.cethelper.server.support.ApiTestSupport.newStudent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 身份识别（GET /api/v1/me）与退出（POST /api/v1/auth/logout）的契约测试。
 *
 * <p>重点覆盖：两名学生互不串号、缺少/伪造凭证统一 401 UNAUTHORIZED、
 * 退出后凭证立即失效（含受保护的释义接口）、重复退出、以及多会话互不干扰。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("身份识别与会话退出")
class SessionAndLogoutApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("/me 返回各自的 id 与 username，两名学生互不串号")
    void meReturnsOwnAccountForEachStudent() throws Exception {
        Student first = newStudent(mockMvc);
        Student second = newStudent(mockMvc);
        assertThat(first.id()).as("两名学生必须是不同账号").isNotEqualTo(second.id());

        JsonNode meFirst = assertAccountBody(
                me(mockMvc, first.token()).andExpect(status().isOk()).andReturn(), first.username());
        assertThat(meFirst.get("id").asLong())
                .as("A 的 /me 返回的 id 必须与注册时一致")
                .isEqualTo(first.id());

        JsonNode meSecond = assertAccountBody(
                me(mockMvc, second.token()).andExpect(status().isOk()).andReturn(), second.username());
        assertThat(meSecond.get("id").asLong())
                .as("B 的 /me 返回的 id 必须与注册时一致")
                .isEqualTo(second.id());

        assertThat(meFirst.get("username").asText())
                .as("A 不能拿到 B 的身份")
                .isNotEqualTo(meSecond.get("username").asText());
    }

    @ParameterizedTest(name = "无凭证访问 {0}")
    @EnumSource(ProtectedEndpoint.class)
    @DisplayName("不带 Authorization 头访问受保护接口：401 UNAUTHORIZED")
    void requestWithoutCredentialReturns401(ProtectedEndpoint endpoint) throws Exception {
        MvcResult result = call(endpoint, null).andReturn();

        assertError(result, 401, CODE_UNAUTHORIZED);
    }

    @ParameterizedTest(name = "Authorization「{0}」")
    @MethodSource("malformedAuthorizationHeaders")
    @DisplayName("伪造或格式错误的凭证访问 /me：401 UNAUTHORIZED")
    void malformedCredentialReturns401(String authorization) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/me").header("Authorization", authorization)).andReturn();

        assertError(result, 401, CODE_UNAUTHORIZED);
    }

    static Stream<String> malformedAuthorizationHeaders() {
        return Stream.of(
                "Bearer",                    // 只有方案名，凭证为空
                "Bearer ",                   // Bearer 后为空
                "Bearer " + "x".repeat(48),  // 长得像凭证但完全是伪造的随机串
                "Basic MTIzNDU2Nzg5MDEyMzQ1Njc4OTA=", // 方案不是 Bearer
                ""                           // 空的 Authorization 头
        );
    }

    @Test
    @DisplayName("退出成功：204，且响应体为空字符串")
    void logoutReturns204WithoutBody() throws Exception {
        Student student = newStudent(mockMvc);

        MvcResult result = logout(mockMvc, student.token())
                .andExpect(status().isNoContent())
                .andReturn();

        assertThat(bodyText(result)).as("204 的响应体必须为空字符串").isEmpty();
        AuthContractAssertions.assertNoInternalLeak(result, student.token(), student.password());
    }

    @Test
    @DisplayName("退出后凭证立即失效：/me 与释义接口均 401，另一名学生的凭证照常可用，重复退出同样 401")
    void logoutRevokesTokenImmediately() throws Exception {
        Student revoked = newStudent(mockMvc);
        Student untouched = newStudent(mockMvc);

        me(mockMvc, revoked.token()).andExpect(status().isOk());
        logout(mockMvc, revoked.token()).andExpect(status().isNoContent());

        MvcResult meResult = me(mockMvc, revoked.token()).andReturn();
        assertError(meResult, 401, CODE_UNAUTHORIZED);
        AuthContractAssertions.assertNoInternalLeak(meResult, revoked.token(), revoked.password());

        MvcResult explainResult = explain(mockMvc, revoked.token(), "decline", SENTENCE_DECLINE_NOUN).andReturn();
        assertError(explainResult, 401, CODE_UNAUTHORIZED);

        MvcResult secondLogout = logout(mockMvc, revoked.token()).andReturn();
        assertError(secondLogout, 401, CODE_UNAUTHORIZED);

        // 退出只撤销自己的会话，另一名学生的凭证必须照常可用
        JsonNode stillValid = assertAccountBody(
                me(mockMvc, untouched.token()).andExpect(status().isOk()).andReturn(), untouched.username());
        assertThat(stillValid.get("id").asLong())
                .as("不能因为别人退出就影响这名学生")
                .isEqualTo(untouched.id());
    }

    @Test
    @DisplayName("同一学生两次登录得到两个不同凭证，退出其中一个不影响另一个")
    void multipleSessionsAreIndependent() throws Exception {
        Student student = newStudent(mockMvc);
        String firstSession = accessToken(mockMvc, student.username(), student.password());
        String secondSession = accessToken(mockMvc, student.username(), student.password());

        assertThat(firstSession)
                .as("每次登录都应签发新的凭证")
                .isNotEqualTo(secondSession);
        me(mockMvc, firstSession).andExpect(status().isOk());
        me(mockMvc, secondSession).andExpect(status().isOk());

        logout(mockMvc, firstSession).andExpect(status().isNoContent());

        assertError(me(mockMvc, firstSession).andReturn(), 401, CODE_UNAUTHORIZED);
        MvcResult stillValid = me(mockMvc, secondSession).andExpect(status().isOk()).andReturn();
        assertThat(body(stillValid).get("id").asLong())
                .as("退出一个会话不能波及同一学生的其他会话")
                .isEqualTo(student.id());
    }

    /** 按受保护接口构造请求；token 为 null 时不带 Authorization 头。 */
    private ResultActions call(ProtectedEndpoint endpoint, String token) throws Exception {
        return switch (endpoint) {
            case ME -> me(mockMvc, token);
            case LOGOUT -> logout(mockMvc, token);
            case EXPLANATIONS -> explain(mockMvc, token, "decline", SENTENCE_DECLINE_NOUN);
        };
    }

    /** 受保护接口：都必须要求有效凭证。 */
    enum ProtectedEndpoint {
        ME,
        LOGOUT,
        EXPLANATIONS
    }
}
