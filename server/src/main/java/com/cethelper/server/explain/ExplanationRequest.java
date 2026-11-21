package com.cethelper.server.explain;

/** 释义请求体：selection 是用户在 sentence 中选中的连续原文。 */
public record ExplanationRequest(String selection, String sentence) {
}
