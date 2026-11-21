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
    private final ArticleOwner owner;
    private final DraftStore drafts;

    String sourceInput;
    String source = "";
    String draft;
    boolean answering;
    TranslationFeedback.HintType selectedHint;
    final PracticeRequest<TranslationFeedback.Hint> hint;
    final PracticeRequest<TranslationFeedback.Review> review;
    Consumer<PracticeRequest<?>> onChanged;

    TranslationPractice(ExecutorService executor, ArticleOwner owner, String token, DraftStore drafts) {
        hint = new PracticeRequest<>(executor, this::notifyChanged);
        review = new PracticeRequest<>(executor, this::notifyChanged);
        this.api = new ApiClient(owner.serverUrl());
        this.token = token;
        this.owner = owner;
        this.drafts = drafts;
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

    boolean hasInput() {
        return !sourceInput.isEmpty() || !draft.isEmpty();
    }

    void setSourceInput(String text) {
        sourceInput = text;
        saveInputs();
    }

    void setDraft(String text) {
        draft = text;
        saveInputs();
    }

    void replace(String text) {
        resetResponses();
        source = "";
        sourceInput = text;
        draft = "";
        saveInputs();
    }

    private void saveInputs() {
        drafts.saveTranslation(owner, sourceInput, draft);
    }

    void loadHint(TranslationFeedback.HintType type) {
        selectedHint = type;
        String input = source;
        hint.start(() -> api.translationHint(token, input, type));
    }

    void submit(String submittedDraft) {
        String input = source;
        review.start(() -> api.translationReview(token, input, submittedDraft));
    }

    void resetResponses() {
        selectedHint = null;
        hint.reset();
        review.reset();
    }
}
