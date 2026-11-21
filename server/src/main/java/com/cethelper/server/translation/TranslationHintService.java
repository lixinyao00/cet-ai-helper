package com.cethelper.server.translation;

import com.cethelper.server.common.ApiException;
import com.cethelper.server.config.CetProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 汉译英提示服务：demo 走内置样例，live 走在线模型。
 *
 * <p>先做输入校验（400），再按模式分流：demo 未收录返回 422，live 由模型客户端抛 502/504。
 * 模式与模型配置沿用 {@code cet.explanation.*}，与语境释义、整句分析共用一份开关。</p>
 */
@Service
public class TranslationHintService {

    private static final Logger log = LoggerFactory.getLogger(TranslationHintService.class);

    private final DemoTranslationCatalog demoCatalog;
    private final LiveTranslationClient liveClient;
    private final boolean liveMode;

    public TranslationHintService(DemoTranslationCatalog demoCatalog,
                                  ObjectProvider<LiveTranslationClient> liveClientProvider,
                                  CetProperties properties) {
        CetProperties.Explanation config = properties.getExplanation();
        if (!config.isDemo() && !config.isLive()) {
            throw new IllegalStateException("cet.explanation.mode 只能为 demo 或 live，当前值为：" + config.getMode());
        }
        this.liveMode = config.isLive();
        this.demoCatalog = demoCatalog;
        this.liveClient = liveClientProvider.getIfAvailable();
        if (liveMode && liveClient == null) {
            throw new IllegalStateException("live 模式未初始化在线翻译客户端，请检查 CET_MODEL_* 配置。");
        }
        log.info("汉译英提示模式：{}", liveMode ? "live（在线模型）" : "demo（内置样例）");
    }

    public TranslationHintResponse hint(String sourceText, HintType hintType) {
        String normalizedSource = sourceText == null ? null : sourceText.strip();
        validateSource(normalizedSource);
        if (liveMode) {
            return liveClient.hint(normalizedSource, hintType);
        }
        return demoCatalog.lookupHint(normalizedSource, hintType)
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DEMO_UNSUPPORTED",
                        "演示模式暂未收录这段中文材料，可切换 live 模式。"));
    }

    /** 中文原文是必填字段，不能为空或超长。 */
    private static void validateSource(String sourceText) {
        if (sourceText == null || sourceText.isBlank()) {
            throw ApiException.invalidInput("请输入中文原文。");
        }
        if (sourceText.length() > TranslationRules.MAX_SOURCE_LENGTH) {
            throw ApiException.invalidInput("中文原文过长：最多 2000 个字符，请精简后再试。");
        }
    }
}
