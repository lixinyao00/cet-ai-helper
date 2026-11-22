package com.cethelper.android;

import android.app.AlertDialog;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

final class TutorScreen {
    private final MainActivity host;
    private final TutorState state;
    private final boolean asTab;

    private View page;
    private TextView contextTypeView;
    private TextView contextTitleView;
    private TextView contextSummaryView;
    private View contextClearButton;
    private ScrollView messagesScroll;
    private LinearLayout messagesContainer;
    private TextView statusView;
    private HorizontalScrollView followUpsScroll;
    private LinearLayout followUpsContainer;
    private LinearLayout quickPromptsContainer;
    private View clearButton;
    private EditText inputField;
    private View sendButton;
    private View stopButton;

    private String renderedSessionKey;
    private int renderedCount;
    private View streamingBubble;
    private TextView streamingBubbleText;
    private TextView streamingBubbleMode;
    private String lastStreamingText = "";

    TutorScreen(MainActivity host, TutorState state, boolean asTab) {
        this.host = host;
        this.state = state;
        this.asTab = asTab;
    }

    void show() {
        page = asTab
                ? host.showL1Screen(R.layout.screen_tutor, NavEntry.Screen.TUTOR)
                : host.showL2Screen(R.layout.screen_tutor, host.getString(R.string.tutor_title));

        View topBack = page.findViewById(R.id.top_bar_back);
        TextView topTitle = page.findViewById(R.id.top_bar_title);
        if (asTab) {
            UiViews.setVisible(topBack, false);
            if (topTitle != null) {
                topTitle.setText(R.string.tutor_title);
            }
        }

        contextTypeView = page.findViewById(R.id.tutor_context_type);
        contextTitleView = page.findViewById(R.id.tutor_context_title);
        contextSummaryView = page.findViewById(R.id.tutor_context_summary);
        contextClearButton = page.findViewById(R.id.tutor_context_clear);
        messagesScroll = page.findViewById(R.id.tutor_messages_scroll);
        messagesContainer = page.findViewById(R.id.tutor_messages);
        statusView = page.findViewById(R.id.tutor_status);
        followUpsScroll = page.findViewById(R.id.tutor_followups_scroll);
        followUpsContainer = page.findViewById(R.id.tutor_followups);
        quickPromptsContainer = page.findViewById(R.id.tutor_quick_prompts);
        clearButton = page.findViewById(R.id.tutor_clear);
        inputField = page.findViewById(R.id.tutor_input);
        sendButton = page.findViewById(R.id.tutor_send);
        stopButton = page.findViewById(R.id.tutor_stop);

        contextClearButton.setOnClickListener(v -> state.attach(host, TutorContext.free()));
        sendButton.setOnClickListener(v -> submitInput());
        stopButton.setOnClickListener(v -> state.stop());
        clearButton.setOnClickListener(v -> confirmClear());

        renderedSessionKey = null;
        renderedCount = 0;
        streamingBubble = null;
        streamingBubbleText = null;
        streamingBubbleMode = null;
        lastStreamingText = "";

        state.setListener(() -> {
            if (host.isCurrent(page)) {
                render();
            }
        });
        render();
    }

    void release() {
        state.setListener(null);
    }

    private void submitInput() {
        if (state.generating) {
            return;
        }
        String text = inputField.getText().toString().trim();
        if (text.isEmpty()) {
            return;
        }
        inputField.setText("");
        hideKeyboard();
        state.send(host, text);
    }

    private void confirmClear() {
        if ((state.messages == null || state.messages.isEmpty()) && !state.generating) {
            return;
        }
        new AlertDialog.Builder(host)
                .setTitle(R.string.tutor_clear_title)
                .setMessage(R.string.tutor_clear_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.clear_confirm, (dialog, which) -> state.clear(host))
                .show();
    }

    private void render() {
        TutorContext ctx = state.context != null ? state.context : TutorContext.free();
        renderContextCard(ctx);
        renderQuickPrompts(ctx);
        renderMessages(ctx);
        renderFollowUps();
        renderControls();
    }

    private void renderContextCard(TutorContext ctx) {
        boolean isFree = TutorContext.FREE.equalsIgnoreCase(UiViews.safeText(ctx.type)) || ctx.type == null;
        contextTypeView.setText(formatContextType(ctx.type));
        String title = UiViews.safeText(ctx.title);
        if (title.isEmpty()) {
            title = isFree ? host.getString(R.string.tutor_context_free) : formatContextType(ctx.type);
        }
        contextTitleView.setText(title);
        String summary = UiViews.safeText(ctx.summary());
        if (summary.isEmpty() && isFree) {
            summary = host.getString(R.string.tutor_context_free_summary);
        }
        contextSummaryView.setText(summary);
        UiViews.setVisible(contextSummaryView, !summary.isEmpty());
        UiViews.setVisible(contextClearButton, !isFree);
    }

    private void renderQuickPrompts(TutorContext ctx) {
        String key = UiViews.safeText(ctx.sessionKey());
        if (key.equals(renderedSessionKey) && quickPromptsContainer.getChildCount() > 0) {
            return;
        }
        quickPromptsContainer.removeAllViews();
        List<String> prompts = ctx.quickPrompts();
        if (prompts == null || prompts.isEmpty()) {
            return;
        }
        int marginEnd = dp(8);
        for (String prompt : prompts) {
            TextView chip = new TextView(host, null, 0, R.style.StudyChip);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(marginEnd);
            chip.setLayoutParams(lp);
            chip.setText(prompt);
            chip.setOnClickListener(v -> {
                if (!state.generating) {
                    hideKeyboard();
                    state.send(host, prompt);
                }
            });
            quickPromptsContainer.addView(chip);
        }
    }

