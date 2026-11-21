package com.cethelper.android;

import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

final class TranslationPractice {
    static final int MAX_SOURCE = 2000;
    static final int MAX_DRAFT = 3000;
    static final String DEMO_SOURCE = "随着数字经济的快速发展，掌握基本的编程能力对高校毕业生越来越重要。"
            + "许多企业因此更愿意录用那些既能独立思考又能团队协作的年轻人。";

    private final ApiClient api;
    private final String token;
    final ArticleOwner owner;
    private final DraftStore drafts;
    private final Consumer<PracticeRecord> onRecordSaved;

    String sourceInput;
    String source = "";
    String draft;
    boolean answering;
    TranslationFeedback.HintType selectedHint;
    final PracticeRequest<TranslationFeedback.Hint> hint;
    final PracticeRequest<TranslationFeedback.Review> review;
    Consumer<PracticeRequest<?>> onChanged;

    TranslationPractice(ExecutorService executor, ArticleOwner owner, String token, DraftStore drafts) {
        this(executor, owner, token, drafts, null);
    }

    TranslationPractice(ExecutorService executor, ArticleOwner owner, String token, DraftStore drafts,
                        Consumer<PracticeRecord> onRecordSaved) {
        this.api = new ApiClient(owner.serverUrl());
        this.token = token;
        this.owner = owner;
        this.drafts = drafts;
        this.onRecordSaved = onRecordSaved;
        hint = new PracticeRequest<>(executor, this::notifyChanged);
        review = new PracticeRequest<>(executor, completed -> {
            if (completed.value != null && this.onRecordSaved != null
                    && completed.value.rawJson() != null && !completed.value.rawJson().isEmpty()) {
                this.onRecordSaved.accept(new PracticeRecord(
                        PracticeRecord.ownerKey(owner),
                        PracticeRecord.TYPE_TRANSLATION,
                        completed.value.source(),
                        completed.value.submittedDraft(),
                        completed.value.rawJson(),
                        completed.value.estimatedScore(),
                        System.currentTimeMillis()));
            }
            notifyChanged(completed);
        });
        DraftStore.Translation saved = drafts.translation(owner);
        sourceInput = saved.source();
        draft = saved.draft();
    }

    private void notifyChanged(PracticeRequest<?> request) {
        if (onChanged != null) {
            onChanged.accept(request);
        }
    }

    void begin(String correctedSource) {
        if (!source.equals(correctedSource)) {
            resetResponses();
        }
        source = correctedSource;
        answering = true;
    }

    void restoreFromRecord(String savedSource, String savedDraft, TranslationFeedback.Review savedReview) {
        resetResponses();
        sourceInput = savedSource;
        source = savedSource;
        draft = savedDraft;
        answering = true;
        review.value = savedReview;
        drafts.saveTranslation(owner, sourceInput, draft, true);
    }

    boolean hasInput() {
        return !sourceInput.isEmpty() || !draft.isEmpty();
    }

    void setSourceInput(String text) {
        sourceInput = text;
        saveInputs(false);
    }

    void setDraft(String text) {
        draft = text;
        saveInputs(false);
    }

    void replace(String text) {
        resetResponses();
        source = "";
        sourceInput = text;
        draft = "";
        saveInputs(true);
    }

    private void saveInputs(boolean immediate) {
        drafts.saveTranslation(owner, sourceInput, draft, immediate);
    }

    void loadHint(TranslationFeedback.HintType type) {
        selectedHint = type;
        String input = source;
        hint.start(() -> api.translationHint(token, input, type));
    }

    void submit(String submittedDraft) {
        drafts.flush();
        String input = source;
        review.start(() -> api.translationReview(token, input, submittedDraft));
    }

    void resetResponses() {
        selectedHint = null;
        hint.reset();
        review.reset();
    }
}
