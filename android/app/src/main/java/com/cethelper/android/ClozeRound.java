package com.cethelper.android;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class ClozeRound {
    enum Result { PENDING, CORRECT, INCORRECT, REVEALED }

    private final List<ClozeQuestion> questions;
    private int index;
    private int correct;
    private Result result = Result.PENDING;
    private String draft = "";

    ClozeRound(List<ClozeQuestion> questions) {
        if (questions.isEmpty()) {
            throw new IllegalArgumentException("A round requires at least one question");
        }
        this.questions = Collections.unmodifiableList(new ArrayList<>(questions));
    }

    ClozeQuestion current() {
        return questions.get(index);
    }

    int number() {
        return index + 1;
    }

    int total() {
        return questions.size();
    }

    int correct() {
        return correct;
    }

    Result result() {
        return result;
    }

    String draft() {
        return draft;
    }

    void editDraft(String text) {
        if (!isFinished() && result == Result.PENDING) {
            draft = text;
        }
    }

    boolean isFinished() {
        return index == total();
    }

    void submit(String answer) {
        if (isFinished() || result != Result.PENDING || ReadingArticle.trimSpace(answer).isEmpty()) {
            return;
        }
        if (current().matches(answer)) {
            result = Result.CORRECT;
            correct++;
        } else {
            result = Result.INCORRECT;
        }
        draft = "";
    }

    void reveal() {
        if (!isFinished() && result == Result.PENDING) {
            result = Result.REVEALED;
            draft = "";
        }
    }

    void advance() {
        if (!isFinished() && result != Result.PENDING) {
            index++;
            result = Result.PENDING;
        }
    }
}
