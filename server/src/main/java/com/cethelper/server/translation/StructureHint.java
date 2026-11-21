package com.cethelper.server.translation;

import java.util.List;

/**
 * 结构提示：主干说明 + 分句 + 连接方式。
 *
 * @param mainClause  用中文概括的句子主干（主语/谓语/宾语或表语）
 * @param clauses     需要单独处理的分句（可为空）
 * @param connectives 连接方式（可为空；分句与连接方式不能同时为空）
 */
public record StructureHint(String mainClause, List<ClauseHint> clauses, List<ConnectiveHint> connectives) {

    public StructureHint {
        clauses = clauses == null ? List.of() : List.copyOf(clauses);
        connectives = connectives == null ? List.of() : List.copyOf(connectives);
    }
}
