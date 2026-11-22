package com.cethelper.server.ai;

import com.cethelper.server.common.ApiException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import org.springframework.http.HttpStatus;

/** Spring AI 相关错误映射的公共工具：稳定的错误码、超时根因识别与代码块剥离。 */
public final class AiFailures {

    private AiFailures() {
    }

    public static ApiException modelError(String message) {
        return new ApiException(HttpStatus.BAD_GATEWAY, "MODEL_ERROR", message);
    }

    public static ApiException modelTimeout(String message) {
        return new ApiException(HttpStatus.GATEWAY_TIMEOUT, "MODEL_TIMEOUT", message);
    }

    /** 遍历异常链识别连接/读超时；Reactor 的 TimeoutException 与 JDK/Netty 超时都算。 */
    public static boolean isTimeout(Throwable throwable) {
        Throwable current = throwable;
        for (int depth = 0; current != null && depth < 12; depth++) {
            if (current instanceof HttpTimeoutException
                    || current instanceof SocketTimeoutException
                    || current instanceof InterruptedIOException
                    || current instanceof java.util.concurrent.TimeoutException) {
                return true;
            }
            if (current == current.getCause()) {
                break;
            }
            current = current.getCause();
        }
        return false;
    }

    /** 兼容模型把 JSON 包在 ```json 代码块里的情况。 */
    public static String stripCodeFence(String content) {
        String text = content == null ? "" : content.trim();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            if (firstNewline >= 0) {
                text = text.substring(firstNewline + 1);
            }
            if (text.endsWith("```")) {
                text = text.substring(0, text.length() - 3);
            }
        }
        return text.trim();
    }
}
