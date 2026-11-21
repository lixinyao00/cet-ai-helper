package com.cethelper.android;

import android.content.Context;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import java.net.SocketTimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;

final class WritingFollowUpPanel {
    private final WritingFollowUp state;
    private final Context context;
    private final Consumer<View> scrollTo;
    private final Button requestRetest;
    private final TextView retestStatus;
    private final View retestSection;
    private final AnswerPanel exercise;
    private final AnswerPanel retest;
    private WritingFollowUp.Task displayedTask;

    WritingFollowUpPanel(View section, WritingFollowUp state, Consumer<View> scrollTo) {
        this.state = state;
        this.scrollTo = scrollTo;
        context = section.getContext();
        WritingFollowUp.Task original = state.context.exercise();
        section.<TextView>findViewById(R.id.writing_exercise_instruction).setText(original.instruction());
        section.<TextView>findViewById(R.id.writing_exercise_item).setText(original.item());

        requestRetest = section.findViewById(R.id.writing_request_retest);
        retestStatus = section.findViewById(R.id.writing_retest_status);
        retestSection = section.findViewById(R.id.writing_retest);
        exercise = new AnswerPanel(section.findViewById(R.id.writing_exercise_answer),
                section.findViewById(R.id.writing_exercise_submit),
                section.findViewById(R.id.writing_exercise_result), state.exercise,
                state::submitExercise, () -> false);
        retest = new AnswerPanel(section.findViewById(R.id.writing_retest_answer),
                section.findViewById(R.id.writing_retest_submit),
                section.findViewById(R.id.writing_retest_result), state.retest,
                state::submitRetest, () -> state.retestTask == null || state.taskRequest.pending());
        requestRetest.setOnClickListener(v -> {
            if (!state.taskRequest.pending()) {
                hideKeyboard(v);
                state.requestRetest();
                render();
                scrollTo.accept(retestStatus);
            }
        });
    }

    boolean handleUnauthorized(Predicate<Exception> unauthorized) {
        return unauthorized.test(state.exercise.request.error)
                || unauthorized.test(state.taskRequest.error)
                || unauthorized.test(state.retest.request.error);
    }

    void render() {
        exercise.render();
        requestRetest.setEnabled(!state.taskRequest.pending());
        showStatus(retestStatus, state.taskRequest, R.string.writing_retest_pending);
        if (displayedTask != state.retestTask) {
            displayedTask = state.retestTask;
            retestSection.setVisibility(displayedTask == null ? View.GONE : View.VISIBLE);
            if (displayedTask != null) {
                retestSection.<TextView>findViewById(R.id.writing_retest_instruction).setText(displayedTask.instruction());
                retestSection.<TextView>findViewById(R.id.writing_retest_item).setText(displayedTask.item());
            }
            if (!state.retest.input.contentEquals(retest.answer.getText())) {
                retest.answer.setText(state.retest.input);
            }
        }
        retest.render();
    }

    private final class AnswerPanel {
        private final EditText answer;
        private final Button submit;
        private final View result;
        private final TextView status;
        private final WritingFollowUp.Attempt attempt;
        private final BooleanSupplier blocked;
        private WritingFollowUp.Assessment displayed;

        AnswerPanel(EditText answer, Button submit, View result, WritingFollowUp.Attempt attempt,
                    Consumer<String> grade, BooleanSupplier blocked) {
            this.answer = answer;
            this.submit = submit;
            this.result = result;
            this.attempt = attempt;
            this.blocked = blocked;
            status = result.findViewById(R.id.writing_answer_status);
            WritingScreen.bindInput(answer, attempt.input, text -> {
                attempt.input = text;
                updateSubmitButton();
            });
            submit.setOnClickListener(v -> {
                String input = ReadingArticle.trimSpace(attempt.input);
                if (blocked.getAsBoolean() || attempt.pendingAnswer(input)) {
                    return;
                }
                if (input.isEmpty() || input.length() > WritingFollowUp.MAX_ANSWER) {
                    String label = context.getString(R.string.writing_answer_english);
                    answer.requestFocus();
                    answer.setError(input.isEmpty() ? context.getString(R.string.writing_required, label)
                            : context.getString(R.string.writing_limit, label, WritingFollowUp.MAX_ANSWER));
                    scrollTo.accept(answer);
                    return;
                }
                hideKeyboard(answer);
                answer.clearFocus();
                grade.accept(input);
                render();
                scrollTo.accept(status);
            });
        }

        private void updateSubmitButton() {
            submit.setEnabled(!blocked.getAsBoolean() && !attempt.pendingAnswer(ReadingArticle.trimSpace(attempt.input)));
        }

        void render() {
            updateSubmitButton();
            showStatus(status, attempt.request, R.string.writing_answer_pending);
            if (displayed != attempt.assessment) {
                displayed = attempt.assessment;
                View content = result.findViewById(R.id.writing_assessment_content);
                content.setVisibility(displayed == null ? View.GONE : View.VISIBLE);
                if (displayed != null) {
                    content.<TextView>findViewById(R.id.writing_answer_verdict).setText(displayed.verdict().label);
                    content.<TextView>findViewById(R.id.writing_answer_snapshot)
                            .setText(ReviewQuote.highlight(context, displayed.answer(), displayed.matches()));
                    TextView candidates = content.findViewById(R.id.writing_answer_candidates);
                    candidates.setVisibility(displayed.matches().size() > 1 ? View.VISIBLE : View.GONE);
                    candidates.setText(context.getString(R.string.review_issue_candidates, displayed.matches().size()));
                    content.<TextView>findViewById(R.id.writing_answer_feedback).setText(displayed.feedback());
                    content.<TextView>findViewById(R.id.writing_answer_next_step)
                            .setText(context.getString(R.string.writing_answer_next_step, displayed.nextStep()));
                }
            }
        }
    }

    private void showStatus(TextView status, PracticeRequest<?> request, int pendingLabel) {
        Exception error = request.error;
        String message = null;
        if (request.pending()) {
            message = context.getString(pendingLabel);
        } else if (error instanceof ApiClient.Failure http && http.status == 422 && "DEMO_UNSUPPORTED".equals(http.code)) {
            message = context.getString(R.string.writing_follow_up_unsupported, http.getMessage());
        } else if (error instanceof SocketTimeoutException
                || error instanceof ApiClient.Failure http && http.status == 504) {
            message = context.getString(R.string.writing_follow_up_timeout);
        } else if (error != null) {
            message = MainActivity.failureMessage(error);
        }
        status.setText(message);
        status.setVisibility(message == null ? View.GONE : View.VISIBLE);
    }

    private void hideKeyboard(View view) {
        context.getSystemService(InputMethodManager.class).hideSoftInputFromWindow(view.getWindowToken(), 0);
    }
}
