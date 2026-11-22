package com.cethelper.server.tutor;

import com.cethelper.server.common.ApiError;
import com.cethelper.server.common.ApiException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AI 助教流式接口。
 *
 * <p>方法声明 {@code produces = text/event-stream}，但同步校验失败时需要返回客户端可读的
 * 400 JSON {@link ApiError}。因此在控制器内部捕获 {@link ApiException} 并显式指定
 * {@code application/json}，避免 {@code HttpMediaTypeNotAcceptableException}。</p>
 */
@RestController
@RequestMapping("/api/v1")
public class TutorController {

    private final TutorService tutorService;

    public TutorController(TutorService tutorService) {
        this.tutorService = tutorService;
    }

    @PostMapping(value = "/tutor/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<?> stream(@RequestBody(required = false) TutorChatRequest request) {
        TutorContextPayload context;
        try {
            context = tutorService.validate(request);
        } catch (ApiException ex) {
            return ResponseEntity.status(ex.getStatus())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ApiError(ex.getCode(), ex.getMessage()));
        }
        SseEmitter emitter = tutorService.stream(request.messages(), context);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(emitter);
    }
}
