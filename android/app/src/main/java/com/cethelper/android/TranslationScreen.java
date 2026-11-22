package com.cethelper.android;

import android.app.AlertDialog;
import android.graphics.Rect;
import android.graphics.Typeface;
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

import org.json.JSONException;
import org.json.JSONObject;

import java.net.SocketTimeoutException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Predicate;

final class TranslationScreen {
    private final MainActivity host;
    private final TranslationPractice exercise;
    private final Predicate<Exception> handleUnauthorized;
    private final ScrollView page;
    private final TextView originalView;
    private final EditText draft;
    private final Button submit;
    private final TextView hintStatus;
    private final LinearLayout hintItems;
    private final View reviewPanel;
    private final TextView reviewHeading;
    private final TextView reviewStatus;
    private final View edited;
    private final LinearLayout issues;
    private final TextView wordCount;

    static void showSource(MainActivity host, TranslationPractice exercise, Consumer<Boolean> importPhoto) {
        View page = host.showTranslationScreen(R.layout.screen_translation_source, host::showHome);
        EditText source = page.findViewById(R.id.translation_source_input);
        source.setText(exercise.sourceInput);
        watch(source, value -> {
            if (host.isCurrent(page)) {
                exercise.setSourceInput(value);
            }
        });
        page.findViewById(R.id.translation_back).setOnClickListener(v -> host.showHome());
        page.findViewById(R.id.translation_demo).setOnClickListener(v ->
                host.replaceTranslation(page, TranslationPractice.DEMO_SOURCE, host::showTranslationSource));
        View clear = page.findViewById(R.id.translation_clear);
        clear.setOnClickListener(v -> {
            if (exercise.hasInput()) {
                host.confirmDraftChange(page, R.string.clear_practice, R.string.clear_practice_confirm,
                        R.string.clear_confirm, () -> {
                            exercise.replace("");
                            host.showTranslationSource();
                        });
            }
        });
        View historyButton = UiViews.findOptionalView(page, "translation_history_button");
        if (historyButton != null) {
            historyButton.setOnClickListener(v -> openHistory(host, page, exercise));
        }
        page.findViewById(R.id.translation_capture).setOnClickListener(v -> importPhoto.accept(true));
        page.findViewById(R.id.translation_pick).setOnClickListener(v -> importPhoto.accept(false));
        Button start = page.findViewById(R.id.translation_start);
        start.setText(exercise.draft.isEmpty() ? R.string.translation_start : R.string.translation_resume);
        start.setOnClickListener(v -> {
            String corrected = ReadingArticle.trimSpace(exercise.sourceInput);
            if (checkSource(source, corrected)) {
                hideKeyboard(page);
                host.startTranslation(corrected);
            }
        });
    }

    static boolean checkSource(EditText input, String source) {
        if (source.isEmpty()) {
            input.setError(input.getContext().getString(R.string.translation_source_required));
        } else if (source.length() > TranslationPractice.MAX_SOURCE) {
            input.setError(input.getContext().getString(R.string.translation_source_limit,
                    TranslationPractice.MAX_SOURCE));
        } else {
            return true;
        }
        input.requestFocus();
        return false;
    }

    static void showAnswer(MainActivity host, TranslationPractice exercise, Predicate<Exception> unauthorized) {
        new TranslationScreen(host, exercise, unauthorized).bind();
    }

    private TranslationScreen(MainActivity host, TranslationPractice exercise, Predicate<Exception> unauthorized) {
        this.host = host;
        this.exercise = exercise;
        this.handleUnauthorized = unauthorized;
        page = (ScrollView) host.showTranslationScreen(R.layout.screen_translation_answer, host::showTranslationSource);
        originalView = page.findViewById(R.id.translation_original);
        draft = page.findViewById(R.id.translation_draft);
        submit = page.findViewById(R.id.translation_submit);
        hintStatus = page.findViewById(R.id.translation_hint_status);
        hintItems = page.findViewById(R.id.translation_hint_items);
        reviewPanel = page.findViewById(R.id.translation_review_panel);
        reviewHeading = page.findViewById(R.id.translation_review_heading);
        reviewStatus = page.findViewById(R.id.translation_review_status);
        edited = page.findViewById(R.id.translation_edited);
        issues = page.findViewById(R.id.translation_issues);
        wordCount = UiViews.findOptionalView(page, "translation_word_count", TextView.class);
    }

