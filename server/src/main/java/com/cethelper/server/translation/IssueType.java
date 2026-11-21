package com.cethelper.server.translation;

import java.util.Locale;

/**
 * 批改问题的类型。
 *
 * <p>只保留两类，因为两类的「引用」分别取自不同输入、校验规则明确：
 * {@link #OMISSION} 的 quote 必须来自中文原文，{@link #UNNATURAL} 的 quote 必须来自学生译文。
 * 其他问题（语序、拼写等）写在总体反馈里，避免无法逐字核对的引用。</p>
 */
public enum IssueType {

    /** 漏译：中文原文里的信息没有译出。 */
    OMISSION("OMISSION"),

    /** 表达不自然：学生译文中某段措辞不符合英语习惯。 */
    UNNATURAL("UNNATURAL");

    private final String value;

    IssueType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    /** 解析模型返回的类型；无法识别时返回 {@code null}。 */
    public static IssueType parse(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.strip().toUpperCase(Locale.ROOT);
        for (IssueType type : values()) {
            if (type.value.equals(normalized)) {
                return type;
            }
        }
        return null;
    }
}
