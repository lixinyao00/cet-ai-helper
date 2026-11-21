package com.cethelper.server.common;

import org.springframework.http.HttpStatus;

/** 带 HTTP 状态与稳定错误码的业务异常，由 {@link GlobalExceptionHandler} 统一转成 {@link ApiError}。 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public static ApiException invalidInput(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", message);
    }

    public static ApiException unauthorized() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "请先登录：缺少或无效的访问凭证。");
    }
}
