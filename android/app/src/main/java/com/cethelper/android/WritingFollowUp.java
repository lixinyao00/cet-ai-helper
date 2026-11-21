package com.cethelper.android;

import java.util.List;
import java.util.concurrent.ExecutorService;

final class WritingFollowUp {
    static final int MAX_ANSWER = 2000;

    record Task(String instruction, String item) {}
    record Context(WritingFeedback.Issue issue, Task exercise) {}
    record Assessment(String answer, Verdict verdict, String feedback, String nextStep, List<ReviewQuote.Range> matches) {}

    enum Verdict {
        CORRECT(false, R.string.writing_answer_correct),
        NEEDS_REVISION(false, R.string.writing_answer_needs_revision),
        TARGET_USED(true, R.string.writing_answer_target_used),
        SAME_ERROR(true, R.string.writing_answer_same_error),
        INSUFFICIENT_EVIDENCE(true, R.string.writing_answer_insufficient);

        final boolean retest;
        final int label;

        Verdict(boolean retest, int label) {
            this.retest = retest;
            this.label = label;
        }
    }

    static final class Attempt {
        final PracticeRequest<Assessment> request;
        String input = "";
        String submitted;
        Assessment assessment;

        Attempt(ExecutorService executor, Runnable onChanged) {
            request = new PracticeRequest<>(executor, completed -> {
                if (completed.value != null) {
                    assessment = completed.value;
                }
                onChanged.run();
            });
        }

        boolean pendingAnswer(String answer) {
            return request.pending() && answer.equals(submitted);
        }

        void submit(String answer, PracticeRequest.Fetch<Assessment> grade) {
            submitted = answer;
            request.start(grade);
        }

        void reset() {
            request.reset();
            input = "";
            submitted = null;
            assessment = null;
        }
    }

    private final ApiClient api;
    private final String token;
    final Context context;
    final Attempt exercise;
    final Attempt retest;
    final PracticeRequest<Task> taskRequest;
    Task retestTask;

    WritingFollowUp(ExecutorService executor, ApiClient api, String token, Context context, Runnable onChanged) {
        this.api = api;
        this.token = token;
        this.context = context;
        exercise = new Attempt(executor, onChanged);
        retest = new Attempt(executor, onChanged);
        taskRequest = new PracticeRequest<>(executor, completed -> {
            if (completed.value != null) {
                retest.reset();
                retestTask = completed.value;
            }
            onChanged.run();
        });
    }

    void submitExercise(String answer) {
        exercise.submit(answer, () -> api.gradeWritingExercise(token, context, answer));
    }

    void requestRetest() {
        taskRequest.start(() -> api.createWritingRetest(token, context));
    }

    void submitRetest(String answer) {
        Task task = retestTask;
        retest.submit(answer, () -> api.gradeWritingRetest(token, context, task, answer));
    }

    void cancel() {
        exercise.reset();
        retest.reset();
        taskRequest.reset();
        retestTask = null;
    }
}
