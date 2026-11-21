package com.cethelper.server.writing;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * 写作批改成功响应。
 *
 * <p>原有六个字段保持不变；评分三字段为可选，在线模型没返回时不输出（{@code NON_NULL}）。</p>
 *
 * @param estimatedScore 四六级 15 分制预估分，没有时为空
 * @param scoreMax       评分满分，通常为 15，没有时为空
 * @param scoreBand      档位评语（中文），没有时为空
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WritingReviewResponse(String prompt, String essay, String overallFeedback,
                                    List<Issue> issues, Exercise exercise, String mode,
                                    Integer estimatedScore, Integer scoreMax, String scoreBand) {

    public WritingReviewResponse {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    /** 兼容旧调用的六参构造：评分字段留空。 */
    public WritingReviewResponse(String prompt, String essay, String overallFeedback,
                                 List<Issue> issues, Exercise exercise, String mode) {
        this(prompt, essay, overallFeedback, issues, exercise, mode, null, null, null);
    }

    public enum IssueType {
        GRAMMAR, WORD_CHOICE, MECHANICS, CLARITY
    }

    public record Issue(IssueType type, String quote, String detail, String suggestion) {
    }

    /** issueIndex 对应本次响应 issues 数组中的位置，从 0 开始。 */
    public record Exercise(int issueIndex, String instruction, String item) {
    }
}
