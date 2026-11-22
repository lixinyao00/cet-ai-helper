package com.cethelper.server.article;

import com.cethelper.server.common.ApiException;
import com.cethelper.server.config.CetProperties;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 文章整理服务：demo 走本地确定性清理，live 走在线多模态模型。
 *
 * <p>先做输入校验与图片 Base64 规范化，再按模式分流。错误响应只包含稳定错误码与中文说明，
 * 不回显请求内容、图片数据或模型密钥。</p>
 */
@Service
public class ArticleOrganizeService {

    private static final Logger log = LoggerFactory.getLogger(ArticleOrganizeService.class);

    private static final int MAX_RAW_TEXT_LENGTH = 50_000;
    private static final int MAX_IMAGE_BASE64_LENGTH = 8_000_000;
    private static final String DEFAULT_IMAGE_MIME_TYPE = "image/jpeg";
    private static final Set<String> ALLOWED_IMAGE_MIME_TYPES =
            Set.of("image/jpeg", "image/png", "image/webp");
    private static final Pattern DATA_URL = Pattern.compile("^data:([^;,]+);base64,(.*)$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final String DEMO_SUMMARY = "演示模式：已按本地规则合并断行并过滤常见干扰行";

    private final LiveArticleOrganizeClient liveClient;
    private final boolean liveMode;

    public ArticleOrganizeService(ObjectProvider<LiveArticleOrganizeClient> liveClientProvider,
                                  CetProperties properties) {
        CetProperties.Explanation config = properties.getExplanation();
        if (!config.isDemo() && !config.isLive()) {
            throw new IllegalStateException("cet.explanation.mode 只能为 demo 或 live，当前值为：" + config.getMode());
        }
        this.liveMode = config.isLive();
        this.liveClient = liveClientProvider.getIfAvailable();
        if (liveMode && liveClient == null) {
            throw new IllegalStateException("live 模式未初始化在线文章整理客户端，请检查 CET_MODEL_* 配置。");
        }
        log.info("文章整理模式：{}", liveMode ? "live（在线模型）" : "demo（本地规则）");
    }

    public ArticleOrganizeResponse organize(ArticleOrganizeRequest request) {
        if (request == null) {
            throw ApiException.invalidInput("请提供待整理的图片或识别文本。");
        }

        String target = normalizeTarget(request.target());
        String rawText = request.rawText() == null ? "" : request.rawText().strip();
        String imageBase64 = request.imageBase64() == null ? "" : request.imageBase64().strip();
        boolean hasText = !rawText.isBlank();
        boolean hasImage = !imageBase64.isBlank();

        if (!hasText && !hasImage) {
            throw ApiException.invalidInput("请提供待整理的图片或识别文本。");
        }
        if (hasText && rawText.length() > MAX_RAW_TEXT_LENGTH) {
            throw ApiException.invalidInput("识别文本过长：最多 50000 个字符，请精简后再试。");
        }

        String imageDataUrl = hasImage ? parseImageDataUrl(imageBase64, request.imageMimeType()) : null;

        if (liveMode) {
            return liveClient.organize(hasText ? rawText : null, imageDataUrl, target);
        }

        if (!hasText) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DEMO_UNSUPPORTED",
                    "演示模式不支持直接识图整理，请先在端侧识别文字后再整理。");
        }
        ArticleCleanupRules.CleanedArticle cleaned = ArticleCleanupRules.clean(rawText, target);
        return new ArticleOrganizeResponse(cleaned.title(), cleaned.body(), DEMO_SUMMARY, "demo");
    }

    private static String normalizeTarget(String target) {
        if (target == null || target.isBlank()) {
            return "reading";
        }
        String normalized = target.strip().toLowerCase(Locale.ROOT);
        if (!"reading".equals(normalized) && !"translation".equals(normalized)) {
            throw ApiException.invalidInput("整理类型仅支持 reading 或 translation。");
        }
        return normalized;
    }

    /** 接受裸 Base64 或 data URL，统一转成 {@code data:<mime>;base64,<payload>}。 */
    private static String parseImageDataUrl(String rawValue, String requestedMimeType) {
        if (rawValue.length() > MAX_IMAGE_BASE64_LENGTH) {
            throw ApiException.invalidInput("图片数据过大：Base64 编码最多 8000000 个字符。");
        }

        String payload = rawValue;
        String dataUrlMimeType = null;
        if (rawValue.regionMatches(true, 0, "data:", 0, 5)) {
            Matcher matcher = DATA_URL.matcher(rawValue);
            if (!matcher.matches()) {
                throw ApiException.invalidInput("图片数据格式不正确，请重新选择图片。");
            }
            dataUrlMimeType = matcher.group(1);
            payload = matcher.group(2);
        }
        payload = payload.replaceAll("\\s+", "");
        if (payload.isEmpty()) {
            throw ApiException.invalidInput("图片数据为空，请重新选择图片。");
        }

        String mimeType = blankToNull(requestedMimeType);
        if (mimeType == null) {
            mimeType = blankToNull(dataUrlMimeType);
        }
        if (mimeType == null) {
            mimeType = DEFAULT_IMAGE_MIME_TYPE;
        }
        mimeType = mimeType.toLowerCase(Locale.ROOT);
        if (!ALLOWED_IMAGE_MIME_TYPES.contains(mimeType)) {
            throw ApiException.invalidInput("图片格式仅支持 image/jpeg、image/png 或 image/webp。");
        }

        try {
            byte[] decoded = Base64.getDecoder().decode(payload);
            if (decoded.length == 0) {
                throw ApiException.invalidInput("图片数据为空，请重新选择图片。");
            }
        } catch (IllegalArgumentException ex) {
            throw ApiException.invalidInput("图片数据不是合法的 Base64，请重新选择图片。");
        }
        return "data:" + mimeType + ";base64," + payload;
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }
}
