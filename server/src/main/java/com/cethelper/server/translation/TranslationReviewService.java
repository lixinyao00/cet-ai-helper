package com.cethelper.server.translation;

import com.cethelper.server.common.ApiException;
import com.cethelper.server.config.CetProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 汉译英批改服务：demo 走内置样例，live 走在线模型。
 *
 * <p>先做输入校验（400），再按模式分流：demo 未收录该「原文 + 译文」组合返回 422，
 * live 由模型客户端抛 502/504。批改引用由 {@link TranslationRules#requireIssues} 统一核对，
 * 引用必须逐字出现在对应输入里。</p>
 */
@Service
public class TranslationReviewService {

    private static final Logger log = LoggerFactory.getLogger(TranslationReviewService.class);

    private final DemoTranslationCatalog demoCatalog;
    private final LiveTranslationClient liveClient;
    private final boolean liveMode;

    public TranslationReviewService(DemoTranslationCatalog demoCatalog,
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
        log.info("汉译英批改模式：{}", liveMode ? "live（在线模型）" : "demo（内置样例）");
    }

    public TranslationReviewResponse review(String sourceText, String studentTranslation) {
        String normalizedSource = sourceText == null ? null : sourceText.strip();
        String normalizedTranslation = studentTranslation == null ? null : studentTranslation.strip();
        validate(normalizedSource, normalizedTranslation);
        if (liveMode) {
            return liveClient.review(normalizedSource, normalizedTranslation);
        }
        return demoCatalog.lookupReview(normalizedSource, normalizedTranslation)
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DEMO_UNSUPPORTED",
                        "演示模式暂未收录这份译文，可切换 live 模式批改。"));
    }

    /** 原文与译文都必填，且各自有长度上限。 */
    private static void validate(String sourceText, String studentTranslation) {
        if (sourceText == null || sourceText.isBlank()) {
            throw ApiException.invalidInput("请输入中文原文。");
        }
        if (sourceText.length() > TranslationRules.MAX_SOURCE_LENGTH) {
            throw ApiException.invalidInput("中文原文过长：最多 2000 个字符，请精简后再试。");
        }
        if (studentTranslation == null || studentTranslation.isBlank()) {
            throw ApiException.invalidInput("请输入你的英文译文。");
        }
        if (studentTranslation.length() > TranslationRules.MAX_TRANSLATION_LENGTH) {
            throw ApiException.invalidInput("英文译文过长：最多 3000 个字符，请精简后再试。");
        }
    }
}
