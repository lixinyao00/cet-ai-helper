package com.cethelper.server.sentence;

/**
 * 拆解结果不符合约定时抛出的内部异常。
 *
 * <p>只在 {@code sentence} 包内部流转：demo 数据写错时让服务启动失败，
 * live 模式下由 {@link LiveSentenceAnalysisClient} 统一转成 502 {@code MODEL_ERROR}，
 * 不会把具体原因（尤其是模型原始输出）带给客户端。</p>
 */
class SentenceAnalysisFormatException extends RuntimeException {

    SentenceAnalysisFormatException(String message) {
        super(message);
    }
}
