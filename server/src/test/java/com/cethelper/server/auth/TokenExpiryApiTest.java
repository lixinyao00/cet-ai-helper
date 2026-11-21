package com.cethelper.server.auth;

import com.cethelper.server.support.ApiTestSupport.Student;
import com.cethelper.server.support.MutableClock;
import com.cethelper.server.support.TestClockConfig;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;

import static com.cethelper.server.auth.AuthContractAssertions.CODE_UNAUTHORIZED;
import static com.cethelper.server.auth.AuthContractAssertions.assertError;
import static com.cethelper.server.support.ApiTestSupport.SENTENCE_DECLINE_NOUN;
import static com.cethelper.server.support.ApiTestSupport.body;
import static com.cethelper.server.support.ApiTestSupport.explain;
import static com.cethelper.server.support.ApiTestSupport.logout;
import static com.cethelper.server.support.ApiTestSupport.me;
import static com.cethelper.server.support.ApiTestSupport.newStudent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 会话凭证过期的契约测试。
 *
 * <p>通过 {@link TestClockConfig} 用 {@link MutableClock} 覆盖生产时钟，
 * 把时间往前拨 2 小时即可让已签发的凭证过期（测试 profile 的 token-ttl 为 PT1H），
 * 不依赖真实等待。为避免与其他用例互相影响，本类单独一个 Spring 上下文。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
@DisplayName("会话凭证过期")
class TokenExpiryApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MutableClock clock;

    @Test
    @DisplayName("有效期内凭证可以正常访问 /me")
    void tokenWorksWithinTtl() throws Exception {
        Student student = newStudent(mockMvc);

        // 只过去 30 分钟，远小于 PT1H 的有效期
        clock.advance(Duration.ofMinutes(30));

        MvcResult result = me(mockMvc, student.token()).andExpect(status().isOk()).andReturn();
        JsonNode node = body(result);
        assertThat(node.get("id").asLong())
                .as("有效期内必须能取回自己的身份")
                .isEqualTo(student.id());
        assertThat(node.get("username").asText()).isEqualTo(student.username());
    }

    @Test
    @DisplayName("超过有效期后 /me、释义接口与退出均返回 401 UNAUTHORIZED")
    void expiredTokenIsRejected() throws Exception {
        Student student = newStudent(mockMvc);
        me(mockMvc, student.token()).andExpect(status().isOk());

        // 过期前一刻仍可用；拨动 2 小时后超出发放时的 PT1H 有效期
        clock.advance(Duration.ofHours(2));

        MvcResult meResult = me(mockMvc, student.token()).andReturn();
        assertError(meResult, 401, CODE_UNAUTHORIZED);
        AuthContractAssertions.assertNoInternalLeak(meResult, student.token(), student.password());

        assertError(explain(mockMvc, student.token(), "decline", SENTENCE_DECLINE_NOUN).andReturn(),
                401, CODE_UNAUTHORIZED);

        assertError(logout(mockMvc, student.token()).andReturn(), 401, CODE_UNAUTHORIZED);
    }
}
