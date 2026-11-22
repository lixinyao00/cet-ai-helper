package com.cethelper.server.dictionary;

import com.cethelper.server.common.ApiException;
import com.cethelper.server.config.CetProperties;
import com.cethelper.server.explain.DemoExplanationCatalog;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 词典服务：参数规范化/校验、demo 与 live 分流。
 *
 * <p>联想与本地命中永远只读内置词表；只有 live 模式且本地未收录时才调用模型。</p>
 */
@Service
public class DictionaryService {

    private static final Logger log = LoggerFactory.getLogger(DictionaryService.class);

    /** 允许字母、撇号、空格与连字符（小写后校验）。 */
    private static final Pattern ALLOWED_INPUT = Pattern.compile("[a-z' -]+");

    private static final int MAX_QUERY_LENGTH = 40;
    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 20;

    private final DemoExplanationCatalog catalog;
    private final LiveDictionaryClient liveClient;
    private final boolean liveMode;

    public DictionaryService(DemoExplanationCatalog catalog,
                             ObjectProvider<LiveDictionaryClient> liveClientProvider,
                             CetProperties properties) {
        CetProperties.Explanation config = properties.getExplanation();
        this.liveMode = config.isLive();
        this.catalog = catalog;
        this.liveClient = liveClientProvider.getIfAvailable();
        if (liveMode && liveClient == null) {
            throw new IllegalStateException("live 模式未初始化在线词典客户端，请检查 CET_MODEL_* 配置。");
        }
        log.info("词典模式：{}", liveMode ? "live（在线模型兜底）" : "demo（内置词表）");
    }

    public List<DictionarySuggestion> suggest(String rawQuery, String rawLevel, int limit) {
        String query = normalizeQuery(rawQuery);
        String level = normalizeLevel(rawLevel);
        int effectiveLimit = normalizeLimit(limit);
        return catalog.suggest(query, level, effectiveLimit).stream()
                .map(DictionarySuggestion::from)
                .toList();
    }

    public DictionaryEntryResponse lookup(DictionaryLookupRequest request) {
        if (request == null) {
            throw ApiException.invalidInput("请输入要查询的单词。");
        }
        String word = normalizeQuery(request.word());
        Optional<DemoExplanationCatalog.WordEntry> local = catalog.findWord(word);
        if (local.isPresent()) {
            return DictionaryEntryResponse.from(word, local.get(), liveMode ? "live" : "demo");
        }
        if (!liveMode) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DEMO_UNSUPPORTED",
                    "演示模式暂未收录该词，请换一个四六级常见词。");
        }
        return liveClient.lookup(word);
    }

    private static String normalizeQuery(String raw) {
        if (raw == null) {
            throw ApiException.invalidInput("请输入要查询的单词。");
        }
        String normalized = raw.strip().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw ApiException.invalidInput("请输入要查询的单词。");
        }
        if (normalized.length() > MAX_QUERY_LENGTH) {
            throw ApiException.invalidInput("查询内容过长，请控制在 40 个字符以内。");
        }
        if (!ALLOWED_INPUT.matcher(normalized).matches()) {
            throw ApiException.invalidInput("查询内容只能包含英文字母、空格、撇号或连字符。");
        }
        return normalized;
    }

    private static String normalizeLevel(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String level = raw.strip().toUpperCase(Locale.ROOT);
        if (!level.equals("CET-4") && !level.equals("CET-6")) {
            throw ApiException.invalidInput("level 只能为 CET-4 或 CET-6。");
        }
        return level;
    }

    private static int normalizeLimit(int limit) {
        if (limit < 1) {
            throw ApiException.invalidInput("limit 必须大于 0。");
        }
        return Math.min(limit, MAX_LIMIT);
    }

    /** 供测试/文档核对默认值。 */
    static int defaultLimit() {
        return DEFAULT_LIMIT;
    }
}
