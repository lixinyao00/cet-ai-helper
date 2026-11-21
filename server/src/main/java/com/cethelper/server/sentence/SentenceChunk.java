package com.cethelper.server.sentence;

/**
 * 长难句拆解中的一个片段。
 *
 * @param text        原句中的连续片段，保留原文用词（不改写、不翻译、不调整大小写与标点）
 * @param role        该片段在本句中的语法作用（中文），例如「主语」「宾语从句」
 * @param explanation 该片段在本句里怎样理解（中文）
 */
public record SentenceChunk(String text, String role, String explanation) {
}
