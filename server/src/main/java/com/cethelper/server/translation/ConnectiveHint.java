package com.cethelper.server.translation;

/**
 * 结构提示中的一处连接方式。
 *
 * @param source     中文原文中的连续片段（逐字摘自 sourceText），例如「因此」「那些」
 * @param suggestion 可用的英文连接表达或引出方式
 */
public record ConnectiveHint(String source, String suggestion) {
}
