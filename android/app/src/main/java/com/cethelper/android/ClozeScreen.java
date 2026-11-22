package com.cethelper.android;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

final class ClozeScreen {
    private final MainActivity host;
    private final ClozeRound round;
    private final Runnable backToVocabulary;

    ClozeScreen(MainActivity host, ClozeRound round, Runnable backToVocabulary) {
        this.host = host;
        this.round = round;
        this.backToVocabulary = backToVocabulary;
    }

    void show() {
        if (round.isFinished()) {
            showSummary();
            return;
        }

        View page = host.showClozeScreen(R.layout.screen_cloze, backToVocabulary);
        page.findViewById(R.id.cloze_back).setOnClickListener(v -> backToVocabulary.run());
        page.<TextView>findViewById(R.id.cloze_progress)
                .setText(host.getString(R.string.cloze_progress, round.number(), round.total()));

        ProgressBar progressBar = UiViews.findOptionalView(page, "cloze_progress_bar", ProgressBar.class);
        if (progressBar != null) {
            progressBar.setMax(round.total());
            progressBar.setProgress(round.number());
        }

        ClozeQuestion question = round.current();
        boolean answered = round.result() != ClozeRound.Result.PENDING;
        page.<TextView>findViewById(R.id.cloze_article)
                .setText(answered ? question.article().title() : question.questionTitle());
        page.<TextView>findViewById(R.id.cloze_prompt).setText(question.prompt());
        if (answered) {
            showFeedback(page, question);
        } else {
            bindInput(page, question);
        }
    }

    private void bindInput(View page, ClozeQuestion question) {
        EditText input = page.findViewById(R.id.cloze_input);
        input.setText(round.draft());
        input.setSelection(input.length());
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
                if (host.isCurrent(page)) {
                    round.editDraft(text.toString());
                }
            }

            @Override
            public void afterTextChanged(Editable text) {}
        });

        View hintButton = UiViews.findOptionalView(page, "cloze_hint_button");
        TextView hintText = UiViews.findOptionalView(page, "cloze_hint_text", TextView.class);
        String hintValue = ClozeQuestion.buildHint(question.favorite().selection);
        if (hintText != null) {
            if (round.isHintShown()) {
                hintText.setText("提示：" + hintValue);
                hintText.setVisibility(View.VISIBLE);
            } else {
                hintText.setVisibility(View.GONE);
            }
        }
        if (hintButton != null) {
            hintButton.setVisibility(View.VISIBLE);
            hintButton.setOnClickListener(v -> {
                if (host.isCurrent(page)) {
                    round.showHint();
                    if (hintText != null) {
                        hintText.setText("提示：" + hintValue);
                        hintText.setVisibility(View.VISIBLE);
                    } else {
                        input.setHint(hintValue);
                    }
                }
            });
        }

        Runnable submit = () -> {
            if (!host.isCurrent(page)) {
                return;
            }
            round.submit(input.getText().toString());
            if (round.result() == ClozeRound.Result.PENDING) {
                input.setError(host.getString(R.string.cloze_answer_required));
                return;
            }
            host.recordFavoriteReview(question.favorite(), round.result() == ClozeRound.Result.CORRECT);
            hideKeyboard(input);
            show();
        };
        page.findViewById(R.id.cloze_submit).setOnClickListener(v -> submit.run());
        page.findViewById(R.id.cloze_reveal).setOnClickListener(v -> {
            if (host.isCurrent(page)) {
                round.reveal();
                host.recordFavoriteReview(question.favorite(), false);
                hideKeyboard(input);
                show();
            }
        });
        input.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_DONE) {
                submit.run();
                return true;
            }
            return false;
        });
    }

    private void showFeedback(View page, ClozeQuestion question) {
        page.findViewById(R.id.cloze_entry).setVisibility(View.GONE);
        page.findViewById(R.id.cloze_review).setVisibility(View.VISIBLE);
        int outcome = switch (round.result()) {
            case CORRECT -> R.string.cloze_correct;
            case INCORRECT -> R.string.cloze_incorrect;
            case REVEALED -> R.string.cloze_revealed;
            case PENDING -> throw new IllegalStateException("Question has not been answered");
        };
        page.<TextView>findViewById(R.id.cloze_feedback).setText(outcome);
        Favorite favorite = question.favorite();
        page.<TextView>findViewById(R.id.cloze_answer).setText(favorite.selection);
        page.<TextView>findViewById(R.id.cloze_meaning).setText(favorite.meaning);
        page.<TextView>findViewById(R.id.cloze_original).setText(favorite.sentence);
        TextView jump = page.findViewById(R.id.cloze_jump);
        if (Favorite.DICT_ARTICLE_ID.equals(favorite.articleId)) {
            jump.setText("词典详情");
            jump.setOnClickListener(v -> {
                if (host.isCurrent(page)) {
                    host.openDictionary(favorite.selection);
                }
            });
        } else {
            jump.setText(R.string.return_to_article);
            jump.setOnClickListener(v -> {
                if (host.isCurrent(page)) {
                    host.showReader(question.article(), favorite, this::show);
                }
            });
        }
        TextView next = page.findViewById(R.id.cloze_next);
        next.setText(round.number() == round.total() ? R.string.cloze_finish : R.string.cloze_next);
        next.setOnClickListener(v -> {
            if (host.isCurrent(page)) {
                round.advance();
                show();
            }
        });
    }

    private void showSummary() {
        View page = host.showClozeScreen(R.layout.screen_cloze_summary, backToVocabulary);
        page.<TextView>findViewById(R.id.cloze_score)
                .setText(host.getString(R.string.cloze_score, round.correct(), round.total()));
        page.findViewById(R.id.cloze_summary_back).setOnClickListener(v -> backToVocabulary.run());

        View retryWrong = UiViews.findOptionalView(page, "cloze_retry_wrong");
        if (retryWrong != null) {
            if (round.hasWrongQuestions()) {
                retryWrong.setVisibility(View.VISIBLE);
                retryWrong.setOnClickListener(v -> {
                    ClozeRound wrongRound = round.retryWrongRound();
                    if (wrongRound != null) {
                        host.startWrongPractice(wrongRound);
                    }
                });
            } else {
                retryWrong.setVisibility(View.GONE);
            }
        }
    }

    private void hideKeyboard(EditText input) {
        host.getSystemService(InputMethodManager.class).hideSoftInputFromWindow(input.getWindowToken(), 0);
    }
}
