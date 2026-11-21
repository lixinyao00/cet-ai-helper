package com.cethelper.server.auth;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.Map;
import java.util.stream.Stream;

import static com.cethelper.server.auth.AuthContractAssertions.CODE_INVALID_CREDENTIALS;
import static com.cethelper.server.auth.AuthContractAssertions.CODE_INVALID_INPUT;
import static com.cethelper.server.auth.AuthContractAssertions.CODE_USERNAME_TAKEN;
import static com.cethelper.server.auth.AuthContractAssertions.assertAccountBody;
import static com.cethelper.server.auth.AuthContractAssertions.assertError;
import static com.cethelper.server.auth.AuthContractAssertions.assertNoInternalLeak;
import static com.cethelper.server.auth.AuthContractAssertions.parseIsoTime;
import static com.cethelper.server.support.ApiTestSupport.VALID_PASSWORD;
import static com.cethelper.server.support.ApiTestSupport.body;
import static com.cethelper.server.support.ApiTestSupport.json;
import static com.cethelper.server.support.ApiTestSupport.login;
import static com.cethelper.server.support.ApiTestSupport.register;
import static com.cethelper.server.support.ApiTestSupport.uniqueUsername;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 注册与登录接口的契约测试。
 *
 * <p>只通过 {@link MockMvc} 走完整 HTTP 过滤器链断言行为，不注入任何 repository/service，
 * 因此不依赖主控的实现细节。所有用户名都由 {@code uniqueUsername()} 生成，用例之间互不干扰。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("账号注册与登录接口")
class RegistrationAndLoginApiTest {

    /** 用于验证「用户名不存在」与「口令错误」两种失败原因的提示一致。 */
    private static final String WRONG_PASSWORD = "WrongPass!9876";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("注册成功：201，正文恰好 id 与 username 两个字段")
    void registerReturns201WithAccountBody() throws Exception {
        String username = uniqueUsername();

        MvcResult result = register(mockMvc, username, VALID_PASSWORD)
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode node = assertAccountBody(result, username);
        assertThat(node.get("id").asLong()).as("注册返回的 id 必须是正整数，实际：%s", node).isPositive();
    }

    @Test
    @DisplayName("同一用户名重复注册：409 USERNAME_TAKEN")
    void duplicateUsernameReturns409() throws Exception {
        String username = uniqueUsername();
        register(mockMvc, username, VALID_PASSWORD).andExpect(status().isCreated());

        MvcResult result = register(mockMvc, username, VALID_PASSWORD).andReturn();

        assertError(result, 409, CODE_USERNAME_TAKEN);
        assertNoInternalLeak(result, VALID_PASSWORD);
    }

    @ParameterizedTest(name = "用户名「{0}」不合法")
    @MethodSource("invalidUsernames")
    @DisplayName("违反用户名规则的注册请求：400 INVALID_INPUT")
    void invalidUsernameReturns400(String username) throws Exception {
        MvcResult result = register(mockMvc, username, VALID_PASSWORD).andReturn();

        assertError(result, 400, CODE_INVALID_INPUT);
    }

    /** 用户名规则：^[A-Za-z][A-Za-z0-9_]{2,31}$（3–32 位、字母开头、仅字母数字下划线）。 */
    static Stream<String> invalidUsernames() {
        return Stream.of(
                "ab",                 // 只有 2 位，过短
                "1student",           // 数字开头
                "_student",           // 下划线开头
                "stu dent",           // 含空格
                "st@dent",            // 含非法字符 @
                "a".repeat(33),       // 33 位，过长
                "李小明"               // 非 ASCII 字母
        );
    }

    @ParameterizedTest(name = "口令不符合规则[{index}]")
    @MethodSource("invalidPasswords")
    @DisplayName("违反口令规则的注册请求：400 INVALID_INPUT")
    void invalidPasswordReturns400(String password) throws Exception {
        MvcResult result = register(mockMvc, uniqueUsername(), password).andReturn();

        assertError(result, 400, CODE_INVALID_INPUT);
    }

