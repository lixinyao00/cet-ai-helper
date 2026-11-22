package com.cethelper.server.article;

/**
 * 文章整理请求：{@code rawText} 与 {@code imageBase64} 至少提供一个。
 *
 * <p>{@code target} 省略时按 {@code reading} 处理；{@code imageMimeType} 省略时按 JPEG 处理。</p>
 */
public record ArticleOrganizeRequest(String rawText, String imageBase64, String imageMimeType, String target) {
}
