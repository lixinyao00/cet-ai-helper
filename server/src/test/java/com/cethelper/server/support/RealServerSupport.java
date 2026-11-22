package com.cethelper.server.support;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

/** 真实端口测试用的账号工具：通过 TestRestTemplate 注册并登录，拿到 Bearer 凭证。 */
public final class RealServerSupport {

    private RealServerSupport() {
    }

    public static Student newStudent(TestRestTemplate rest) {
        String username = ApiTestSupport.uniqueUsername();
        String password = ApiTestSupport.VALID_PASSWORD;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("username", username);
        payload.put("password", password);

        ResponseEntity<JsonNode> registered =
                rest.postForEntity("/api/v1/auth/register", payload, JsonNode.class);
        if (!registered.getStatusCode().is2xxSuccessful() || registered.getBody() == null) {
            throw new IllegalStateException("注册失败：" + registered.getStatusCode());
        }
        ResponseEntity<JsonNode> login = rest.postForEntity("/api/v1/auth/login", payload, JsonNode.class);
        JsonNode body = login.getBody();
        if (!login.getStatusCode().is2xxSuccessful() || body == null || !body.hasNonNull("accessToken")) {
            throw new IllegalStateException("登录失败：" + login.getStatusCode());
        }
        return new Student(body.get("accessToken").asText());
    }

    public record Student(String token) {
    }
}
