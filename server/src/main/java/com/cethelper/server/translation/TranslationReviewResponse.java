package com.cethelper.server.translation;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * 批改成功响应。
 *
 * <p>原有五个字段保持不变；评分与参考译文为可选字段，在线模型没返回时不输出（{@code NON_NULL}），
 * 旧版客户端不受影响。</p>
 *
 * @param sourceText           中文原文（去掉首尾空白后与请求一致）
 * @param studentTranslation   学生译文（去掉首尾空白后与请求一致）
 * @param overallFeedback      总体反馈（中文）；意思准确、表达自然的译文允许只给肯定反馈
 * @param issues               具体修改项；没有任何问题时为空数组（不是 null）
 * @param mode                 {@code demo} 或 {@code live}
 * @param estimatedScore       四六级 15 分制预估分，没有时为空
 * @param scoreMax             评分满分，通常为 15，没有时为空
 * @param scoreBand            档位评语（中文），没有时为空
 * @param referenceTranslation 参考译文，没有时为空
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TranslationReviewResponse(String sourceText,
                                        String studentTranslation,
                                        String overallFeedback,
                                        List<ReviewIssue> issues,
                                        String mode,
                                        Integer estimatedScore,
                                        Integer scoreMax,
                                        String scoreBand,
                                        String referenceTranslation) {

    public TranslationReviewResponse {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    /** 兼容旧调用的五参构造：评分与参考译文留空。 */
    public TranslationReviewResponse(String sourceText,
                                     String studentTranslation,
                                     String overallFeedback,
                                     List<ReviewIssue> issues,
                                     String mode) {
        this(sourceText, studentTranslation, overallFeedback, issues, mode, null, null, null, null);
    }
}
