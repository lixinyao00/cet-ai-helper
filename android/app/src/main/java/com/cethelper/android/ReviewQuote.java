package com.cethelper.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.view.OneShotPreDrawListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class ReviewQuote {
    record Range(int start, int end) {}

    static List<Range> findMatches(String snapshot, String quote) {
        if (quote.isEmpty()) {
            return Collections.emptyList();
        }
        List<Range> matches = new ArrayList<>();
        // Advance one UTF-16 code unit to include overlapping occurrences.
        for (int start = snapshot.indexOf(quote); start >= 0; start = snapshot.indexOf(quote, start + 1)) {
            matches.add(new Range(start, start + quote.length()));
        }
        return Collections.unmodifiableList(matches);
    }

    static void bind(Activity host, View row, int category, String quote, String detail, String suggestion,
                     int snapshotTitle, String snapshot, List<Range> matches) {
        Button button = row.findViewById(R.id.review_issue_quote);
        button.setText(host.getString(R.string.review_issue_quote, host.getString(category), quote));
        row.<TextView>findViewById(R.id.review_issue_detail).setText(detail);
        row.<TextView>findViewById(R.id.review_issue_suggestion)
                .setText(host.getString(R.string.review_issue_suggestion, suggestion));
        if (matches.size() > 1) {
            TextView candidates = row.findViewById(R.id.review_issue_candidates);
            candidates.setText(host.getString(R.string.review_issue_candidates, matches.size()));
            candidates.setVisibility(View.VISIBLE);
        }
        button.setOnClickListener(v -> {
            host.getSystemService(InputMethodManager.class).hideSoftInputFromWindow(v.getWindowToken(), 0);
            show(host, snapshotTitle, snapshot, matches);
        });
    }

    private static void show(Activity host, int title, String snapshot, List<Range> matches) {
        ScrollView scroll = (ScrollView) host.getLayoutInflater().inflate(R.layout.dialog_review_quote, null);
        TextView text = scroll.findViewById(R.id.review_snapshot);
        text.setText(highlight(host, snapshot, matches));
        new AlertDialog.Builder(host).setTitle(title).setView(scroll)
                .setPositiveButton(android.R.string.ok, null).show();
        OneShotPreDrawListener.add(text, () -> {
            int firstLine = text.getLayout().getLineForOffset(matches.get(0).start());
            scroll.scrollTo(0, text.getTop() + text.getLayout().getLineTop(firstLine));
        });
    }

    static CharSequence highlight(Context context, String snapshot, List<Range> matches) {
        if (matches.isEmpty()) {
            return snapshot;
        }
        SpannableString highlighted = new SpannableString(snapshot);
        for (Range match : matches) {
            highlighted.setSpan(new BackgroundColorSpan(context.getColor(R.color.marker)),
                    match.start(), match.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return highlighted;
    }
}
