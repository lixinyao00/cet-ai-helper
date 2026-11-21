package com.cethelper.server.translation;

/**
 * 结构提示中的一个分句。
 *
 * @param source   中文原文中的连续片段（逐字摘自 sourceText）
 * @param role     该分句在整句里的作用，例如「时间状语」「修饰年轻人的定语从句」
 * @param guidance 中文说明怎样译（用从句、分词还是名词短语）
 */
public record ClauseHint(String source, String role, String guidance) {
}
