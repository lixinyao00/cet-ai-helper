package com.cethelper.android;

import java.util.List;

record WritingFeedback(String prompt, String essay, boolean demo, String overall, List<Issue> issues,
                       Exercise exercise) {
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
