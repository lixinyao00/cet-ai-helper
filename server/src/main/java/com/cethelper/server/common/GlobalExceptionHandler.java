package com.cethelper.server.common;

import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 统一错误出口。
 *
 * <p>只输出稳定错误码和中文说明：不返回堆栈、不返回上游响应正文、不回显请求体，
 * 避免口令、完整 token 或模型密钥随响应泄漏。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApiException(ApiException ex) {
        return ResponseEntity.status(ex.getStatus()).body(new ApiError(ex.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(HttpMessageNotReadableException ex) {
        // 字段类型不符（例如把字符串字段写成数字或布尔值）单独给一条更明确的说明。
        if (ex.getMostSpecificCause() instanceof MismatchedInputException) {
            return ResponseEntity.badRequest()
                    .body(new ApiError("INVALID_INPUT", "请求字段类型不正确：字符串字段请使用 JSON 字符串。"));
        }
        return ResponseEntity.badRequest()
                .body(new ApiError("INVALID_INPUT", "请求体不是合法的 JSON，或字段类型不正确。"));
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> handleBadParameter(Exception ex) {
        return ResponseEntity.badRequest()
                .body(new ApiError("INVALID_INPUT", "请求参数缺失或格式不正确。"));
    }

    /** 兜底的唯一约束保护：并发注册同一用户名时，数据库约束仍是最终防线。 */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrity(DataIntegrityViolationException ex) {
        // 只记录异常类型，绝不打印 SQL 与绑定值（其中可能含口令哈希）。
        log.warn("数据库约束冲突：{}", ex.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("USERNAME_TAKEN", "该用户名已被注册，请换一个。"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("NOT_FOUND", "接口不存在。"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(new ApiError("METHOD_NOT_ALLOWED", "该接口不支持此 HTTP 方法。"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("未处理异常：{} {} -> {}", request.getMethod(), request.getRequestURI(),
                ex.getClass().getName(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("INTERNAL_ERROR", "服务内部错误，请稍后重试。"));
    }
}
