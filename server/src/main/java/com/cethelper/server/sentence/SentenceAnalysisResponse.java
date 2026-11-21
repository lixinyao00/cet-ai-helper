package com.cethelper.server.sentence;

import java.util.List;

/**
 * 整句分析成功响应。
 *
 * @param sentence    被分析的原句（与请求里规范化后的文本一致）
 * @param translation 自然通顺的中文翻译
 * @param structure   用中文概括的句子主干
 * @param chunks      按原句从前到后排列的语法片段，每个片段的 text 都是原句中的连续原文
 * @param mode        {@code demo} 表示内置演示分析，{@code live} 表示在线模型生成
 */
public record SentenceAnalysisResponse(String sentence,
                                       String translation,
                                       String structure,
                                       List<SentenceChunk> chunks,
                                       String mode) {

    public SentenceAnalysisResponse {
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
    }
}
