package com.cethelper.server.sentence;

import com.cethelper.server.common.ApiException;
import com.cethelper.server.config.CetProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 整句分析服务：demo 走内置样例，live 走在线模型。
 *
 * <p>先做输入校验（400），再按模式分流：demo 未收录返回 422，live 由模型客户端抛 502/504。
 * 模式与模型配置沿用 {@code cet.explanation.*}，与语境释义共用一份开关。</p>
 */
@Service
public class SentenceAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(SentenceAnalysisService.class);

    private static final int MAX_SENTENCE_LENGTH = 2000;

    private final DemoSentenceCatalog demoCatalog;
    private final LiveSentenceAnalysisClient liveClient;
    private final boolean liveMode;

    public SentenceAnalysisService(DemoSentenceCatalog demoCatalog,
                                   ObjectProvider<LiveSentenceAnalysisClient> liveClientProvider,
                                   CetProperties properties) {
        CetProperties.Explanation config = properties.getExplanation();
        if (!config.isDemo() && !config.isLive()) {
            throw new IllegalStateException("cet.explanation.mode 只能为 demo 或 live，当前值为：" + config.getMode());
        }
        this.liveMode = config.isLive();
        this.demoCatalog = demoCatalog;
        this.liveClient = liveClientProvider.getIfAvailable();
        if (liveMode && liveClient == null) {
            throw new IllegalStateException("live 模式未初始化在线整句分析客户端，请检查 CET_MODEL_* 配置。");
        }
        log.info("整句分析模式：{}", liveMode ? "live（在线模型）" : "demo（内置样例）");
    }

    public SentenceAnalysisResponse analyze(String sentence) {
        // 首尾空白（含全角空格与制表符）先去再校验；句内空格原样保留，
        // demo 与 live 使用同一份文本，片段定位也基于这份文本。
        String normalizedSentence = sentence == null ? null : sentence.strip();
        validate(normalizedSentence);
        if (liveMode) {
            return liveClient.analyze(normalizedSentence);
        }
        return demoCatalog.lookup(normalizedSentence)
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DEMO_UNSUPPORTED",
                        "演示模式暂未收录该句子，可切换 live 模式分析。"));
    }

    /** 原句是唯一必填字段，不能为空或超长。 */
    private static void validate(String sentence) {
        if (sentence == null || sentence.isBlank()) {
            throw ApiException.invalidInput("请输入要分析的英文原句。");
        }
        if (sentence.length() > MAX_SENTENCE_LENGTH) {
            throw ApiException.invalidInput("原句过长：最多 2000 个字符，请精简后再试。");
        }
    }
}
