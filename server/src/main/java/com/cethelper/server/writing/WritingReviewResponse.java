package com.cethelper.server.writing;

import java.util.List;

public record WritingReviewResponse(String prompt, String essay, String overallFeedback,
                                    List<Issue> issues, Exercise exercise, String mode) {

    public WritingReviewResponse {
        issues = List.copyOf(issues);
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
