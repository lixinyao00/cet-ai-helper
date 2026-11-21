package com.cethelper.server.auth;

/**
 * 登录成功响应。
 *
 * @param accessToken 不透明随机凭证，客户端后续放在 {@code Authorization: Bearer &lt;token&gt;}
 * @param tokenType   固定为 {@code Bearer}
 * @param expiresAt   ISO-8601 UTC 过期时间，例如 {@code 2026-11-21T08:00:00Z}
 */
public record LoginResponse(String accessToken, String tokenType, String expiresAt) {
}
