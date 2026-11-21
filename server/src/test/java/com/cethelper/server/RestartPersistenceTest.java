package com.cethelper.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 验证文件型 H2 的持久化：服务重启后账号仍能登录，已签发的会话凭证也仍有效。
 *
 * <p>用真实的 Servlet 容器与真实 HTTP 调用，两次启动共用同一个临时数据库文件，
 * 不依赖本地 server/data/ 目录。</p>
 */
class RestartPersistenceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Test
    @DisplayName("重启服务后原账号仍能登录，旧凭证在有效期内仍可用")
    void accountAndSessionSurviveRestart() throws Exception {
        Path databaseDir = Files.createTempDirectory("cet-restart-test");
        String jdbcUrl = "jdbc:h2:file:" + databaseDir.resolve("cet").toAbsolutePath() + ";DB_CLOSE_ON_EXIT=FALSE";
        String username = "restart_user";
        String password = "Passw0rd!23";

        String baseUrl;
        long registeredId;
        String firstToken;

        try (ConfigurableApplicationContext context = start(jdbcUrl)) {
            baseUrl = baseUrl(context);

            Response registered = post(baseUrl, "/api/v1/auth/register",
                    json(Map.of("username", username, "password", password)), null);
            assertThat(registered.status()).isEqualTo(201);
            registeredId = MAPPER.readTree(registered.body()).get("id").asLong();

            Response loggedIn = post(baseUrl, "/api/v1/auth/login",
                    json(Map.of("username", username, "password", password)), null);
            assertThat(loggedIn.status()).isEqualTo(200);
            firstToken = MAPPER.readTree(loggedIn.body()).get("accessToken").asText();

            Response me = get(baseUrl, "/api/v1/me", firstToken);
            assertThat(me.status()).isEqualTo(200);
            assertThat(MAPPER.readTree(me.body()).get("username").asText()).isEqualTo(username);
        }

        // 模拟重启：同一个数据库文件，全新的 Spring 上下文与端口。
        try (ConfigurableApplicationContext restarted = start(jdbcUrl)) {
            String restartedBaseUrl = baseUrl(restarted);
            assertThat(restartedBaseUrl).isNotEqualTo(baseUrl);

            Response relogin = post(restartedBaseUrl, "/api/v1/auth/login",
                    json(Map.of("username", username, "password", password)), null);
            assertThat(relogin.status()).isEqualTo(200);
            String secondToken = MAPPER.readTree(relogin.body()).get("accessToken").asText();
            assertThat(secondToken).isNotEqualTo(firstToken);

            Response me = get(restartedBaseUrl, "/api/v1/me", secondToken);
            assertThat(me.status()).isEqualTo(200);
            JsonNode profile = MAPPER.readTree(me.body());
            assertThat(profile.get("id").asLong()).isEqualTo(registeredId);
            assertThat(profile.get("username").asText()).isEqualTo(username);

            // 会话也持久化：重启前签发且未撤销的凭证仍然有效。
            Response oldTokenMe = get(restartedBaseUrl, "/api/v1/me", firstToken);
            assertThat(oldTokenMe.status()).isEqualTo(200);

            // 旧口令依旧匹配（BCrypt 哈希已落库）。
            Response wrongPassword = post(restartedBaseUrl, "/api/v1/auth/login",
                    json(Map.of("username", username, "password", "WrongPass!23")), null);
            assertThat(wrongPassword.status()).isEqualTo(401);
        }
    }

    private static ConfigurableApplicationContext start(String jdbcUrl) {
        return new SpringApplicationBuilder(CetServerApplication.class)
                .run("--server.port=0",
                        "--spring.datasource.url=" + jdbcUrl,
                        "--cet.explanation.mode=demo",
                        "--cet.auth.token-ttl=PT1H");
    }

    private static String baseUrl(ConfigurableApplicationContext context) {
        String port = context.getEnvironment().getRequiredProperty("local.server.port");
        return "http://127.0.0.1:" + port;
    }

    private static String json(Map<String, Object> payload) {
        try {
            return MAPPER.writeValueAsString(new LinkedHashMap<>(payload));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Response post(String baseUrl, String path, String body, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return send(builder.build());
    }

    private static Response get(String baseUrl, String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path)).GET();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return send(builder.build());
    }

    private static Response send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Response(response.statusCode(), response.body());
    }

    private record Response(int status, String body) {
    }
}
