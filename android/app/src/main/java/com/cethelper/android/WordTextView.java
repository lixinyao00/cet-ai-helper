package com.cethelper.android;

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.Layout;
import android.text.Spannable;
import android.text.style.BackgroundColorSpan;
import android.text.style.UnderlineSpan;
import android.util.AttributeSet;
import android.view.ActionMode;
import android.view.GestureDetector;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.textclassifier.TextClassifier;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// This screen uses the framework theme and TextView's native selection action mode.
@SuppressLint("AppCompatCustomView")
public final class WordTextView extends TextView {
    interface OnLookup {
        void lookup(int start, int end);
    }

    private static final int LOOKUP_ITEM = 1;
    private static final int SAVED_HIGHLIGHT_COLOR = 0x22075B53;
    private static final Pattern WORD = Pattern.compile("\\p{L}[\\p{L}\\p{M}]*(?:['’‐‑-][\\p{L}\\p{M}]+)*");

    private final GestureDetector taps;
    private final List<Object> savedSpans = new ArrayList<>();
    private OnLookup listener;
    private ActionMode selectionMode;
    private BackgroundColorSpan marker;

    public WordTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setTextClassifier(TextClassifier.NO_OP);
        taps = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent event) {
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent event) {
                if (selectionMode == null) {
                    lookUpWordAt(event.getX(), event.getY());
                }
                return true;
            }
        });
        setCustomSelectionActionModeCallback(new ActionMode.Callback() {
            @Override
            public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                selectionMode = mode;
                menu.add(Menu.NONE, LOOKUP_ITEM, 0, R.string.lookup)
                        .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
                return true;
            }

            @Override
            public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                return false;
            }

            @Override
            public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                if (item.getItemId() != LOOKUP_ITEM) {
                    return false;
                }
                int start = Math.min(getSelectionStart(), getSelectionEnd());
                int end = Math.max(getSelectionStart(), getSelectionEnd());
                mode.finish();
                if (listener != null && start >= 0 && start < end) {
                    listener.lookup(start, end);
                }
                return true;
            }

            @Override
            public void onDestroyActionMode(ActionMode mode) {
                selectionMode = null;
            }
        });
    }

    void setParagraph(String sentence, OnLookup listener) {
        savedSpans.clear();
        marker = null;
        setText(sentence, BufferType.SPANNABLE);
        this.listener = listener;
    }

    void setSavedRanges(List<int[]> ranges) {
        CharSequence current = getText();
        if (!(current instanceof Spannable text)) {
            return;
        }
        for (Object span : savedSpans) {
            text.removeSpan(span);
        }
        savedSpans.clear();
        if (ranges == null || ranges.isEmpty()) {
            return;
        }
        int length = text.length();
        for (int[] range : ranges) {
            if (range == null || range.length < 2) {
                continue;
            }
            int start = range[0];
            int end = range[1];
            if (start >= 0 && start < end && end <= length) {
                UnderlineSpan underline = new UnderlineSpan();
                BackgroundColorSpan tint = new BackgroundColorSpan(SAVED_HIGHLIGHT_COLOR);
                text.setSpan(tint, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                text.setSpan(underline, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                savedSpans.add(tint);
                savedSpans.add(underline);
            }
        }
    }

    void mark(int start, int end) {
        Spannable text = (Spannable) getText();
        if (marker != null) {
            text.removeSpan(marker);
            marker = null;
        }
        if (start >= 0 && start < end && end <= text.length()) {
            marker = new BackgroundColorSpan(getContext().getColor(R.color.marker));
            text.setSpan(marker, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        taps.onTouchEvent(event);
        return super.onTouchEvent(event);
    }

    private void lookUpWordAt(float x, float y) {
        Layout layout = getLayout();
        if (layout == null || listener == null) {
            return;
        }
        float textX = x - getTotalPaddingLeft() + getScrollX();
        int line = layout.getLineForVertical((int) (y - getTotalPaddingTop() + getScrollY()));
        if (textX < layout.getLineLeft(line) || textX >= layout.getLineRight(line)) {
            return;
        }

        // Layout gives the nearest caret. English text begins at the caret to the left of the tap.
        int offset = layout.getOffsetForHorizontal(line, textX);
        if (offset > layout.getLineStart(line) && textX < layout.getPrimaryHorizontal(offset)) {
            offset--;
        }
        Matcher words = WORD.matcher(getText());
        while (words.find()) {
            if (words.start() <= offset && offset < words.end()) {
                int start = words.start();
                int end = words.end();
                post(() -> listener.lookup(start, end));
                return;
            }
        }
    }
}
