package com.cethelper.android;

import android.graphics.Rect;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.view.OneShotPreDrawListener;

import java.net.SocketTimeoutException;
import java.util.function.Consumer;
import java.util.function.Predicate;

final class WritingScreen {
    private final MainActivity host;
    private final WritingPractice practice;
    private final ScrollView page;
    private final EditText prompt;
    private final EditText essay;
    private final Button submit;
    private final TextView requestStatus;
    private final View retry;
    private final View reviewPanel;
    private final View editedStatus;
    private final LinearLayout issues;
    private WritingFeedback displayed;
    private WritingFollowUpPanel followUpPanel;

    static void show(MainActivity host, WritingPractice practice, Predicate<Exception> unauthorized) {
        new WritingScreen(host, practice).bind(unauthorized);
    }

    private WritingScreen(MainActivity host, WritingPractice practice) {
        this.host = host;
        this.practice = practice;
        page = (ScrollView) host.show(R.layout.screen_writing, host::showHome);
        prompt = page.findViewById(R.id.writing_prompt);
        essay = page.findViewById(R.id.writing_essay);
        submit = page.findViewById(R.id.writing_submit);
        requestStatus = page.findViewById(R.id.writing_request_status);
        retry = page.findViewById(R.id.writing_retry);
        reviewPanel = page.findViewById(R.id.writing_review_panel);
        editedStatus = page.findViewById(R.id.writing_review_edited);
        issues = page.findViewById(R.id.writing_issues);
    }

    private void bind(Predicate<Exception> unauthorized) {
        bindInput(prompt, practice.prompt, input -> {
            if (host.isCurrent(page)) {
                practice.setPrompt(input);
                renderEditorState();
            }
        });
        bindInput(essay, practice.essay, input -> {
            if (host.isCurrent(page)) {
                practice.setEssay(input);
                renderEditorState();
            }
        });
        page.findViewById(R.id.writing_back).setOnClickListener(v -> host.showHome());
        page.findViewById(R.id.writing_demo).setOnClickListener(v -> {
            Runnable replace = () -> replaceInputs(WritingPractice.DEMO_PROMPT, WritingPractice.DEMO_ESSAY);
            if (practice.hasInput()) {
                host.confirmDraftChange(page, R.string.replace_practice, R.string.replace_practice_confirm,
                        R.string.replace_confirm, replace);
            } else {
                replace.run();
            }
        });
        page.findViewById(R.id.writing_clear).setOnClickListener(v -> {
            if (practice.hasInput()) {
                host.confirmDraftChange(page, R.string.clear_practice, R.string.clear_practice_confirm,
                        R.string.clear_confirm, () -> replaceInputs("", ""));
            }
        });
        submit.setOnClickListener(v -> submit());
        retry.setOnClickListener(v -> submit());
        page.findViewById(R.id.writing_edit_essay).setOnClickListener(v -> {
            essay.requestFocus();
            scrollTo(essay);
            host.getSystemService(InputMethodManager.class).showSoftInput(essay, InputMethodManager.SHOW_IMPLICIT);
        });
        practice.onChanged = () -> {
            if (!host.isCurrent(page)) {
                return;
            }
            boolean follow = displayed != practice.feedback && !prompt.hasFocus() && !essay.hasFocus();
            if (render(unauthorized) && follow) {
                scrollTo(reviewPanel);
            }
        };
        render(unauthorized);
    }

    private void replaceInputs(String prompt, String essay) {
        host.getSystemService(InputMethodManager.class).hideSoftInputFromWindow(page.getWindowToken(), 0);
        practice.replace(prompt, essay);
        host.showWriting();
    }

    private boolean render(Predicate<Exception> unauthorized) {
        if (unauthorized.test(practice.request.error)) {
            return false;
        }
        render();
        return followUpPanel == null || !followUpPanel.handleUnauthorized(unauthorized);
    }

    private void submit() {
        WritingPractice.Submission input = practice.submission();
        if (!validate(prompt, input.prompt(), R.string.writing_prompt, WritingPractice.MAX_PROMPT)
                || !validate(essay, input.essay(), R.string.writing_essay, WritingPractice.MAX_ESSAY)) {
            return;
        }
        if (practice.request.pending() && input.equals(practice.latestSubmission)) {
            return;
        }
        host.getSystemService(InputMethodManager.class).hideSoftInputFromWindow(page.getWindowToken(), 0);
        View focused = page.findFocus();
        if (focused != null) {
            focused.clearFocus();
        }
        practice.submit(input);
        render();
        scrollTo(requestStatus);
    }

