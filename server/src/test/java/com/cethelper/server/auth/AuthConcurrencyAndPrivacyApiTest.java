package com.cethelper.server.auth;

import com.cethelper.server.support.ApiTestSupport.Student;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.cethelper.server.auth.AuthContractAssertions.CODE_INVALID_CREDENTIALS;
import static com.cethelper.server.auth.AuthContractAssertions.CODE_INVALID_INPUT;
import static com.cethelper.server.auth.AuthContractAssertions.CODE_UNAUTHORIZED;
import static com.cethelper.server.auth.AuthContractAssertions.CODE_USERNAME_TAKEN;
import static com.cethelper.server.auth.AuthContractAssertions.assertAccountBody;
import static com.cethelper.server.auth.AuthContractAssertions.assertError;
import static com.cethelper.server.auth.AuthContractAssertions.assertNoInternalLeak;
import static com.cethelper.server.support.ApiTestSupport.VALID_PASSWORD;
import static com.cethelper.server.support.ApiTestSupport.body;
import static com.cethelper.server.support.ApiTestSupport.bodyText;
import static com.cethelper.server.support.ApiTestSupport.login;
import static com.cethelper.server.support.ApiTestSupport.logout;
import static com.cethelper.server.support.ApiTestSupport.me;
import static com.cethelper.server.support.ApiTestSupport.newStudent;
import static com.cethelper.server.support.ApiTestSupport.register;
import static com.cethelper.server.support.ApiTestSupport.uniqueUsername;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 用户名唯一约束的并发验证，以及响应体的敏感信息检查。
 *
 * <p>并发用例用 2 个线程 + {@link CountDownLatch} 同时注册同一用户名：数据库唯一约束必须兜住，
 * 落败的一方要映射成 409 USERNAME_TAKEN 而不是 500。MockMvc 在多线程里使用是安全的，
 * 但两个 {@link MvcResult} 都收集回主线程再断言，避免断言抛在线程里被吞掉。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("注册唯一约束与响应脱敏")
class AuthConcurrencyAndPrivacyApiTest {

    /** 与正确的口令不同，用于验证错误响应不会回显提交的口令。 */
    private static final String WRONG_PASSWORD = "WrongPass!9876";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("并发注册同一用户名：恰好一个 201、一个 409 USERNAME_TAKEN")
    void concurrentRegistrationAllowsOnlyOneAccount() throws Exception {
        String username = uniqueUsername();
        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<MvcResult> results = new ArrayList<>(2);

        try {
            List<Future<MvcResult>> futures = new ArrayList<>(2);
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    // 两个线程在栅栏处对齐后同时发起注册，尽量制造真正的时间重叠
                    boolean released = startGate.await(5, TimeUnit.SECONDS);
                    if (!released) {
                        throw new IllegalStateException("并发起跑栅栏等待超时");
                    }
                    return register(mockMvc, username, VALID_PASSWORD).andReturn();
                }));
            }
            startGate.countDown();
            for (Future<MvcResult> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        List<Integer> statuses = results.stream().map(result -> result.getResponse().getStatus()).toList();
        assertThat(statuses)
                .as("同一用户名并发注册必须一个成功一个冲突，实际状态码：%s", statuses)
                .containsExactlyInAnyOrder(201, 409);

        MvcResult conflict = results.stream()
                .filter(result -> result.getResponse().getStatus() == 409)
                .findFirst()
                .orElseThrow(() -> new AssertionError("并发注册没有产生 409 冲突，实际状态码：" + statuses));
        assertError(conflict, 409, CODE_USERNAME_TAKEN);
        assertNoInternalLeak(conflict, VALID_PASSWORD);

        // 该用户名仍然只有一个账号：可以正常登录并取回自己的身份
        MvcResult loginResult = login(mockMvc, username, VALID_PASSWORD)
                .andExpect(status().isOk())
                .andReturn();
        String token = body(loginResult).get("accessToken").asText();
        assertAccountBody(me(mockMvc, token).andExpect(status().isOk()).andReturn(), username);

        // 事后再注册同名，依旧只有一个账号、依旧 409
        assertError(register(mockMvc, username, VALID_PASSWORD).andReturn(), 409, CODE_USERNAME_TAKEN);
    }

    @Test
    @DisplayName("注册与登录的成功响应不包含明文口令或口令哈希")
    void successResponsesDoNotEchoPassword() throws Exception {
        String username = uniqueUsername();

        MvcResult registered = register(mockMvc, username, VALID_PASSWORD)
                .andExpect(status().isCreated())
                .andReturn();
        assertThat(bodyText(registered))
                .as("注册响应不得回显明文口令")
                .doesNotContain(VALID_PASSWORD);
        assertThat(bodyText(registered))
                .as("注册响应不得出现口令哈希")
                .doesNotContain("$2a$", "$2b$", "$2y$");

        MvcResult loggedIn = login(mockMvc, username, VALID_PASSWORD)
                .andExpect(status().isOk())
                .andReturn();
        assertThat(bodyText(loggedIn))
                .as("登录响应不得回显明文口令")
                .doesNotContain(VALID_PASSWORD);
        assertThat(bodyText(loggedIn))
                .as("登录响应不得出现口令哈希")
                .doesNotContain("$2a$", "$2b$", "$2y$");

        // 成功响应同样不允许出现内部堆栈痕迹
        assertNoInternalLeak(registered);
        assertNoInternalLeak(loggedIn);
    }

    @Test
    @DisplayName("400/409/401 错误响应不泄露口令、完整凭证与内部堆栈")
    void errorResponsesDoNotLeakSecrets() throws Exception {
        String username = uniqueUsername();
        String rejectedPassword = "Secret!Pass99";

        // 400：非法输入，响应里不得出现被拒的口令
        MvcResult invalidInput = register(mockMvc, "ab", rejectedPassword).andReturn();
        assertError(invalidInput, 400, CODE_INVALID_INPUT);
        assertNoInternalLeak(invalidInput, rejectedPassword);

        // 409：用户名重复，响应里不得出现口令
        register(mockMvc, username, VALID_PASSWORD).andExpect(status().isCreated());
        MvcResult conflict = register(mockMvc, username, VALID_PASSWORD).andReturn();
        assertError(conflict, 409, CODE_USERNAME_TAKEN);
        assertNoInternalLeak(conflict, VALID_PASSWORD, rejectedPassword);

        // 401：口令错误，响应里不得回显提交的口令或正确口令
        MvcResult wrongPassword = login(mockMvc, username, WRONG_PASSWORD).andReturn();
        assertError(wrongPassword, 401, CODE_INVALID_CREDENTIALS);
        assertNoInternalLeak(wrongPassword, WRONG_PASSWORD, VALID_PASSWORD);

        // 401：已退出的凭证，响应里不得回显该凭证
        Student student = newStudent(mockMvc);
        logout(mockMvc, student.token()).andExpect(status().isNoContent());
        MvcResult revoked = me(mockMvc, student.token()).andReturn();
        assertError(revoked, 401, CODE_UNAUTHORIZED);
        assertNoInternalLeak(revoked, student.token(), student.password());
        JsonNode errorBody = AuthContractAssertions.bodyOf(revoked);
        assertThat(errorBody.size()).as("401 错误体同样只有 code 与 message").isEqualTo(2);
    }
}
