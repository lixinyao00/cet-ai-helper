package com.cethelper.server.common;

/** 提供给客户端的稳定错误体，只含错误码与中文说明。 */
public record ApiError(String code, String message) {
}