    private void bind() {
        originalView.setText(exercise.source);
        page.findViewById(R.id.translation_edit_source).setOnClickListener(v -> {
            hideKeyboard(page);
            host.showTranslationSource();
        });
        View historyButton = UiViews.findOptionalView(page, "translation_history_button");
        if (historyButton != null) {
            historyButton.setOnClickListener(v -> openHistory(host, page, exercise));
        }
        draft.setText(exercise.draft);
        updateWordCount();
        watch(draft, value -> {
            if (host.isCurrent(page)) {
                ReviewQuote.clearHighlight(draft.getText());
                exercise.setDraft(value);
                showEditedStatus();
                updateWordCount();
            }
        });
        for (TranslationFeedback.HintType type : TranslationFeedback.HintType.values()) {
            page.findViewById(type.button).setOnClickListener(v -> {
                hideKeyboard(page);
                exercise.loadHint(type);
                renderHint();
                scrollTo(page.findViewById(R.id.translation_hint_panel));
            });
        }
        submit.setOnClickListener(v -> submitDraft());
        page.findViewById(R.id.translation_retry_review).setOnClickListener(v -> submitDraft());
        page.findViewById(R.id.translation_edit_draft).setOnClickListener(v -> {
            draft.requestFocus();
            scrollTo(draft);
            host.getSystemService(InputMethodManager.class).showSoftInput(draft, InputMethodManager.SHOW_IMPLICIT);
        });
        exercise.onChanged = request -> {
            if (!host.isCurrent(page) || handleUnauthorized.test(request.error)) {
                return;
            }
            if (request == exercise.hint) {
                renderHint();
            } else {
                renderReview();
            }
        };
        if (!handleUnauthorized.test(exercise.hint.error) && !handleUnauthorized.test(exercise.review.error)) {
            renderHint();
            renderReview();
        }
    }

    private static void openHistory(MainActivity host, View currentPage, TranslationPractice exercise) {
        host.loadPracticeHistory(PracticeRecord.TYPE_TRANSLATION, records -> {
            if (!host.isCurrent(currentPage)) {
                return;
            }
            if (records.isEmpty()) {
                new AlertDialog.Builder(host)
                        .setTitle("翻译批改历史")
                        .setMessage("还没有保存的翻译批改记录。")
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                return;
            }
            SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
            String[] labels = new String[records.size()];
            for (int i = 0; i < records.size(); i++) {
                PracticeRecord r = records.get(i);
                String scorePart = r.estimatedScore >= 0 ? (" [" + r.estimatedScore + "/15分]") : "";
                String snippet = r.promptOrSource.replace('\n', ' ').trim();
                if (snippet.length() > 36) {
                    snippet = snippet.substring(0, 36) + "…";
                }
                labels[i] = fmt.format(new Date(r.createdAt)) + scorePart + "\n" + snippet;
            }
            new AlertDialog.Builder(host)
                    .setTitle("翻译批改历史")
                    .setItems(labels, (dialog, which) -> showHistoryDetail(host, currentPage, exercise, records.get(which)))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });
    }

