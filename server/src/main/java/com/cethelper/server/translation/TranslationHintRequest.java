package com.cethelper.server.translation;

/** 提示请求体：中文原文 + 学生本次想要的提示类型。 */
public record TranslationHintRequest(String sourceText, String hintType) {
}
