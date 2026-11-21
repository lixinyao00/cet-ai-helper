package com.cethelper.server.translation;

import java.util.List;

/**
 * 批改成功响应。
 *
 * @param sourceText          中文原文（去掉首尾空白后与请求一致）
 * @param studentTranslation  学生译文（去掉首尾空白后与请求一致）
 * @param overallFeedback     总体反馈（中文）；意思准确、表达自然的译文允许只给肯定反馈
 * @param issues              具体修改项；没有任何问题时为空数组（不是 null）
 * @param mode                {@code demo} 或 {@code live}
 */
public record TranslationReviewResponse(String sourceText,
                                        String studentTranslation,
                                        String overallFeedback,
                                        List<ReviewIssue> issues,
                                        String mode) {

    public TranslationReviewResponse {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }
}
