package com.cethelper.android;

import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

final class WritingPractice {
    static final int MAX_PROMPT = 2000;
    static final int MAX_ESSAY = 6000;
    static final String DEMO_PROMPT = "For this part, you are allowed 30 minutes to write an essay on the "
            + "benefits of volunteering for college students. Give an example to support your view. "
            + "You should write at least 120 words but no more than 180 words.";
    static final String DEMO_ESSAY = """
            Many students prefers to spend their weekends relaxing, but volunteering is another way to use that time well. It gives college students a chance to help others while putting what they learn in class into practice.

            Last semester, our class organize a weekend cleanup near the campus. At first, we all wanted to work on the same street, and no one had brought enough bags. We divided the area into smaller sections and asked a nearby shop for extra bags. By the end of the morning, we had learned how to share tasks and solve a problem together.

            A student does not have to volunteer every weekend to benefit. Even a few hours a month can build confidence and make the local community a better place to live.""";

    record Submission(String prompt, String essay) {}

    private final ExecutorService executor;
    private final ApiClient api;
    private final String token;
    final ArticleOwner owner;
    private final DraftStore drafts;
    private final Consumer<PracticeRecord> onRecordSaved;
    final PracticeRequest<WritingFeedback> request;
    String prompt;
    String essay;
    WritingFeedback feedback;
    WritingFollowUp followUp;
    Submission latestSubmission;
    Runnable onChanged;

    WritingPractice(ExecutorService executor, ArticleOwner owner, String token, DraftStore drafts) {
        this(executor, owner, token, drafts, null);
    }

    WritingPractice(ExecutorService executor, ArticleOwner owner, String token, DraftStore drafts,
                    Consumer<PracticeRecord> onRecordSaved) {
        this.executor = executor;
        this.api = new ApiClient(owner.serverUrl());
        this.token = token;
        this.owner = owner;
        this.drafts = drafts;
        this.onRecordSaved = onRecordSaved;
        DraftStore.Writing saved = drafts.writing(owner);
        prompt = saved.prompt();
        essay = saved.essay();
        request = new PracticeRequest<>(executor, completed -> {
            if (completed.value != null) {
                applyFeedback(completed.value);
                if (this.onRecordSaved != null && completed.value.rawJson() != null
                        && !completed.value.rawJson().isEmpty()) {
                    this.onRecordSaved.accept(new PracticeRecord(
                            PracticeRecord.ownerKey(owner),
                            PracticeRecord.TYPE_WRITING,
                            completed.value.prompt(),
                            completed.value.essay(),
                            completed.value.rawJson(),
                            completed.value.estimatedScore(),
                            System.currentTimeMillis()));
                }
            }
            notifyChanged();
        });
    }

    private void applyFeedback(WritingFeedback result) {
        clearFollowUp();
        feedback = result;
        WritingFeedback.Exercise exercise = feedback.exercise();
        if (exercise != null && exercise.issueIndex() >= 0 && exercise.issueIndex() < feedback.issues().size()) {
            WritingFollowUp.Context context = new WritingFollowUp.Context(
                    feedback.issues().get(exercise.issueIndex()),
                    new WritingFollowUp.Task(exercise.instruction(), exercise.item()));
            followUp = new WritingFollowUp(executor, api, token, context, this::notifyChanged);
        }
    }

    void restoreFromRecord(String savedPrompt, String savedEssay, WritingFeedback savedFeedback) {
        cancel();
        prompt = savedPrompt;
        essay = savedEssay;
        latestSubmission = new Submission(ReadingArticle.trimSpace(savedPrompt), ReadingArticle.trimSpace(savedEssay));
        if (savedFeedback != null) {
            applyFeedback(savedFeedback);
        } else {
            feedback = null;
        }
        drafts.saveWriting(owner, prompt, essay, true);
    }

    private void notifyChanged() {
        if (onChanged != null) {
            onChanged.run();
        }
    }

    private void clearFollowUp() {
        if (followUp != null) {
            followUp.cancel();
            followUp = null;
        }
    }

    void cancel() {
        request.reset();
        clearFollowUp();
    }

    boolean hasInput() {
        return !prompt.isEmpty() || !essay.isEmpty();
    }

    void setPrompt(String input) {
        prompt = input;
        saveInputs(false);
    }

    void setEssay(String input) {
        essay = input;
        saveInputs(false);
    }

    void replace(String newPrompt, String newEssay) {
        cancel();
        feedback = null;
        latestSubmission = null;
        prompt = newPrompt;
        essay = newEssay;
        saveInputs(true);
    }

    private void saveInputs(boolean immediate) {
        drafts.saveWriting(owner, prompt, essay, immediate);
    }

    Submission submission() {
        return new Submission(ReadingArticle.trimSpace(prompt), ReadingArticle.trimSpace(essay));
    }

    void submit(Submission input) {
        drafts.flush();
        latestSubmission = input;
        request.start(() -> api.writingReview(token, input.prompt(), input.essay()));
    }
}
