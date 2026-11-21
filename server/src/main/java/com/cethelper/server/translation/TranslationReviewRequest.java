package com.cethelper.server.translation;

/** 批改请求体：中文原文 + 学生自己写的英文译文。 */
public record TranslationReviewRequest(String sourceText, String studentTranslation) {
}
