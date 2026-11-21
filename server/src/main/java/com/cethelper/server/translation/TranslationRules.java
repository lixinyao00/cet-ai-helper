package com.cethelper.server.translation;

import java.util.ArrayList;
import java.util.List;

/**
 * 汉译英两个接口共用的校验规则：长度上限、必填文本，以及「引用必须逐字来自对应输入」。
 *
 * <p>demo 内置数据与 live 模型输出走同一套校验：数据写错时服务启动失败，
 * 模型返回不符合约定时由 {@link LiveTranslationClient} 转成 502，都不会把不可核对的内容交给前端。</p>
 */
final class TranslationRules {

    /** 中文原文长度上限。 */
    static final int MAX_SOURCE_LENGTH = 2000;

    /** 学生英文译文长度上限（英文通常比中文长，放宽一些）。 */
    static final int MAX_TRANSLATION_LENGTH = 3000;

    /** 引用片段长度上限。 */
    static final int MAX_QUOTE_LENGTH = 200;

    /** 说明、建议这类长文本的长度上限。 */
    static final int MAX_TEXT_LENGTH = 500;

    /** 英文表达、分句作用这类短文本的长度上限。 */
    static final int MAX_SHORT_TEXT_LENGTH = 120;

    /** 一条批改最多返回的问题数。 */
    static final int MAX_ISSUES = 20;

    /** 关键词/短语最多返回的条目数。 */
    static final int MAX_HINT_ITEMS = 10;

    /** 单个条目最多给出的英文表达数。 */
    static final int MAX_EXPRESSIONS = 5;

    /** 结构提示最多返回的分句数与连接方式数。 */
    static final int MAX_CLAUSES = 8;
    static final int MAX_CONNECTIVES = 8;

    private TranslationRules() {
    }

    /** 去掉首尾空白；空白或超长时抛格式异常。 */
    static String requireText(String value, String what, int maxLength) {
        String text = value == null ? "" : value.strip();
        if (text.isEmpty()) {
            throw new TranslationFormatException(what + "为空");
        }
        if (text.length() > maxLength) {
            throw new TranslationFormatException(what + "过长：" + text.length());
        }
        return text;
    }

    /** 引用必须能在输入里逐字找到（去掉引用片段首尾空白后比较），否则视为编造引用。 */
    static String requireQuote(String input, String quote, String what) {
        String text = requireText(quote, what, MAX_QUOTE_LENGTH);
        if (input == null || !input.contains(text)) {
            throw new TranslationFormatException(what + "不是输入中的连续原文");
        }
        return text;
    }

    /** 校验关键词或短语条目列表：非空、条数与内容长度受限、中文片段必须是原文中的连续原文。 */
    static List<ExpressionHint> requireExpressionHints(String sourceText, List<ExpressionHint> hints, String label) {
        if (hints == null || hints.isEmpty()) {
            throw new TranslationFormatException(label + "为空");
        }
        if (hints.size() > MAX_HINT_ITEMS) {
            throw new TranslationFormatException(label + "条数超过上限：" + hints.size());
        }
        List<ExpressionHint> cleaned = new ArrayList<>(hints.size());
        for (ExpressionHint hint : hints) {
            if (hint == null) {
                throw new TranslationFormatException(label + "含空元素");
            }
            String source = requireQuote(sourceText, hint.source(), label + "的中文片段");
            List<String> expressions = requireExpressions(hint.expressions(), label + "的英文表达");
            String note = requireText(hint.note(), label + "的说明", MAX_TEXT_LENGTH);
            cleaned.add(new ExpressionHint(source, expressions, note));
        }
        return List.copyOf(cleaned);
    }