    /** 口令规则：8–72 个字符、UTF-8 编码不超过 72 字节、首尾不能有空白字符。 */
    static Stream<String> invalidPasswords() {
        return Stream.of(
                "Ab1!xyz",                    // 只有 7 个字符，过短
                "        ",                   // 8 个空格，首尾都是空白
                " Passw0rd!23",               // 前导空白
                "Passw0rd!23 ",               // 尾部空白
                "Aa1!" + "x".repeat(69),      // 73 个字符，过长
                "密".repeat(30),              // 30 个字符但 UTF-8 占 90 字节，超过 72 字节
                ""                            // 空口令
        );
    }

    @Test
    @DisplayName("登录成功：accessToken、tokenType=Bearer、expiresAt 为可解析且未来的 ISO-8601 时间")
    void loginReturnsBearerCredential() throws Exception {
        String username = uniqueUsername();
        register(mockMvc, username, VALID_PASSWORD).andExpect(status().isCreated());

        MvcResult result = login(mockMvc, username, VALID_PASSWORD)
                .andExpect(status().isOk())
                .andReturn();

        JsonNode node = body(result);
        assertThat(node.isObject()).as("登录成功必须是 JSON 对象，实际：%s", node).isTrue();

        String token = node.path("accessToken").asText(null);
        assertThat(token)
                .as("accessToken 必须是非空字符串，实际：%s", node)
                .isNotNull()
                .isNotBlank()
                .hasSizeGreaterThanOrEqualTo(32);
        assertThat(token).as("accessToken 不能等于明文口令").isNotEqualTo(VALID_PASSWORD);
        assertThat(node.path("tokenType").asText())
                .as("tokenType 必须恰好是 Bearer，实际：%s", node)
                .isEqualTo("Bearer");

        Instant expiresAt = parseIsoTime(node, "expiresAt");
        assertThat(expiresAt).as("expiresAt 必须晚于当前时间").isAfter(Instant.now());

        assertThat(AuthContractAssertions.rawBody(result))
                .as("登录响应不得回显明文口令")
                .doesNotContain(VALID_PASSWORD);
    }

    @Test
    @DisplayName("用户名不存在与口令错误：统一 401 INVALID_CREDENTIALS，且 code 与 message 完全一致")
    void unknownUserAndWrongPasswordShareSameErrorBody() throws Exception {
        String username = uniqueUsername();
        register(mockMvc, username, VALID_PASSWORD).andExpect(status().isCreated());

        MvcResult unknownUser = login(mockMvc, uniqueUsername(), VALID_PASSWORD).andReturn();
        JsonNode unknownNode = assertError(unknownUser, 401, CODE_INVALID_CREDENTIALS);

        MvcResult wrongPassword = login(mockMvc, username, WRONG_PASSWORD).andReturn();
        JsonNode wrongNode = assertError(wrongPassword, 401, CODE_INVALID_CREDENTIALS);

        assertThat(unknownNode.get("message").asText())
                .as("防止枚举账号：两种失败原因的提示必须完全相同")
                .isEqualTo(wrongNode.get("message").asText());
        assertNoInternalLeak(wrongPassword, WRONG_PASSWORD);
    }

    @ParameterizedTest(name = "登录报文：{0}")
    @MethodSource("loginPayloadsWithoutUsername")
    @DisplayName("登录时用户名字段缺失或空白：400 INVALID_INPUT")
    void loginWithoutUsernameReturns400(String rawJson) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rawJson))
                .andReturn();

        assertError(result, 400, CODE_INVALID_INPUT);
    }

    static Stream<String> loginPayloadsWithoutUsername() {
        return Stream.of(
                "{\"password\":\"Passw0rd!23\"}",                           // username 字段缺失
                json(Map.of("username", "   ", "password", VALID_PASSWORD)), // username 全是空白
                json(Map.of("username", "", "password", VALID_PASSWORD)),    // username 空串
                "{}"                                                          // 完全空的报文
        );
    }
}
