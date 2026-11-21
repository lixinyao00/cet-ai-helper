package com.cethelper.android;

import java.util.List;

final class TranslationFeedback {
    enum HintType {
        KEYWORD("keyword", "keywords", R.string.translation_keywords, R.id.translation_keywords),
        PHRASE("phrase", "phrases", R.string.translation_phrases, R.id.translation_phrases),
        STRUCTURE("structure", "structure", R.string.translation_structure, R.id.translation_structure);

        final String apiValue;
        final String field;
        final int label;
        final int button;

        HintType(String apiValue, String field, int label, int button) {
            this.apiValue = apiValue;
            this.field = field;
            this.label = label;
            this.button = button;
        }
    }

    enum IssueType {
        OMISSION(R.string.translation_omission, R.string.translation_submitted_source),
        UNNATURAL(R.string.translation_unnatural, R.string.translation_submitted_draft);

        final int label;
        final int snapshotTitle;

        IssueType(int label, int snapshotTitle) {
            this.label = label;
            this.snapshotTitle = snapshotTitle;
        }
    }

    record HintItem(String title, String guidance) {}
    record Hint(boolean demo, List<HintItem> items) {}
    record Issue(IssueType type, String quote, String detail, String suggestion, List<ReviewQuote.Range> matches) {}
    record Review(String source, String submittedDraft, boolean demo, String overall, List<Issue> issues,
                  int estimatedScore, int scoreMax, String scoreBand, String referenceTranslation,
                  String rawJson) {
        Review(String source, String submittedDraft, boolean demo, String overall, List<Issue> issues) {
            this(source, submittedDraft, demo, overall, issues, -1, 15, "", "", "");
        }

        String snapshot(IssueType type) {
            return type == IssueType.OMISSION ? source : submittedDraft;
        }
    }
}