    private boolean validate(EditText field, String input, int labelId, int limit) {
        if (!input.isEmpty() && input.length() <= limit) {
            return true;
        }
        String label = host.getString(labelId);
        field.requestFocus();
        field.setError(input.isEmpty() ? host.getString(R.string.writing_required, label)
                : host.getString(R.string.writing_limit, label, limit));
        scrollTo(field);
        return false;
    }

    private void renderEditorState() {
        WritingPractice.Submission input = practice.submission();
        submit.setEnabled(!practice.request.pending() || !input.equals(practice.latestSubmission));
        submit.setText(practice.latestSubmission == null ? R.string.writing_submit : R.string.writing_resubmit);
        WritingFeedback feedback = practice.feedback;
        boolean edited = feedback != null && (!input.prompt().equals(feedback.prompt())
                || !input.essay().equals(feedback.essay()));
        editedStatus.setVisibility(edited ? View.VISIBLE : View.GONE);
    }

    private void render() {
        renderEditorState();
        Exception error = practice.request.error;
        boolean unsupported = error instanceof ApiClient.Failure http
                && http.status == 422 && "DEMO_UNSUPPORTED".equals(http.code);
        String message = null;
        if (practice.request.pending()) {
            message = host.getString(R.string.writing_review_loading);
        } else if (unsupported) {
            message = host.getString(R.string.writing_review_scope);
        } else if (error instanceof SocketTimeoutException
                || error instanceof ApiClient.Failure http && http.status == 504) {
            message = host.getString(R.string.writing_review_timeout);
        } else if (error != null) {
            message = MainActivity.failureMessage(error);
        }
        requestStatus.setText(message);
        requestStatus.setVisibility(message == null ? View.GONE : View.VISIBLE);
        retry.setVisibility(error != null && !unsupported ? View.VISIBLE : View.GONE);
        if (displayed != practice.feedback) {
            displayed = practice.feedback;
            showFeedback(displayed);
        }
        if (followUpPanel != null) {
            followUpPanel.render();
        }
    }

    private void showFeedback(WritingFeedback feedback) {
        reviewPanel.setVisibility(View.VISIBLE);
        page.<TextView>findViewById(R.id.writing_review_heading)
                .setText(feedback.demo() ? R.string.review_demo : R.string.writing_review_title);
        page.<TextView>findViewById(R.id.writing_review_overall).setText(feedback.overall());
        issues.removeAllViews();
        followUpPanel = null;
        page.findViewById(R.id.writing_edit_essay).setVisibility(
                feedback.issues().isEmpty() ? View.GONE : View.VISIBLE);
        for (int i = 0; i < feedback.issues().size(); i++) {
            WritingFeedback.Issue issue = feedback.issues().get(i);
            LinearLayout row = (LinearLayout) host.getLayoutInflater().inflate(R.layout.row_review_issue, issues, false);
            ReviewQuote.bind(host, row, issue.type().label, issue.quote(), issue.detail(), issue.suggestion(),
                    R.string.writing_snapshot_title, feedback.essay(), issue.matches());
            WritingFeedback.Exercise exercise = feedback.exercise();
            if (exercise != null && exercise.issueIndex() == i) {
                View section = host.getLayoutInflater().inflate(R.layout.section_writing_exercise, row, false);
                followUpPanel = new WritingFollowUpPanel(section, practice.followUp, this::scrollTo);
                row.addView(section);
            }
            issues.addView(row);
        }
    }

    private void scrollTo(View anchor) {
        OneShotPreDrawListener.add(page, () -> {
            if (host.isCurrent(page) && anchor.isShown()) {
                Rect bounds = new Rect();
                anchor.getDrawingRect(bounds);
                page.offsetDescendantRectToMyCoords(anchor, bounds);
                int spacing = Math.round(12 * host.getResources().getDisplayMetrics().density);
                page.smoothScrollTo(0, bounds.top - spacing);
            }
        });
        page.invalidate();
    }

    static void bindInput(EditText field, String initial, Consumer<String> update) {
        field.setText(initial);
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                update.accept(s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }
}
