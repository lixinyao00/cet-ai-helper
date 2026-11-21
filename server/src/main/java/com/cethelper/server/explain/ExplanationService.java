package com.cethelper.server.explain;

import com.cethelper.server.common.ApiException;
import com.cethelper.server.config.CetProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 语境释义服务：demo 走内置样例，live 走在线模型。
 *
 * <p>先做输入校验（400），再按模式分流：demo 未收录返回 422，live 由模型客户端抛 502/504。</p>
 */
@Service
public class ExplanationService {

    private static final Logger log = LoggerFactory.getLogger(ExplanationService.class);

    private static final int MAX_SELECTION_LENGTH = 200;
    private static final int MAX_SENTENCE_LENGTH = 2000;

    private final DemoExplanationCatalog demoCatalog;
    private final LiveModelClient liveModelClient;
    private final boolean liveMode;

    public ExplanationService(DemoExplanationCatalog demoCatalog,
                              ObjectProvider<LiveModelClient> liveModelClientProvider,
                              CetProperties properties) {
        CetProperties.Explanation config = properties.getExplanation();
        if (!config.isDemo() && !config.isLive()) {
            throw new IllegalStateException("cet.explanation.mode 只能为 demo 或 live，当前值为：" + config.getMode());
        }
        this.liveMode = config.isLive();
        this.demoCatalog = demoCatalog;
        this.liveModelClient = liveModelClientProvider.getIfAvailable();
        if (liveMode && liveModelClient == null) {
            throw new IllegalStateException("live 模式未初始化在线释义客户端，请检查 CET_MODEL_* 配置。");
        }
        log.info("语境释义模式：{}", liveMode ? "live（在线模型）" : "demo（内置样例）");
    }

    public ExplanationResponse explain(String selection, String sentence) {
        // 统一去掉首尾空白（含全角空格与制表符），内部空格原样保留；
        // 非空、长度与连续子串校验以及后续的 demo/live 处理都用这份规范化后的输入。
        String normalizedSelection = selection == null ? null : selection.strip();
        String normalizedSentence = sentence == null ? null : sentence.strip();
        validate(normalizedSelection, normalizedSentence);
        if (liveMode) {
            return liveModelClient.explain(normalizedSelection, normalizedSentence);
        }
        return demoCatalog.lookup(normalizedSelection, normalizedSentence)
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DEMO_UNSUPPORTED",
                        "演示模式暂未收录该选词，可切换 live 模式查询。"));
    }

    /** 选词必须是原句中的连续原文，字段不得缺失或空白。 */
    private static void validate(String selection, String sentence) {
        if (selection == null || selection.isBlank() || sentence == null || sentence.isBlank()) {
            throw ApiException.invalidInput("请输入选词及其所在原句。");
        }
        if (selection.length() > MAX_SELECTION_LENGTH) {
            throw ApiException.invalidInput("选词过长，请重新选择。");
        }
        if (sentence.length() > MAX_SENTENCE_LENGTH) {
            throw ApiException.invalidInput("原句过长，请重新选择。");
        }
        if (!sentence.contains(selection)) {
            throw ApiException.invalidInput("选词必须是原句中的连续原文。");
        }
    }
}
