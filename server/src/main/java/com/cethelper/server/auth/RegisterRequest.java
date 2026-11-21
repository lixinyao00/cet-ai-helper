package com.cethelper.server.auth;

/** 注册请求体。字段校验在 {@link AuthService} 内完成，以便返回稳定的中文错误说明。 */
public record RegisterRequest(String username, String password) {
}
