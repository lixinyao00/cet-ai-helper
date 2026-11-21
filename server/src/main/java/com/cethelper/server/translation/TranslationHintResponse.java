package com.cethelper.server.translation;

import java.util.List;

/**
 * 提示成功响应：只包含学生所选类型的那一部分，其余字段不会出现在 JSON 里（Jackson 已配置 non_null）。
 *
 * @param sourceText 中文原文（去掉首尾空白后与请求一致）
 * @param hintType   {@code keyword} / {@code phrase} / {@code structure}
 * @param keywords   仅 {@code hintType=keyword} 时出现
 * @param phrases    仅 {@code hintType=phrase} 时出现
 * @param structure  仅 {@code hintType=structure} 时出现
 * @param mode       {@code demo} 或 {@code live}
 */
public record TranslationHintResponse(String sourceText,
                                      String hintType,
                                      List<ExpressionHint> keywords,
                                      List<ExpressionHint> phrases,
                                      StructureHint structure,
                                      String mode) {

    public TranslationHintResponse {
        keywords = keywords == null ? null : List.copyOf(keywords);
        phrases = phrases == null ? null : List.copyOf(phrases);
    }
}
