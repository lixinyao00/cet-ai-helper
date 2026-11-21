package com.cethelper.server.writing;

import java.util.Locale;

/** HTTP contracts for following up on the exercise in a writing review. */
public final class WritingPractice {

    private WritingPractice() {
    }

    public record OriginalIssue(String type, String quote, String detail, String suggestion) {
        static OriginalIssue from(WritingReviewResponse.Issue issue) {
            return new OriginalIssue(issue.type().name(), issue.quote(), issue.detail(), issue.suggestion());
        }
    }

    public record Task(String instruction, String item) {
        boolean repeats(Task original) {
            return comparableItem(item).contains(comparableItem(original.item()));
        }

        private static String comparableItem(String text) {
            return text.strip().replaceAll("(?U)\\s+", " ").toLowerCase(Locale.ROOT);
        }
    }

    public record Context(OriginalIssue issue, Task exercise) {
    }

    public record ExerciseAttempt(Context context, String answer) {
    }

    public record RetestAttempt(Context context, Task task, String answer) {
    }

    public enum ExerciseVerdict {
        CORRECT, NEEDS_REVISION
    }

    public enum RetestVerdict {
        TARGET_USED, SAME_ERROR, INSUFFICIENT_EVIDENCE
    }

    public record ExerciseFeedback(OriginalIssue issue, String answer, ExerciseVerdict verdict,
                                   String feedback, String nextStep, String mode) {
    }

    public record Retest(OriginalIssue issue, Task task, String mode) {
    }

    public record RetestFeedback(OriginalIssue issue, String answer, RetestVerdict verdict, String quote,
                                 String feedback, String nextStep, String mode) {
    }
}
