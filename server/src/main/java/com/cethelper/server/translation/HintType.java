package com.cethelper.server.translation;

import java.util.Locale;

/**
 * 汉译英提示类型：学生每次只请求一种，接口只返回这一种。
 *
 * <p>取值固定为 {@code keyword}、{@code phrase}、{@code structure}（大小写与首尾空白不敏感），
 * 其他取值由服务层转成 400 {@code INVALID_INPUT}。</p>
 */
public enum HintType {

    /** 关键词：给出中文关键词、可考虑的英文表达与简短说明。 */
    KEYWORD("keyword", "关键词"),

    /** 短语：给出中文短语、可考虑的英文表达与简短说明。 */
    PHRASE("phrase", "短语"),

    /** 结构：说明主干、分句与连接方式。 */
    STRUCTURE("structure", "结构");

    private final String value;
    private final String label;

    HintType(String value, String label) {
        this.value = value;
        this.label = label;
    }

    public String value() {
        return value;
    }

    public String label() {
        return label;
    }

    /** 解析请求里的提示类型；无法识别时返回 {@code null}，由调用方给 400。 */
    public static HintType parse(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.strip().toLowerCase(Locale.ROOT);
        for (HintType type : values()) {
            if (type.value.equals(normalized)) {
                return type;
            }
        }
        return null;
    }
}
