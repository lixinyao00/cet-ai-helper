package com.cethelper.server.auth;

import com.cethelper.server.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * auth 包测试共用的契约断言。
 *
 * <p>这里只做断言与取值，不发请求、不注入任何 repository/service，
 * 因此用例只依赖 HTTP 契约，不与主控的业务实现耦合。</p>
 */
final class AuthContractAssertions {

    /** 统一错误码：违反输入规则。 */
    static final String CODE_INVALID_INPUT = "INVALID_INPUT";

    /** 统一错误码：用户名已被占用。 */
    static final String CODE_USERNAME_TAKEN = "USERNAME_TAKEN";

    /** 统一错误码：用户名不存在或口令错误（两者共用，防止枚举账号）。 */
    static final String CODE_INVALID_CREDENTIALS = "INVALID_CREDENTIALS";

    /** 统一错误码：缺少、格式错误、伪造、已退出或已过期的会话凭证。 */
    static final String CODE_UNAUTHORIZED = "UNAUTHORIZED";

    /** 错误响应里绝不允许出现的内部实现痕迹。 */
    private static final String[] INTERNAL_MARKERS = {
            "Exception", "at com.cethelper", "org.springframework", "java.lang"
    };

    private AuthContractAssertions() {
    }

    /** 读取响应正文字符串（UTF-8）。 */
    static String rawBody(MvcResult result) {
        try {
            return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("读取响应体失败", e);
        }
    }

    /** 把响应正文解析成 JSON 树。 */
    static JsonNode bodyOf(MvcResult result) {
        try {
            return ApiTestSupport.body(result);
        } catch (Exception e) {
            throw new IllegalStateException("响应正文不是合法 JSON：" + rawBody(result), e);
        }
    }

    /**
     * 断言统一错误体：状态码、恰好 {@code code} 与 {@code message} 两个字段、message 为中文非空说明。
     *
     * @return 错误体 JSON，便于调用方继续做逐字比对
     */
    static JsonNode assertError(MvcResult result, int expectedStatus, String expectedCode) {
        assertThat(result.getResponse().getStatus())
                .as("HTTP 状态码（期望 %d），响应正文：%s", expectedStatus, rawBody(result))
                .isEqualTo(expectedStatus);

        JsonNode node = bodyOf(result);
        assertThat(node.isObject())
                .as("错误响应必须是 JSON 对象，实际正文：%s", rawBody(result))
                .isTrue();
        assertThat(node.size())
                .as("错误体只允许 code 与 message 两个字段，实际：%s", node)
                .isEqualTo(2);
        assertThat(node.hasNonNull("code") && node.get("code").isTextual())
                .as("错误体缺少字符串字段 code，实际：%s", node)
                .isTrue();
        assertThat(node.get("code").asText())
                .as("错误码，实际错误体：%s", node)
                .isEqualTo(expectedCode);
        assertThat(node.hasNonNull("message") && node.get("message").isTextual())
                .as("错误体缺少字符串字段 message，实际：%s", node)
                .isTrue();

        String message = node.get("message").asText();
        assertThat(message).as("错误说明必须是非空字符串，实际错误体：%s", node).isNotBlank();
        assertThat(containsHan(message)).as("错误说明必须是中文，实际为：%s", message).isTrue();
        return node;
    }

    /**
     * 断言账号体：恰好 {@code id} 与 {@code username} 两个字段，id 为正整数，username 等于期望值。
     *
     * <p>状态码由调用方自行断言（注册是 201，/me 是 200）。</p>
     */
    static JsonNode assertAccountBody(MvcResult result, String expectedUsername) {
        JsonNode node = bodyOf(result);
        assertThat(node.isObject())
                .as("账号响应必须是 JSON 对象，实际正文：%s", rawBody(result))
                .isTrue();
        assertThat(node.size())
                .as("账号信息只包含 id 与 username 两个字段，实际：%s", node)
                .isEqualTo(2);
        assertThat(node.hasNonNull("id") && node.get("id").isNumber())
                .as("账号响应缺少数字字段 id，实际：%s", node)
                .isTrue();
        assertThat(node.get("id").asLong()).as("id 必须是正整数，实际：%s", node).isPositive();
        assertThat(node.hasNonNull("username") && node.get("username").isTextual())
                .as("账号响应缺少字符串字段 username，实际：%s", node)
                .isTrue();
        assertThat(node.get("username").asText())
                .as("username 必须与注册时一致，实际：%s", node)
                .isEqualTo(expectedUsername);
        return node;
    }

    /** 把字段解析为 ISO-8601 时间点，并断言它确实是个可解析的字符串而不是时间戳数字。 */
    static Instant parseIsoTime(JsonNode node, String field) {
        assertThat(node.hasNonNull(field)).as("响应缺少字段 %s，实际：%s", field, node).isTrue();
        assertThat(node.get(field).isTextual())
                .as("%s 必须是 ISO-8601 时间字符串而不是数字，实际：%s", field, node)
                .isTrue();

        String text = node.get(field).asText();
        Instant parsed = null;
        try {
            parsed = OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException ignored) {
            parsed = null;
        }
        assertThat(parsed)
                .as("%s 必须是可解析的 ISO-8601 时间字符串（如 2026-11-21T08:00:00Z），实际为：%s", field, text)
                .isNotNull();
        return parsed;
    }

    /** 断言响应体既没有堆栈痕迹，也没有外泄给定的敏感内容（口令、完整凭证等）。 */
    static void assertNoInternalLeak(MvcResult result, String... forbiddenValues) {
        String raw = rawBody(result);
        assertThat(raw)
                .as("响应体不得携带 Java 堆栈或框架内部信息")
                .doesNotContain(INTERNAL_MARKERS);
        for (String forbidden : forbiddenValues) {
            if (forbidden == null || forbidden.isBlank()) {
                continue;
            }
            assertThat(raw)
                    .as("响应体不得外泄敏感内容「%s」", forbidden)
                    .doesNotContain(forbidden);
        }
    }

    /** 判断文本里是否含汉字，用于确认错误说明是中文。 */
    private static boolean containsHan(String text) {
        return text.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN);
    }
}
