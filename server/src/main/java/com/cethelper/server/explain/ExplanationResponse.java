package com.cethelper.server.explain;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 释义成功响应。
 *
 * <p>原有三个字段保持不变，新增字段均为可选：在线模型或内置词表没有给出时不输出（{@code NON_NULL}），
 * 旧版客户端仍能按原契约解析。</p>
 *
 * @param meaning      短释义（中文）
 * @param explanation  结合原句的用法解释（中文）
 * @param mode         {@code demo} 表示内置演示释义，{@code live} 表示在线模型生成
 * @param phonetic     音标，如 {@code /dɪˈklaɪn/}，没有时为空
 * @param partOfSpeech 词性，如 {@code n.} / {@code v.} / {@code phr.}，没有时为空
 * @param lemma        原形（词条），如 {@code decline}，没有时为空
 * @param cetLevel     词频/考纲级别，如 {@code CET-4}，没有时为空
 * @param contextual   {@code true} 表示释义贴合原句语境，{@code false} 表示来自内置词表的通用释义
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExplanationResponse(String meaning,
                                  String explanation,
                                  String mode,
                                  String phonetic,
                                  String partOfSpeech,
                                  String lemma,
                                  String cetLevel,
                                  Boolean contextual) {

    /** 兼容旧调用的三参构造：可选字段留空，按语境释义处理。 */
    public ExplanationResponse(String meaning, String explanation, String mode) {
        this(meaning, explanation, mode, null, null, null, null, true);
    }
}