    private static void showHistoryDetail(MainActivity host, View currentPage, TranslationPractice exercise,
                                          PracticeRecord record) {
        if (!host.isCurrent(currentPage)) {
            return;
        }
        StringBuilder details = new StringBuilder();
        if (record.estimatedScore >= 0) {
            details.append("预估分：").append(record.estimatedScore).append(" / 15 分\n\n");
        }
        details.append("原文：\n").append(record.promptOrSource).append("\n\n");
        details.append("译文：\n").append(record.draft);
        new AlertDialog.Builder(host)
                .setTitle("历史批改详情")
                .setMessage(details.toString())
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("回填到编辑器", (d, w) -> {
                    if (!host.isCurrent(currentPage)) {
                        return;
                    }
                    TranslationFeedback.Review parsed = null;
                    try {
                        parsed = ApiClient.parseTranslationReview(new JSONObject(record.reviewJson));
                    } catch (JSONException ignored) {
                        // Fall back to restoring source and draft without structured review.
                    }
                    exercise.restoreFromRecord(record.promptOrSource, record.draft, parsed);
                    host.showTranslationAnswerFromHistory();
                })
                .show();
    }

    private void updateWordCount() {
        if (wordCount != null) {
            int words = UiViews.countEnglishWords(exercise.draft);
            wordCount.setText(UiViews.formatTranslationWordCount(words));
        }
    }

    private void renderHint() {
        TranslationFeedback.HintType selected = exercise.selectedHint;
        for (TranslationFeedback.HintType type : TranslationFeedback.HintType.values()) {
            Button button = page.findViewById(type.button);
            boolean active = selected == type;
            button.setSelected(active);
            button.setTypeface(button.getTypeface(), active ? Typeface.BOLD : Typeface.NORMAL);
        }
        hintItems.removeAllViews();
        if (selected == null) {
            return;
        }
        String label = host.getString(selected.label);
        if (exercise.hint.pending()) {
            setMessage(hintStatus, host.getString(R.string.translation_hint_loading, label));
        } else if (exercise.hint.error != null) {
            Exception error = exercise.hint.error;
            String message = isUnsupported(error) ? host.getString(R.string.translation_hint_scope)
                    : host.getString(R.string.translation_hint_error, errorMessage(error, true), label);
            setMessage(hintStatus, message);
        } else {
            TranslationFeedback.Hint hint = exercise.hint.value;
            setMessage(hintStatus, hint.demo() ? host.getString(R.string.translation_hint_demo, label) : null);
            for (TranslationFeedback.HintItem item : hint.items()) {
                TextView text = new TextView(host, null, 0, R.style.StudyCaption);
                text.setPadding(0, dp(8), 0, dp(6));
                text.setText(item.title() == null ? item.guidance() : item.title() + "\n" + item.guidance());
                hintItems.addView(text);
            }
        }
    }

    private void submitDraft() {
        String input = ReadingArticle.trimSpace(exercise.draft);
        if (input.isEmpty() || input.length() > TranslationPractice.MAX_DRAFT) {
            draft.setError(input.isEmpty() ? host.getString(R.string.translation_draft_required)
                    : host.getString(R.string.translation_draft_limit, TranslationPractice.MAX_DRAFT));
            draft.requestFocus();
            scrollTo(draft);
            return;
        }
        hideKeyboard(page);
        exercise.submit(input);
        renderReview();
        scrollTo(reviewPanel);
    }

