package com.cethelper.server.auth;

/** 登录请求体。 */
public record LoginRequest(String username, String password) {
}