    private static List<String> requireExpressions(List<String> expressions, String what) {
        if (expressions == null || expressions.isEmpty()) {
            throw new TranslationFormatException(what + "为空");
        }
        if (expressions.size() > MAX_EXPRESSIONS) {
            throw new TranslationFormatException(what + "条数超过上限：" + expressions.size());
        }
        List<String> cleaned = new ArrayList<>(expressions.size());
        for (String expression : expressions) {
            cleaned.add(requireText(expression, what, MAX_SHORT_TEXT_LENGTH));
        }
        return List.copyOf(cleaned);
    }

    /** 校验结构提示：主干必填，分句与连接方式至少有一项，片段都必须是原文中的连续原文。 */
    static StructureHint requireStructureHint(String sourceText, StructureHint structure) {
        if (structure == null) {
            throw new TranslationFormatException("structure 为空");
        }
        String mainClause = requireText(structure.mainClause(), "structure.mainClause", MAX_TEXT_LENGTH);
        List<ClauseHint> clauses = structure.clauses();
        if (clauses.size() > MAX_CLAUSES) {
            throw new TranslationFormatException("clauses 条数超过上限：" + clauses.size());
        }
        List<ClauseHint> cleanedClauses = new ArrayList<>(clauses.size());
        for (ClauseHint clause : clauses) {
            if (clause == null) {
                throw new TranslationFormatException("clauses 含空元素");
            }
            cleanedClauses.add(new ClauseHint(
                    requireQuote(sourceText, clause.source(), "分句的中文片段"),
                    requireText(clause.role(), "分句的作用", MAX_SHORT_TEXT_LENGTH),
                    requireText(clause.guidance(), "分句的译法说明", MAX_TEXT_LENGTH)));
        }
        List<ConnectiveHint> connectives = structure.connectives();
        if (connectives.size() > MAX_CONNECTIVES) {
            throw new TranslationFormatException("connectives 条数超过上限：" + connectives.size());
        }
        List<ConnectiveHint> cleanedConnectives = new ArrayList<>(connectives.size());
        for (ConnectiveHint connective : connectives) {
            if (connective == null) {
                throw new TranslationFormatException("connectives 含空元素");
            }
            cleanedConnectives.add(new ConnectiveHint(
                    requireQuote(sourceText, connective.source(), "连接方式的中文片段"),
                    requireText(connective.suggestion(), "连接方式的建议", MAX_TEXT_LENGTH)));
        }
        if (cleanedClauses.isEmpty() && cleanedConnectives.isEmpty()) {
            throw new TranslationFormatException("clauses 与 connectives 不能同时为空");
        }
        return new StructureHint(mainClause, cleanedClauses, cleanedConnectives);
    }

    /**
     * 校验批改问题列表：可以整体为空（译文合格），但每条的类型、引用、说明、建议都必须成立，
     * 且引用必须逐字出现在对应输入里——漏译引用中文原文，表达不自然引用学生译文。
     */
    static List<ReviewIssue> requireIssues(String sourceText, String studentTranslation, List<ReviewIssue> issues) {
        if (issues == null) {
            throw new TranslationFormatException("issues 缺失或不是数组");
        }
        if (issues.size() > MAX_ISSUES) {
            throw new TranslationFormatException("issues 条数超过上限：" + issues.size());
        }
        List<ReviewIssue> cleaned = new ArrayList<>(issues.size());
        for (ReviewIssue issue : issues) {
            if (issue == null || issue.type() == null) {
                throw new TranslationFormatException("issue 缺少 type 或 type 不是 OMISSION/UNNATURAL");
            }
            String input = issue.type() == IssueType.OMISSION ? sourceText : studentTranslation;
            String label = issue.type() == IssueType.OMISSION ? "漏译引用的中文原文" : "表达不自然引用的学生译文";
            cleaned.add(new ReviewIssue(
                    issue.type(),
                    requireQuote(input, issue.quote(), label),
                    requireText(issue.detail(), "问题的说明", MAX_TEXT_LENGTH),
                    requireText(issue.suggestion(), "修改建议", MAX_TEXT_LENGTH)));
        }
        return List.copyOf(cleaned);
    }
}
