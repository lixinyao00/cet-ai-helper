package com.cethelper.server.article;

/**
 * 文章整理结果。
 *
 * @param title   文章标题；翻译原文整理时固定为空字符串
 * @param body    清理后的正文，自然段之间使用 {@code \n\n} 分隔
 * @param summary 简短中文说明
 * @param mode    {@code demo} 或 {@code live}
 */
public record ArticleOrganizeResponse(String title, String body, String summary, String mode) {
}
