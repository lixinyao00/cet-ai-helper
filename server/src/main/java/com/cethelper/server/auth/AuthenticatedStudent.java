package com.cethelper.server.auth;

/**
 * 通过 Bearer 凭证识别出的当前学生，作为 Spring Security 的主体放入安全上下文。
 *
 * <p>只携带学生与会话标识，不携带明文 token，避免被日志或异常信息带出。</p>
 */
public record AuthenticatedStudent(Long studentId, String username, Long sessionId) {
}
