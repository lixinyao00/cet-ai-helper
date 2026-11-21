package com.cethelper.server.sentence;

/** 整句分析请求体：sentence 是学生在阅读页点选的英文原句。 */
public record SentenceAnalysisRequest(String sentence) {
}
