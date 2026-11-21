package com.cethelper.server.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cethelper.server.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * {@code GET /api/v1/health} 的契约测试（demo 模式）。
 *
 * <p>覆盖：无需 Bearer 凭证、响应字段固定、模式与当前配置一致、伪造凭证不影响结果。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("健康与模式探测接口（demo）")
class HealthApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("匿名调用即 200：status=UP、mode=demo、version=0.2.0")
    void anonymousCallReturnsHealth() throws Exception {
        MvcResult result = ApiTestSupport.health(mockMvc, null)
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = ApiTestSupport.body(result);

        assertThat(fieldNames(json)).containsExactlyInAnyOrder("status", "mode", "version");
        assertThat(json.get("status").asText()).isEqualTo("UP");
        assertThat(json.get("mode").asText()).isEqualTo("demo");
        assertThat(json.get("version").asText()).isEqualTo("0.2.0");
    }

    @Test
    @DisplayName("带上无效凭证也返回 200，说明该接口确实免鉴权")
    void bogusTokenStillSucceeds() throws Exception {
        MvcResult result = ApiTestSupport.health(mockMvc, "definitely-not-a-valid-token")
                .andExpect(status().isOk())
                .andReturn();

        assertThat(ApiTestSupport.body(result).get("status").asText()).isEqualTo("UP");
    }

    @Test
    @DisplayName("已登录学生调用同样 200，响应体不包含账号或凭证")
    void loggedInStudentGetsSameBody() throws Exception {
        ApiTestSupport.Student student = ApiTestSupport.newStudent(mockMvc);
        MvcResult result = ApiTestSupport.health(mockMvc, student.token())
                .andExpect(status().isOk())
                .andReturn();

        String text = ApiTestSupport.bodyText(result);
        assertThat(text).doesNotContain(student.password());
        assertThat(text).doesNotContain(student.token());
        assertThat(ApiTestSupport.body(result).get("mode").asText()).isEqualTo("demo");
    }

    private static List<String> fieldNames(JsonNode json) {
        List<String> names = new ArrayList<>();
        json.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
