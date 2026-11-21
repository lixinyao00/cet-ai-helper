package com.cethelper.server.translation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 关键词或短语提示的一项。
 *
 * @param source      中文原文中的连续片段（逐字摘自 sourceText）
 * @param expressions 可考虑的英文表达，至少一个
 * @param note        简短中文说明（怎么用、注意什么）
 */
public record ExpressionHint(String source, List<String> expressions, String note) {

    public ExpressionHint {
        // 这里刻意容忍列表里的 null（如上游返回 [null]）：真正的类型校验在 TranslationRules 里完成，
        // 由它抛出格式异常并映射成 502；若在此处用 List.copyOf，null 元素会先抛 NPE 变成 500。
        expressions = expressions == null ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(expressions));
    }
}
