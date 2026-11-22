package com.cethelper.android;

import android.os.Bundle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

final class ClozeRound {
    static final int MAX_ROUND_SIZE = 10;
    static final long DAY_MS = 24L * 60L * 60L * 1000L;

    enum Result { PENDING, CORRECT, INCORRECT, REVEALED }

    record Creation(ClozeRound round, int skippedCount) {}

    private final List<ClozeQuestion> questions;
    private final List<Integer> wrongIndices = new ArrayList<>();
    private int index;
    private int correct;
    private Result result = Result.PENDING;
    private String draft = "";
    private boolean hintShown;

    ClozeRound(List<ClozeQuestion> questions) {
        if (questions.isEmpty()) {
            throw new IllegalArgumentException("A round requires at least one question");
        }
        this.questions = Collections.unmodifiableList(new ArrayList<>(questions));
    }

    static Creation create(List<Favorite> favorites, Function<String, ReadingArticle> articleLookup) {
        return create(favorites, articleLookup, System.currentTimeMillis(), MAX_ROUND_SIZE);
    }

    static Creation create(List<Favorite> favorites, Function<String, ReadingArticle> articleLookup,
                           long now, int maxSize) {
        if (favorites == null || favorites.isEmpty()) {
            return new Creation(null, 0);
        }
        List<Favorite> ordered = sortForReview(favorites, now);
        List<ClozeQuestion> valid = new ArrayList<>();
        int skipped = 0;
        int limit = maxSize > 0 ? maxSize : MAX_ROUND_SIZE;
        for (Favorite favorite : ordered) {
            if (favorite == null) {
                skipped++;
                continue;
            }
            ReadingArticle article = Favorite.DICT_ARTICLE_ID.equals(favorite.articleId)
                    ? null
                    : articleLookup.apply(favorite.articleId);
            ClozeQuestion question = ClozeQuestion.from(article, favorite);
            if (question == null) {
                skipped++;
                continue;
            }
            valid.add(question);
            if (valid.size() >= limit) {
                break;
            }
        }
        return new Creation(valid.isEmpty() ? null : new ClozeRound(valid), skipped);
    }

    static List<Favorite> sortForReview(List<Favorite> favorites, long now) {
        List<Favorite> sorted = new ArrayList<>(favorites);
        sorted.sort(Comparator
                .comparing((Favorite f) -> f.nextReviewAt > now)
                .thenComparingLong(f -> f.nextReviewAt)
                .thenComparingInt(f -> f.masteryLevel)
                .thenComparing(Comparator.comparingLong((Favorite f) -> f.savedAt).reversed())
                .thenComparing(f -> f.articleId)
                .thenComparingInt(f -> f.startOffset)
                .thenComparingInt(f -> f.endOffset));
        return sorted;
    }

    static int nextMasteryLevel(int currentMastery, boolean correct) {
        if (!correct) {
            return 0;
        }
        return Math.min(3, Math.max(0, currentMastery) + 1);
    }

    static long nextReviewTimestamp(int newMastery, long now) {
        return switch (newMastery) {
            case 1 -> now + DAY_MS;
            case 2 -> now + 3L * DAY_MS;
            case 3 -> now + 7L * DAY_MS;
            default -> now;
        };
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

    boolean isHintShown() {
        return hintShown;
    }

    void showHint() {
        if (!isFinished() && result == Result.PENDING) {
            hintShown = true;
        }
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
            recordWrong(index);
        }
        draft = "";
    }

    void reveal() {
        if (!isFinished() && result == Result.PENDING) {
            result = Result.REVEALED;
            recordWrong(index);
            draft = "";
        }
    }

    void advance() {
        if (!isFinished() && result != Result.PENDING) {
            index++;
            result = Result.PENDING;
            hintShown = false;
        }
    }

    boolean hasWrongQuestions() {
        return !wrongIndices.isEmpty();
    }

    List<ClozeQuestion> wrongQuestions() {
        List<ClozeQuestion> resultList = new ArrayList<>(wrongIndices.size());
        for (int wrongIndex : wrongIndices) {
            if (wrongIndex >= 0 && wrongIndex < questions.size()) {
                resultList.add(questions.get(wrongIndex));
            }
        }
        return Collections.unmodifiableList(resultList);
    }

