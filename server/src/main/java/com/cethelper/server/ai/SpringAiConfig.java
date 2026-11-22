package com.cethelper.server.ai;

import com.cethelper.server.config.CetProperties;
import io.netty.channel.ChannelOption;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * live 模式下手工装配 Spring AI 的 {@link OpenAiApi} / {@link OpenAiChatModel} / {@link ChatClient}。
 *
 * <p>刻意不引入任何 Spring AI starter 或自动配置：demo 模式（默认）下本配置不生效，
 * 应用无需 {@code spring.ai.openai.api-key} 也能零配置启动。所有连接参数都复用
 * {@link CetProperties.Explanation}，与既有的 live 客户端保持一致。</p>
 *
 * <p>baseUrl 归一化：{@code FakeOpenAiServer.baseUrl()} 返回的地址以 {@code /v1} 结尾，
 * 此时把 completionsPath 改为 {@code /chat/completions}，避免拼出 {@code /v1/v1/chat/completions}；
 * 其余地址沿用 Spring AI 默认的 {@code /v1/chat/completions}。</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "cet.explanation", name = "mode", havingValue = "live")
public class SpringAiConfig {

    /** 与 {@code RetryUtils.NO_RETRY_TEMPLATE} 等价：只尝试一次，测试中的错误响应不会被重试。 */
    private static final RetryTemplate NO_RETRY_TEMPLATE = RetryTemplate.builder().maxAttempts(1).build();

    @Bean
    public OpenAiApi cetOpenAiApi(CetProperties properties) {
        CetProperties.Explanation config = properties.getExplanation();
        String baseUrl = required(config.getBaseUrl(), "CET_MODEL_BASE_URL");
        String apiKey = required(config.getApiKey(), "CET_MODEL_API_KEY");
        // model 的必填校验留给 OpenAiChatModel，保证启动失败信息一并提示三个变量。
        required(config.getModel(), "CET_MODEL_NAME");

        String normalizedBaseUrl = stripTrailingSlashes(baseUrl);
        boolean versionedBaseUrl = normalizedBaseUrl.endsWith("/v1");

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(config.getConnectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(config.getReadTimeout());

        WebClient.Builder webClientBuilder = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(reactor.netty.http.client.HttpClient.create()
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeoutMillis(config.getConnectTimeout()))));

        OpenAiApi.Builder builder = OpenAiApi.builder()
                .baseUrl(normalizedBaseUrl)
                .apiKey(apiKey)
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                .webClientBuilder(webClientBuilder);
        if (versionedBaseUrl) {
            builder.completionsPath("/chat/completions");
        }
        return builder.build();
    }

    @Bean
    public OpenAiChatModel cetOpenAiChatModel(OpenAiApi api, CetProperties properties) {
        CetProperties.Explanation config = properties.getExplanation();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(required(config.getModel(), "CET_MODEL_NAME"))
                        .temperature(0.4)
                        .build())
                .retryTemplate(NO_RETRY_TEMPLATE)
                .build();
    }

    @Bean
    public ChatClient cetChatClient(OpenAiChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }

    private static String required(String value, String variable) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "live 模式缺少配置：" + variable + " 不能为空；请设置 CET_MODEL_BASE_URL、"
                            + "CET_MODEL_NAME 与 CET_MODEL_API_KEY，或改用 CET_EXPLANATION_MODE=demo。");
        }
        return value.strip();
    }

    private static String stripTrailingSlashes(String value) {
        String current = value;
        while (current.endsWith("/")) {
            current = current.substring(0, current.length() - 1);
        }
        return current;
    }

    private static int timeoutMillis(Duration timeout) {
        long millis = timeout == null ? 3000L : timeout.toMillis();
        if (millis <= 0) {
            return 3000;
        }
        return (int) Math.min(Integer.MAX_VALUE, millis);
    }
}