    private void renderMessages(TutorContext ctx) {
        String key = UiViews.safeText(ctx.sessionKey());
        List<TutorMessage> messages = state.messages;
        int total = messages == null ? 0 : messages.size();

        if (!key.equals(renderedSessionKey) || total < renderedCount) {
            messagesContainer.removeAllViews();
            renderedSessionKey = key;
            renderedCount = 0;
            streamingBubble = null;
            streamingBubbleText = null;
            streamingBubbleMode = null;
            lastStreamingText = "";
        }

        boolean shouldScroll = false;
        LayoutInflater inflater = LayoutInflater.from(host);

        if (total > renderedCount && streamingBubble != null) {
            messagesContainer.removeView(streamingBubble);
            streamingBubble = null;
            streamingBubbleText = null;
            streamingBubbleMode = null;
            lastStreamingText = "";
        }

        for (int i = renderedCount; i < total; i++) {
            TutorMessage msg = messages.get(i);
            boolean isUser = "user".equalsIgnoreCase(UiViews.safeText(msg.role));
            View row = inflater.inflate(
                    isUser ? R.layout.row_tutor_message_user : R.layout.row_tutor_message_assistant,
                    messagesContainer, false);
            row.<TextView>findViewById(R.id.tutor_bubble_text).setText(UiViews.safeText(msg.content));
            if (!isUser) {
                TextView modeBadge = row.findViewById(R.id.tutor_bubble_mode);
                bindModeBadge(modeBadge, msg.mode);
            }
            messagesContainer.addView(row);
            shouldScroll = true;
        }
        renderedCount = total;

        String streamingText = state.streaming == null ? "" : state.streaming.toString();
        if (state.generating || !streamingText.isEmpty()) {
            if (streamingBubble == null) {
                streamingBubble = inflater.inflate(
                        R.layout.row_tutor_message_assistant, messagesContainer, false);
                streamingBubbleText = streamingBubble.findViewById(R.id.tutor_bubble_text);
                streamingBubbleMode = streamingBubble.findViewById(R.id.tutor_bubble_mode);
                messagesContainer.addView(streamingBubble);
                shouldScroll = true;
            }
            bindModeBadge(streamingBubbleMode, state.mode);
            String display = streamingText.isEmpty() ? "正在思考…" : streamingText;
            if (!display.equals(lastStreamingText)) {
                streamingBubbleText.setText(display);
                lastStreamingText = display;
                shouldScroll = true;
            }
        } else if (streamingBubble != null) {
            messagesContainer.removeView(streamingBubble);
            streamingBubble = null;
            streamingBubbleText = null;
            streamingBubbleMode = null;
            lastStreamingText = "";
        }

        if (shouldScroll) {
            messagesScroll.post(() -> {
                if (host.isCurrent(page)) {
                    messagesScroll.fullScroll(View.FOCUS_DOWN);
                }
            });
        }
    }

    private void renderFollowUps() {
        followUpsContainer.removeAllViews();
        List<String> followUps = state.followUps;
        if (state.generating || followUps == null || followUps.isEmpty()) {
            UiViews.setVisible(followUpsScroll, false);
            followUpsContainer.setVisibility(View.GONE);
            return;
        }
        UiViews.setVisible(followUpsScroll, true);
        followUpsContainer.setVisibility(View.VISIBLE);
        int marginEnd = dp(8);
        for (String prompt : followUps) {
            TextView chip = new TextView(host, null, 0, R.style.StudyChip);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(marginEnd);
            chip.setLayoutParams(lp);
            chip.setText(prompt);
            chip.setOnClickListener(v -> {
                if (!state.generating) {
                    hideKeyboard();
                    state.send(host, prompt);
                }
            });
            followUpsContainer.addView(chip);
        }
    }

    private void renderControls() {
        boolean generating = state.generating;
        UiViews.setVisible(sendButton, !generating);
        UiViews.setVisible(stopButton, generating);

        if (state.error != null && !state.error.isEmpty()) {
            statusView.setText(state.error);
            statusView.setTextColor(host.getColor(R.color.danger));
            statusView.setVisibility(View.VISIBLE);
        } else if ((state.messages == null || state.messages.isEmpty()) && !generating) {
            statusView.setText(R.string.tutor_empty_hint);
            statusView.setTextColor(host.getColor(R.color.muted));
            statusView.setVisibility(View.VISIBLE);
        } else {
            statusView.setVisibility(View.GONE);
        }
    }

    private static void bindModeBadge(TextView badge, String mode) {
        if (badge == null) {
            return;
        }
        String normalized = UiViews.safeText(mode).trim();
        if (normalized.isEmpty()) {
            badge.setVisibility(View.GONE);
            return;
        }
        badge.setText("demo".equalsIgnoreCase(normalized) ? "演示助教" : "AI 助教");
        badge.setVisibility(View.VISIBLE);
    }

    private static String formatContextType(String type) {
        if (type == null) {
            return "自由问答";
        }
        return switch (type.toUpperCase(java.util.Locale.ROOT)) {
            case TutorContext.WORD -> "单词上下文";
            case TutorContext.SENTENCE -> "句法上下文";
            case TutorContext.TRANSLATION -> "翻译上下文";
            case TutorContext.WRITING -> "写作上下文";
            default -> "自由问答";
        };
    }

    private void hideKeyboard() {
        InputMethodManager imm = host.getSystemService(InputMethodManager.class);
        if (imm != null && inputField != null) {
            imm.hideSoftInputFromWindow(inputField.getWindowToken(), 0);
        }
    }

    private int dp(int value) {
        return Math.round(value * host.getResources().getDisplayMetrics().density);
    }
}
