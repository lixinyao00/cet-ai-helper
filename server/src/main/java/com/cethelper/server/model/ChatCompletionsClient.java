package com.cethelper.server.model;

import com.cethelper.server.common.ApiException;
import com.cethelper.server.config.CetProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** 通过现有 live 配置调用兼容 OpenAI 的 Chat Completions 上游。 */
@Component
@ConditionalOnProperty(prefix = "cet.explanation", name = "mode", havingValue = "live")
public class ChatCompletionsClient {

    private static final Logger log = LoggerFactory.getLogger(ChatCompletionsClient.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String model;
    private final String apiKey;
    private final Duration connectTimeout;
    private final Duration readTimeout;

    public ChatCompletionsClient(CetProperties properties, ObjectMapper objectMapper) {
        CetProperties.Explanation config = properties.getExplanation();
        String baseUrl = blankToNull(config.getBaseUrl());
        this.model = blankToNull(config.getModel());
        this.apiKey = blankToNull(config.getApiKey());
        if (baseUrl == null || model == null || apiKey == null) {
            throw new IllegalStateException(
                    "live 模式缺少配置：请设置 CET_MODEL_BASE_URL、CET_MODEL_NAME 与 CET_MODEL_API_KEY；"
                            + "无凭据时请使用 CET_EXPLANATION_MODE=demo。");
        }
        this.objectMapper = objectMapper;
        this.connectTimeout = config.getConnectTimeout();
        this.readTimeout = config.getReadTimeout();

        String normalizedBaseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        this.restClient = RestClient.builder()
                .baseUrl(normalizedBaseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    public String complete(String systemPrompt, String userContent, int maxTokens,
                           String timeoutMessage, String errorMessage) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("temperature", 0.2);
        payload.put("max_tokens", maxTokens);
        payload.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userContent)));

        try {
            String responseBody = restClient.post()
                    .uri("/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(String.class);
            return contentOf(responseBody, errorMessage);
        } catch (ResourceAccessException ex) {
            if (isTimeout(ex)) {
                log.warn("在线模型上游超时（connectTimeout={}, readTimeout={}）", connectTimeout, readTimeout);
                throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "MODEL_TIMEOUT", timeoutMessage);
            }
            log.warn("在线模型上游连接失败：{}", ex.getClass().getSimpleName());
            throw modelError(errorMessage);
        } catch (RestClientResponseException ex) {
            // 只记录状态码，不记录上游响应正文，避免把供应商内容带进日志。
            log.warn("在线模型上游返回 HTTP {}", ex.getStatusCode().value());
            throw modelError(errorMessage);
        } catch (RestClientException ex) {
            log.warn("在线模型调用失败：{}", ex.getClass().getSimpleName());
            throw modelError(errorMessage);
        }
    }

    /** 从 chat.completions 信封里取出 content；结构不对按格式错误处理。 */
    private String contentOf(String responseBody, String errorMessage) {
        if (responseBody == null || responseBody.isBlank()) {
            log.warn("在线模型上游返回空响应");
            throw modelError(errorMessage);
        }
        JsonNode contentNode;
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            contentNode = root.path("choices").path(0).path("message").path("content");
        } catch (JsonProcessingException e) {
            log.warn("在线模型上游响应不是合法 JSON");
            throw modelError(errorMessage);
        }
        if (!contentNode.isTextual() || contentNode.asText().isBlank()) {
            log.warn("在线模型上游响应缺少 choices[0].message.content");
            throw modelError(errorMessage);
        }
        return stripCodeFence(contentNode.asText().trim());
    }

    /** 兼容模型把 JSON 包在 ```json 代码块里的情况。 */
    private static String stripCodeFence(String content) {
        String text = content;
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            if (firstNewline >= 0) {
                text = text.substring(firstNewline + 1);
            }
            if (text.endsWith("```")) {
                text = text.substring(0, text.length() - 3);
            }
        }
        return text.trim();
    }

    private static boolean isTimeout(Throwable throwable) {
        Throwable current = throwable;
        for (int depth = 0; current != null && depth < 10; depth++) {
            if (current instanceof HttpTimeoutException
                    || current instanceof SocketTimeoutException
                    || current instanceof InterruptedIOException) {
                return true;
            }
            if (current == current.getCause()) {
                break;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    public static ApiException modelError(String message) {
        return new ApiException(HttpStatus.BAD_GATEWAY, "MODEL_ERROR", message);
    }
}
