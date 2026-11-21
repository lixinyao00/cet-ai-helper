package com.cethelper.server.translation;

/**
 * 汉译英提示与批改结果不符合约定时抛出的内部异常。
 *
 * <p>只在 {@code translation} 包内部流转：demo 数据写错时让服务启动失败；
 * live 模式下由 {@link LiveTranslationClient} 统一转成 502 {@code MODEL_ERROR}，
 * 不把模型原始输出或具体原因带给客户端。</p>
 */
class TranslationFormatException extends RuntimeException {

    TranslationFormatException(String message) {
        super(message);
    }
}