    private void renderReview() {
        TranslationFeedback.Review result = exercise.review.value;
        boolean pending = exercise.review.pending();
        Exception error = exercise.review.error;
        submit.setEnabled(!pending);
        submit.setText(result != null || error != null ? R.string.translation_submit_again : R.string.translation_submit);
        issues.removeAllViews();
        showEditedStatus();
        View scoreCard = UiViews.findOptionalView(page, "translation_score_card");
        TextView scoreValue = UiViews.findOptionalView(page, "translation_score_value", TextView.class);
        TextView scoreBand = UiViews.findOptionalView(page, "translation_score_band", TextView.class);
        View refCard = UiViews.findOptionalView(page, "translation_reference_card");
        TextView refText = UiViews.findOptionalView(page, "translation_reference_text", TextView.class);
        View askTutor = UiViews.findOptionalView(page, "translation_ask_tutor");

        if (result == null && !pending && error == null) {
            reviewPanel.setVisibility(View.GONE);
            UiViews.setVisible(askTutor, false);
            if (scoreCard != null) {
                scoreCard.setVisibility(View.GONE);
            }
            if (refCard != null) {
                refCard.setVisibility(View.GONE);
            }
            return;
        }
        reviewPanel.setVisibility(View.VISIBLE);
        page.findViewById(R.id.translation_retry_review).setVisibility(
                error != null && !isUnsupported(error) ? View.VISIBLE : View.GONE);
        page.findViewById(R.id.translation_edit_draft).setVisibility(pending ? View.GONE : View.VISIBLE);
        reviewHeading.setText(result != null && result.demo()
                ? R.string.review_demo : R.string.translation_review_heading);
        if (pending) {
            UiViews.setVisible(askTutor, false);
            if (scoreCard != null) {
                scoreCard.setVisibility(View.GONE);
            }
            if (refCard != null) {
                refCard.setVisibility(View.GONE);
            }
            reviewStatus.setText(R.string.translation_review_loading);
        } else if (error != null) {
            UiViews.setVisible(askTutor, false);
            if (scoreCard != null) {
                scoreCard.setVisibility(View.GONE);
            }
            if (refCard != null) {
                refCard.setVisibility(View.GONE);
            }
            reviewStatus.setText(isUnsupported(error)
                    ? host.getString(R.string.translation_review_scope) : errorMessage(error, false));
        } else {
            boolean hasScore = result.estimatedScore() >= 0;
            if (scoreCard != null) {
                scoreCard.setVisibility(hasScore ? View.VISIBLE : View.GONE);
            }
            if (scoreValue != null) {
                if (hasScore) {
                    String val = result.estimatedScore() + " / " + result.scoreMax() + " 分";
                    if (scoreBand == null && !result.scoreBand().isEmpty()) {
                        val = val + " · " + result.scoreBand();
                    }
                    scoreValue.setText(val);
                    scoreValue.setVisibility(View.VISIBLE);
                } else {
                    scoreValue.setVisibility(View.GONE);
                }
            }
            if (scoreBand != null) {
                if (hasScore && !result.scoreBand().isEmpty()) {
                    scoreBand.setText(result.scoreBand());
                    scoreBand.setVisibility(View.VISIBLE);
                } else {
                    scoreBand.setVisibility(View.GONE);
                }
            }
            boolean hasRef = !result.referenceTranslation().isEmpty();
            if (refCard != null) {
                refCard.setVisibility(hasRef ? View.VISIBLE : View.GONE);
            }
            if (refText != null) {
                if (hasRef) {
                    refText.setText(result.referenceTranslation());
                    refText.setVisibility(View.VISIBLE);
                } else {
                    refText.setVisibility(View.GONE);
                }
            }

            String statusText = result.overall();
            if ((hasScore && scoreCard == null && scoreValue == null)
                    || (hasRef && refCard == null && refText == null)) {
                StringBuilder enriched = new StringBuilder();
                if (hasScore && scoreCard == null && scoreValue == null) {
                    enriched.append("预估分：").append(result.estimatedScore()).append(" / ")
                            .append(result.scoreMax()).append(" 分");
                    if (!result.scoreBand().isEmpty()) {
                        enriched.append("\n").append(result.scoreBand());
                    }
                    enriched.append("\n\n");
                }
                enriched.append(statusText);
                if (hasRef && refCard == null && refText == null) {
                    enriched.append("\n\n参考译文：\n").append(result.referenceTranslation());
                }
                statusText = enriched.toString();
            }
            reviewStatus.setText(statusText);
            for (TranslationFeedback.Issue issue : result.issues()) {
                addIssue(result, issue);
            }
            if (askTutor != null) {
                askTutor.setVisibility(View.VISIBLE);
                askTutor.setOnClickListener(v -> openTutor(result));
            }
        }
    }

