package com.cethelper.server.article;

import static com.cethelper.server.model.ChatCompletionsClient.modelError;

import com.cethelper.server.config.CetProperties;
import com.cethelper.server.model.ChatCompletionsClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * live 模式：调用兼容 OpenAI Chat Completions 的多模态服务整理文章。
 *
 * <p>图片与识别文本可同时提供：图片决定版面与段落，文本用于校对拼写。模型只做断行修复、
 * 连字符断词与明显 OCR 错字修正，不得改写原文。上游异常、返回不可解析或缺少正文都映射为 502，
 * 超时映射为 504；日志和响应都不包含模型密钥或上游正文。</p>
 */
@Component
@ConditionalOnProperty(prefix = "cet.explanation", name = "mode", havingValue = "live")
public class LiveArticleOrganizeClient {

    private static final Logger log = LoggerFactory.getLogger(LiveArticleOrganizeClient.class);

    private static final String READING_SYSTEM_PROMPT = """
            你是四六级英语阅读文章整理助手。用户会提供试卷照片和/或 OCR 识别文本。
            任务：只提取其中唯一一篇连贯的英语阅读理解正文；如果正文自带印刷标题，也一并保留。
            规则：
            1. 有图片时，直接依据图片的版面、分栏与段落阅读；若同时提供识别文本，用它校对拼写。
            2. 丢弃考试指令（Directions:、Part III、Section A/B/C 等）、题号与 A/B/C/D 选项、页眉页脚与页码、手写边注、中文批注，以及与本文无关的相邻文章残句。
            3. 严格忠实原文措辞：只修复断行、行尾连字符断词和明显的 OCR 错字；如遇到跨行连字符断词（如 mor-\\nning），去掉连字符并还原为完整单词（如 morning），但保留原本的复合词连字符（如 well-known）。绝不改写、润色、概括、翻译或编造句子。
            4. 用空行分隔自然段，段落之间使用 \\n\\n。
            5. title 使用原文印刷标题；没有印刷标题时，给出 3-8 个英文单词的简短概括标题。
            只输出严格 JSON：{"title":"...","body":"...","summary":"不超过40字的中文说明"}，不要输出代码块或额外文字。
            """;

    private static final String TRANSLATION_SYSTEM_PROMPT = """
            你是四六级翻译题原文整理助手。用户会提供试卷照片和/或 OCR 识别文本。
            任务：只提取待翻译的那一段原文，丢弃考试页眉、题号、选项、注释、手写边注与无关残句。
            规则：
            1. 有图片时，直接依据图片的版面与段落阅读；若同时提供识别文本，用它校对拼写。
            2. 严格忠实原文措辞：只修复断行、行尾连字符断词和明显的 OCR 错字；绝不改写、润色、概括或编造内容。
            3. 用空行分隔自然段，段落之间使用 \\n\\n。
            4. title 固定为 ""。
            只输出严格 JSON：{"title":"","body":"...","summary":"不超过40字的中文说明"}，不要输出代码块或额外文字。
            """;

    private static final int MAX_TOKENS = 4096;
    private static final String TIMEOUT_MESSAGE = "在线整理请求超时，请稍后重试。";
    private static final String ERROR_MESSAGE = "在线整理服务返回异常，请稍后重试。";

    private final ChatCompletionsClient chatClient;
    private final ObjectMapper objectMapper;
    private final Duration readTimeoutOverride;

    public LiveArticleOrganizeClient(ChatCompletionsClient chatClient,
                                     ObjectMapper objectMapper,
                                     CetProperties properties) {
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
        this.readTimeoutOverride = properties.getArticle().getReadTimeout();
    }

    /** 整理文章；上游失败或返回不符合约定 502，超时 504。 */
    public ArticleOrganizeResponse organize(String rawText, String imageDataUrl, String target) {
        boolean translation = "translation".equals(target);
        String systemPrompt = translation ? TRANSLATION_SYSTEM_PROMPT : READING_SYSTEM_PROMPT;
        String userText = buildUserText(rawText, imageDataUrl != null, translation);

        String content = chatClient.completeMultimodal(systemPrompt, userText, imageDataUrl, MAX_TOKENS,
                readTimeoutOverride, TIMEOUT_MESSAGE, ERROR_MESSAGE);
        return parse(content, translation);
    }

    private static String buildUserText(String rawText, boolean hasImage, boolean translation) {
        String label = translation ? "翻译原文" : "阅读材料";
        if (rawText != null && !rawText.isBlank()) {
            String suffix = hasImage ? "\n\n（另附同一材料的图片，请用图片校对版面与拼写。）" : "";
            return "请整理下面的" + label + "：\n\n" + rawText + suffix;
        }
        return "请直接阅读图片中的" + label + "并整理。";
    }

    private ArticleOrganizeResponse parse(String content, boolean translation) {
        JsonNode payload;
        try {
            payload = objectMapper.readTree(content);
        } catch (JsonProcessingException e) {
            log.warn("在线文章整理结果不是合法 JSON");
            throw modelError(ERROR_MESSAGE);
        }
        if (payload == null || !payload.isObject()) {
            log.warn("在线文章整理结果不是 JSON 对象");
            throw modelError(ERROR_MESSAGE);
        }

        String body = textField(payload, "body");
        if (body == null) {
            log.warn("在线文章整理结果缺少非空的 body");
            throw modelError(ERROR_MESSAGE);
        }
        body = ArticleCleanupRules.mergeWrappedLines(body, translation ? "translation" : "reading");
        if (body.isBlank()) {
            log.warn("在线文章整理结果清理后正文为空");
            throw modelError(ERROR_MESSAGE);
        }

        String title = translation ? "" : textOrEmpty(payload, "title");
        String summary = textField(payload, "summary");
        if (summary == null) {
            summary = translation ? "已提取翻译原文并整理分段。" : "已提取阅读正文并整理分段。";
        }
        return new ArticleOrganizeResponse(title, body, summary, "live");
    }

    private static String textOrEmpty(JsonNode payload, String field) {
        JsonNode node = payload.path(field);
        return node.isTextual() ? node.asText().strip() : "";
    }

    private static String textField(JsonNode payload, String field) {
        JsonNode node = payload.path(field);
        if (!node.isTextual() || node.asText().isBlank()) {
            return null;
        }
        return node.asText().strip();
    }
}
