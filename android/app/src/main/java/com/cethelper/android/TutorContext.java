package com.cethelper.android;

import android.os.Bundle;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

final class TutorContext {
    static final String FREE = "FREE";
    static final String WORD = "WORD";
    static final String SENTENCE = "SENTENCE";
    static final String TRANSLATION = "TRANSLATION";
    static final String WRITING = "WRITING";

    private static final int MAX_TITLE = 200;
    private static final int MAX_FOCUS = 2000;
    private static final int MAX_REFERENCE = 6000;
    private static final int MAX_EXTRA = 4000;
    private static final int MAX_SUMMARY = 60;

    final String type;
    final String title;
    final String focusText;
    final String referenceText;
    final String extraNote;

    TutorContext(String type, String title, String focusText, String referenceText, String extraNote) {
        this.type = normalizeType(type);
        this.title = safe(title);
        this.focusText = safe(focusText);
        this.referenceText = safe(referenceText);
        this.extraNote = safe(extraNote);
    }

    static TutorContext free() {
        return new TutorContext(FREE, "", "", "", "");
    }

    JSONObject toJson() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("type", type);
        if (!title.isEmpty()) {
            obj.put("title", clamp(title, MAX_TITLE));
        }
        if (!focusText.isEmpty()) {
            obj.put("focusText", clamp(focusText, MAX_FOCUS));
        }
        if (!referenceText.isEmpty()) {
            obj.put("referenceText", clamp(referenceText, MAX_REFERENCE));
        }
        if (!extraNote.isEmpty()) {
            obj.put("extraNote", clamp(extraNote, MAX_EXTRA));
        }
        return obj;
    }

    Bundle toBundle() {
        Bundle b = new Bundle();
        b.putString("type", type);
        b.putString("title", title);
        b.putString("focusText", focusText);
        b.putString("referenceText", referenceText);
        b.putString("extraNote", extraNote);
        return b;
    }

    static TutorContext fromBundle(Bundle b) {
        if (b == null) {
            return free();
        }
        return new TutorContext(
                b.getString("type", FREE),
                b.getString("title", ""),
                b.getString("focusText", ""),
                b.getString("referenceText", ""),
                b.getString("extraNote", "")
        );
    }

    String summary() {
        if (FREE.equals(type)) {
            return "自由提问四六级词汇、语法、阅读、翻译或写作问题";
        }
        String candidate = !focusText.isEmpty() ? focusText
                : (!title.isEmpty() ? title : referenceText);
        String collapsed = candidate.replaceAll("\\s+", " ").trim();
        if (collapsed.isEmpty()) {
            return "当前学习上下文";
        }
        if (collapsed.length() <= MAX_SUMMARY) {
            return collapsed;
        }
        return collapsed.substring(0, MAX_SUMMARY - 1) + "…";
    }

    String sessionKey() {
        if (FREE.equals(type)) {
            return "free";
        }
        return type + ":" + focusText.hashCode();
    }

    List<String> quickPrompts() {
        return switch (type) {
            case WORD -> List.of(
                    "这个词在四六级真题里常见搭配有哪些？",
                    "它和近义词有什么区别？",
                    "能再给我两个双语例句吗？",
                    "它有哪些常见的词性变形？"
            );
            case SENTENCE -> List.of(
                    "请通俗拆解这句话的从句结构",
                    "这句话里有哪些四六级核心词汇或短语？",
                    "如何把这句话翻译得更地道？"
            );
            case TRANSLATION -> List.of(
                    "我的译文最需要先修改哪一处？",
                    "能给我一个更高分的地道改写吗？",
                    "这道题涉及哪些常考翻译句型？"
            );
            case WRITING -> List.of(
                    "如何把我的作文提升到高分档？",
                    "请帮我润色其中的薄弱句子",
                    "这个话题有哪些四六级亮点表达？"
            );
            default -> List.of(
                    "四六级阅读如何快速定位答案？",
                    "帮我梳理高频虚拟语气考点",
                    "汉译英常用高级衔接词有哪些？",
                    "作文开头段怎么写更有亮点？"
            );
        };
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TutorContext other)) {
            return false;
        }
        return type.equals(other.type)
                && title.equals(other.title)
                && focusText.equals(other.focusText)
                && referenceText.equals(other.referenceText)
                && extraNote.equals(other.extraNote);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, title, focusText, referenceText, extraNote);
    }

    private static String normalizeType(String raw) {
        if (raw == null) {
            return FREE;
        }
        String upper = raw.trim().toUpperCase(Locale.ROOT);
        return switch (upper) {
            case WORD, SENTENCE, TRANSLATION, WRITING -> upper;
            default -> FREE;
        };
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String clamp(String value, int maxLen) {
        return value.length() <= maxLen ? value : value.substring(0, maxLen);
    }
}