    private void openTutor(TranslationFeedback.Review result) {
        StringBuilder note = new StringBuilder();
        if (result.estimatedScore() >= 0) {
            note.append("预估分：").append(result.estimatedScore())
                    .append("/").append(result.scoreMax()).append("分");
            if (!result.scoreBand().isEmpty()) {
                note.append("（").append(result.scoreBand()).append("）");
            }
            note.append("\n");
        }
        if (result.overall() != null && !result.overall().isEmpty()) {
            note.append("总评：").append(result.overall()).append("\n");
        }
        if (result.referenceTranslation() != null && !result.referenceTranslation().isEmpty()) {
            note.append("参考译文：").append(result.referenceTranslation()).append("\n");
        }
        for (TranslationFeedback.Issue issue : result.issues()) {
            note.append("- ").append(host.getString(issue.type().label))
                    .append("「").append(UiViews.safeText(issue.quote())).append("」：")
                    .append(UiViews.safeText(issue.detail()));
            if (issue.suggestion() != null && !issue.suggestion().isEmpty()) {
                note.append(" 建议：").append(issue.suggestion());
            }
            note.append("\n");
        }
        String draftText = ReadingArticle.trimSpace(exercise.draft);
        if (draftText.isEmpty()) {
            draftText = UiViews.safeText(result.submittedDraft());
        }
        host.openTutorWithContext(new TutorContext(
                TutorContext.TRANSLATION,
                "翻译练习",
                UiViews.truncate(draftText, 2000),
                UiViews.truncate(exercise.source, 6000),
                UiViews.truncate(note.toString(), 4000)));
    }

    private void showEditedStatus() {
        TranslationFeedback.Review result = exercise.review.value;
        boolean changed = result != null && !result.submittedDraft().equals(ReadingArticle.trimSpace(exercise.draft));
        edited.setVisibility(changed ? View.VISIBLE : View.GONE);
    }

    private void addIssue(TranslationFeedback.Review result, TranslationFeedback.Issue issue) {
        View row = host.getLayoutInflater().inflate(R.layout.row_review_issue, issues, false);
        Runnable inlineLocate = issue.type() == TranslationFeedback.IssueType.OMISSION
                ? () -> ReviewQuote.highlightAndScrollTextView(page, originalView, exercise.source, issue.quote())
                : () -> ReviewQuote.highlightAndScrollEditText(page, draft, issue.quote());
        ReviewQuote.bind(host, row, issue.type().label, issue.quote(), issue.detail(), issue.suggestion(),
                issue.type().snapshotTitle, result.snapshot(issue.type()), issue.matches(), inlineLocate);
        issues.addView(row);
    }

    private void scrollTo(View anchor) {
        OneShotPreDrawListener.add(anchor, () -> {
            if (host.isCurrent(page)) {
                Rect bounds = new Rect();
                anchor.getDrawingRect(bounds);
                page.offsetDescendantRectToMyCoords(anchor, bounds);
                page.smoothScrollTo(0, bounds.top - dp(12));
            }
        });
        page.invalidate();
    }

    private String errorMessage(Exception error, boolean hint) {
        if (error instanceof SocketTimeoutException || error instanceof ApiClient.Failure http && http.status == 504) {
            return host.getString(hint ? R.string.translation_hint_timeout : R.string.translation_review_timeout);
        }
        return MainActivity.failureMessage(error);
    }

    private static boolean isUnsupported(Exception error) {
        return error instanceof ApiClient.Failure http && http.status == 422 && "DEMO_UNSUPPORTED".equals(http.code);
    }

    private static void setMessage(TextView view, String text) {
        view.setText(text);
        view.setVisibility(text == null ? View.GONE : View.VISIBLE);
    }

    private int dp(int size) {
        return Math.round(size * host.getResources().getDisplayMetrics().density);
    }

    private static void hideKeyboard(View root) {
        root.getContext().getSystemService(InputMethodManager.class).hideSoftInputFromWindow(root.getWindowToken(), 0);
    }

    private static void watch(EditText field, Consumer<String> update) {
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                update.accept(s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }
}
