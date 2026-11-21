package com.cethelper.android;

import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.BackgroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.function.Consumer;

final class SentenceAnalysisPanel {
    private final ScrollView scroll;
    private final TextView original;
    private final TextView status;
    private final View mode;
    private final View result;
    private final View retry;
    private final LinearLayout fragments;
    private Consumer<String> onSpeak;
    private String currentSentence = "";

    SentenceAnalysisPanel(ScrollView scroll) {
        this.scroll = scroll;
        original = scroll.findViewById(R.id.analysis_original);
        status = scroll.findViewById(R.id.analysis_status);
        mode = scroll.findViewById(R.id.analysis_mode);
        result = scroll.findViewById(R.id.analysis_result);
        retry = scroll.findViewById(R.id.analysis_retry);
        fragments = scroll.findViewById(R.id.analysis_fragments);
        original.setOnClickListener(v -> {
            if (onSpeak != null && !currentSentence.isEmpty()) {
                onSpeak.accept(currentSentence);
            }
        });
    }

    void setOnSpeak(Consumer<String> onSpeak) {
        this.onSpeak = onSpeak;
    }

    void loading(String sentence) {
        currentSentence = sentence == null ? "" : sentence;
        original.setText(sentence);
        mode.setVisibility(View.GONE);
        result.setVisibility(View.GONE);
        fragments.removeAllViews();
        status.setText(R.string.analysis_loading);
        status.setVisibility(View.VISIBLE);
        retry.setVisibility(View.GONE);
        retry.setOnClickListener(null);
        scroll.scrollTo(0, 0);
    }

    void error(String message, Runnable onRetry) {
        status.setText(message);
        status.setVisibility(View.VISIBLE);
        retry.setOnClickListener(v -> onRetry.run());
        retry.setVisibility(View.VISIBLE);
    }

    void show(SentenceAnalysis analysis) {
        currentSentence = analysis.sentence();
        original.setText(analysis.sentence());
        status.setVisibility(View.GONE);
        retry.setVisibility(View.GONE);
        mode.setVisibility("demo".equals(analysis.mode()) ? View.VISIBLE : View.GONE);
        scroll.<TextView>findViewById(R.id.analysis_translation).setText(analysis.translation());
        scroll.<TextView>findViewById(R.id.analysis_structure).setText(analysis.structure());
        fragments.removeAllViews();

        LayoutInflater inflater = LayoutInflater.from(scroll.getContext());
        for (SentenceAnalysis.Fragment fragment : analysis.fragments()) {
            View row = inflater.inflate(R.layout.row_analysis_fragment, fragments, false);
            SentenceAnalysis.Chunk chunk = fragment.chunk();
            row.<TextView>findViewById(R.id.fragment_text).setText(chunk.text());
            row.<TextView>findViewById(R.id.fragment_role).setText(chunk.role());
            row.<TextView>findViewById(R.id.fragment_explanation).setText(chunk.explanation());
            row.setOnClickListener(v -> highlight(analysis.sentence(), fragment));
            fragments.addView(row);
        }
        result.setVisibility(View.VISIBLE);
    }

    private void highlight(String sentence, SentenceAnalysis.Fragment fragment) {
        SpannableString text = new SpannableString(sentence);
        text.setSpan(new BackgroundColorSpan(scroll.getContext().getColor(R.color.marker)),
                fragment.start(), fragment.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        original.setText(text);
        original.post(() -> {
            Layout layout = original.getLayout();
            if (layout != null && scroll.isShown()) {
                int line = layout.getLineForOffset(fragment.start());
                int top = original.getTop() + original.getTotalPaddingTop() + layout.getLineTop(line);
                int bottom = original.getTop() + original.getTotalPaddingTop() + layout.getLineBottom(line);
                if (scroll.getScrollY() > top || scroll.getScrollY() + scroll.getHeight() < bottom) {
                    scroll.scrollTo(0, bottom <= scroll.getHeight() ? 0 : top);
                }
            }
        });
    }
}
