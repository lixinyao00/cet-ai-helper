package com.cethelper.server.writing;

import com.cethelper.server.common.ApiException;
import com.cethelper.server.config.CetProperties;
import com.cethelper.server.writing.WritingPractice.Context;
import com.cethelper.server.writing.WritingPractice.ExerciseAttempt;
import com.cethelper.server.writing.WritingPractice.ExerciseFeedback;
import com.cethelper.server.writing.WritingPractice.OriginalIssue;
import com.cethelper.server.writing.WritingPractice.Retest;
import com.cethelper.server.writing.WritingPractice.RetestAttempt;
import com.cethelper.server.writing.WritingPractice.RetestFeedback;
import com.cethelper.server.writing.WritingPractice.Task;
import com.cethelper.server.writing.WritingReviewResponse.IssueType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class WritingPracticeService {

    private final LiveWritingPracticeClient liveClient;

    public WritingPracticeService(CetProperties properties, ObjectProvider<LiveWritingPracticeClient> liveClient) {
        this.liveClient = properties.getExplanation().isLive() ? liveClient.getObject() : null;
    }

    ExerciseFeedback gradeExercise(ExerciseAttempt attempt) {
        if (attempt == null) {
            throw ApiException.invalidInput("请提交原练习和你的答案。");
        }
        requireContext(attempt.context());
        ExerciseAttempt normalized = new ExerciseAttempt(attempt.context(), requireAnswer(attempt.answer()));
        return liveClient == null ? DemoWritingPractice.gradeExercise(normalized) : liveClient.gradeExercise(normalized);
    }

    Retest createRetest(Context context) {
        requireContext(context);
        return liveClient == null ? DemoWritingPractice.createRetest(context) : liveClient.createRetest(context);
    }

    RetestFeedback gradeRetest(RetestAttempt attempt) {
        if (attempt == null) {
            throw ApiException.invalidInput("请提交复测题和你的答案。");
        }
        requireContext(attempt.context());
        requireTask(attempt.task(), "复测");
        if (attempt.task().repeats(attempt.context().exercise())) {
            throw ApiException.invalidInput("复测题面需与原练习不同。");
        }
        RetestAttempt normalized = new RetestAttempt(attempt.context(), attempt.task(), requireAnswer(attempt.answer()));
        return liveClient == null ? DemoWritingPractice.gradeRetest(normalized) : liveClient.gradeRetest(normalized);
    }

    private static void requireContext(Context context) {
        if (context == null || context.issue() == null) {
            throw ApiException.invalidInput("请提交原批改中与练习关联的问题。");
        }
        OriginalIssue issue = context.issue();
        requireText(issue.type(), 24, "原问题类别");
        try {
            IssueType.valueOf(issue.type());
        } catch (IllegalArgumentException ex) {
            throw ApiException.invalidInput("原问题类别不正确。");
        }
        requireText(issue.quote(), 240, "原问题引用");
        requireText(issue.detail(), 400, "原问题说明");
        requireText(issue.suggestion(), 400, "原问题修改建议");
        requireTask(context.exercise(), "原练习");
    }

    private static void requireTask(Task task, String label) {
        if (task == null) {
            throw ApiException.invalidInput("请提交" + label + "题面和作答指令。");
        }
        requireText(task.instruction(), 400, label + "作答指令");
        requireText(task.item(), 400, label + "题面");
    }

    private static String requireAnswer(String answer) {
        String normalized = answer == null ? null : answer.strip();
        requireText(normalized, 2000, "英文作答");
        return normalized;
    }

    private static void requireText(String value, int maxLength, String label) {
        if (value == null || value.isBlank()) {
            throw ApiException.invalidInput("请输入" + label + "。");
        }
        if (value.length() > maxLength) {
            throw ApiException.invalidInput(label + "过长：最多 " + maxLength + " 个字符，请精简后再试。");
        }
    }
}
