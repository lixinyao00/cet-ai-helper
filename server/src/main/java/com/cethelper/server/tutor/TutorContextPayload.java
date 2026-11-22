package com.cethelper.server.tutor;

/**
 * 助教上下文：从阅读/翻译/写作页面带入的结构化信息。
 *
 * <p>{@code type} 可取 FREE / WORD / SENTENCE / TRANSLATION / WRITING（大小写不敏感）。</p>
 */
public record TutorContextPayload(String type, String title, String focusText,
                                  String referenceText, String extraNote) {

    public static final String FREE = "FREE";
    public static final String WORD = "WORD";
    public static final String SENTENCE = "SENTENCE";
    public static final String TRANSLATION = "TRANSLATION";
    public static final String WRITING = "WRITING";

    public static TutorContextPayload free() {
        return new TutorContextPayload(FREE, null, null, null, null);
    }
}
