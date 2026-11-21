package com.cethelper.server.translation;

/**
 * 一条具体修改项。
 *
 * @param type       {@code OMISSION}（漏译，quote 取自中文原文）或 {@code UNNATURAL}（表达不自然，quote 取自学生译文）
 * @param quote      被引用的原文片段：类型决定它必须逐字出现在中文原文还是学生译文里
 * @param detail     中文说明：漏了什么，或为什么不自然
 * @param suggestion 局部修改建议（不是整段参考译文）
 */
public record ReviewIssue(IssueType type, String quote, String detail, String suggestion) {
}
