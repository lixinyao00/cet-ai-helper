package com.cethelper.android;

import java.util.List;

record WritingFeedback(String prompt, String essay, boolean demo, String overall, List<Issue> issues,
                       Exercise exercise, int estimatedScore, int scoreMax, String scoreBand,
                       String rawJson) {
    WritingFeedback(String prompt, String essay, boolean demo, String overall, List<Issue> issues,
                    Exercise exercise) {
        this(prompt, essay, demo, overall, issues, exercise, -1, 15, "", "");
    }

    enum IssueType {
        GRAMMAR(R.string.writing_grammar),
        WORD_CHOICE(R.string.writing_word_choice),
        MECHANICS(R.string.writing_mechanics),
        CLARITY(R.string.writing_clarity);

        final int label;

        IssueType(int label) {
            this.label = label;
        }
    }

    record Issue(IssueType type, String quote, String detail, String suggestion, List<ReviewQuote.Range> matches) {}
    record Exercise(int issueIndex, String instruction, String item) {}
}
