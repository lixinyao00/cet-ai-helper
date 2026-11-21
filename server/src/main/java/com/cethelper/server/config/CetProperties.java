package com.cethelper.server.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 服务自有配置，前缀 {@code cet}。所有密钥只从环境变量注入，不写入仓库。 */
@ConfigurationProperties(prefix = "cet")
public class CetProperties {

    private final Auth auth = new Auth();
    private final Explanation explanation = new Explanation();

    public Auth getAuth() {
        return auth;
    }

    public Explanation getExplanation() {
        return explanation;
    }

    /** 账号与会话配置。 */
    public static class Auth {

        /** 会话凭证有效期。 */
        private Duration tokenTtl = Duration.ofHours(72);

        public Duration getTokenTtl() {
            return tokenTtl;
        }

        public void setTokenTtl(Duration tokenTtl) {
            this.tokenTtl = tokenTtl;
        }
    }

    /** 释义配置：demo 使用内置样例，live 调用兼容 OpenAI Chat Completions 的服务。 */
    public static class Explanation {

        private String mode = "demo";
        private String baseUrl = "";
        private String model = "";
        private String apiKey = "";
        private Duration connectTimeout = Duration.ofSeconds(3);
        private Duration readTimeout = Duration.ofSeconds(20);

        public boolean isLive() {
            return "live".equalsIgnoreCase(mode == null ? "" : mode.trim());
        }

        public boolean isDemo() {
            return "demo".equalsIgnoreCase(mode == null ? "" : mode.trim());
        }

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }
    }
}