    ClozeRound retryWrongRound() {
        List<ClozeQuestion> wrongs = wrongQuestions();
        return wrongs.isEmpty() ? null : new ClozeRound(wrongs);
    }

    private void recordWrong(int questionIndex) {
        if (!wrongIndices.contains(questionIndex)) {
            wrongIndices.add(questionIndex);
        }
    }

    Bundle saveState() {
        Bundle saved = new Bundle();
        int size = questions.size();
        ArrayList<String> articleIds = new ArrayList<>(size);
        ArrayList<String> selections = new ArrayList<>(size);
        int[] starts = new int[size];
        int[] ends = new int[size];
        for (int i = 0; i < size; i++) {
            Favorite fav = questions.get(i).favorite();
            articleIds.add(fav.articleId);
            selections.add(fav.selection);
            starts[i] = fav.startOffset;
            ends[i] = fav.endOffset;
        }
        saved.putStringArrayList("articleIds", articleIds);
        saved.putStringArrayList("selections", selections);
        saved.putIntArray("starts", starts);
        saved.putIntArray("ends", ends);
        saved.putInt("index", index);
        saved.putInt("correct", correct);
        saved.putString("result", result.name());
        saved.putString("draft", draft);
        saved.putBoolean("hintShown", hintShown);
        int[] wrongs = new int[wrongIndices.size()];
        for (int i = 0; i < wrongIndices.size(); i++) {
            wrongs[i] = wrongIndices.get(i);
        }
        saved.putIntArray("wrongIndices", wrongs);
        return saved;
    }

    static ClozeRound restoreState(Bundle saved, List<Favorite> allFavorites,
                                   Function<String, ReadingArticle> articleLookup) {
        if (saved == null || allFavorites == null) {
            return null;
        }
        ArrayList<String> articleIds = saved.getStringArrayList("articleIds");
        ArrayList<String> selections = saved.getStringArrayList("selections");
        int[] starts = saved.getIntArray("starts");
        int[] ends = saved.getIntArray("ends");
        if (articleIds == null || starts == null || ends == null
                || articleIds.size() != starts.length || starts.length != ends.length || articleIds.isEmpty()) {
            return null;
        }
        List<ClozeQuestion> restoredQuestions = new ArrayList<>(articleIds.size());
        for (int i = 0; i < articleIds.size(); i++) {
            String articleId = articleIds.get(i);
            String selection = (selections != null && i < selections.size()) ? selections.get(i) : null;
            int start = starts[i];
            int end = ends[i];
            Favorite match = null;
            for (Favorite fav : allFavorites) {
                if (fav.articleId.equals(articleId) && fav.startOffset == start && fav.endOffset == end
                        && (selection == null || fav.selection.equals(selection))) {
                    match = fav;
                    break;
                }
            }
            if (match == null) {
                continue;
            }
            ReadingArticle article = Favorite.DICT_ARTICLE_ID.equals(articleId)
                    ? null
                    : articleLookup.apply(articleId);
            ClozeQuestion q = ClozeQuestion.from(article, match);
            if (q != null) {
                restoredQuestions.add(q);
            }
        }
        if (restoredQuestions.isEmpty()) {
            return null;
        }
        ClozeRound round = new ClozeRound(restoredQuestions);
        int savedIndex = saved.getInt("index", 0);
        round.index = Math.max(0, Math.min(savedIndex, restoredQuestions.size()));
        round.correct = Math.max(0, Math.min(saved.getInt("correct", 0), restoredQuestions.size()));
        try {
            round.result = Result.valueOf(saved.getString("result", Result.PENDING.name()));
        } catch (IllegalArgumentException ignored) {
            round.result = Result.PENDING;
        }
        round.draft = saved.getString("draft", "");
        round.hintShown = saved.getBoolean("hintShown", false);
        int[] wrongs = saved.getIntArray("wrongIndices");
        if (wrongs != null) {
            for (int w : wrongs) {
                if (w >= 0 && w < restoredQuestions.size()) {
                    round.recordWrong(w);
                }
            }
        }
        return round;
    }
}
