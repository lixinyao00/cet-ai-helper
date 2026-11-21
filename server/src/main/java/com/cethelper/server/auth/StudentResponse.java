package com.cethelper.server.auth;

/** 学生公开信息，注册与 /api/v1/me 使用同一结构。 */
public record StudentResponse(long id, String username) {
}
