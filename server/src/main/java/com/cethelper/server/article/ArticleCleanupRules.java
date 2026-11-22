package com.cethelper.server.article;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * demo 模式下的确定性整理规则：过滤常见考试干扰行，合并断行并保留自然段。
 *
 * <p>规则只做格式整理，不改写措辞；无法从原文可靠判断标题时返回空标题。</p>
 */
public final class ArticleCleanupRules {

    /** 单独占一行的页码，例如 {@code 12}、{@code - 3 -}。 */
    private static final Pattern PAGE_NUMBER = Pattern.compile("^[-–—]?\\s*\\d{1,3}\\s*[-–—]?$");

    /** 考试指令行，例如 {@code Directions: ...}、{@code Part III}、{@code Section A}。 */
    private static final Pattern DIRECTIONS = Pattern.compile(
            "(?i)^(directions?|part\\s+[ivxlc]+|section\\s+[a-d])\\b.*$");

    /** 选择题选项行，例如 {@code A) ...}、{@code B. ...}。 */
    private static final Pattern OPTION = Pattern.compile("^[A-D][\\).、]\\s+.*$");

    /** 孤立成行的带问号题号，例如 {@code 1. What is the main idea?}。 */
    private static final Pattern QUESTION = Pattern.compile("^\\d{1,2}\\s*[.、)]\\s+.*\\?\\s*$");

    /** 英文行尾连字符断词，例如 {@code stu-\ndents}。 */
    private static final Pattern HYPHEN_BREAK = Pattern.compile("([A-Za-z])-[ \\t]*\\n[ \\t]*([a-z])");

    /** 一个或多个空行，用于切分自然段。 */
    private static final Pattern PARAGRAPH_BREAK = Pattern.compile("\\n[ \\t]*\\n+");

    private ArticleCleanupRules() {
    }

    /**
     * 清理 OCR 文本。
     *
     * @param rawText 端侧识别出的原始文本
     * @param target  {@code reading} 或 {@code translation}
     */
    public static CleanedArticle clean(String rawText, String target) {
        String normalized = normalizeNewlines(rawText);
        List<String> lines = splitLines(normalized);

        TitleMatch title = detectTitle(lines);
        StringBuilder kept = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i == title.lineIndex()) {
                continue;
            }
            String line = lines.get(i);
            if (isNoise(line.strip())) {
                continue;
            }
            kept.append(line).append('\n');
        }

        String filtered = kept.toString();
        if ("reading".equals(target)) {
            filtered = HYPHEN_BREAK.matcher(filtered).replaceAll("$1$2");
        }
        String body = mergeWrappedLines(filtered, target);
        if (body.isBlank()) {
            // 过滤后什么都不剩时退回原文，避免把正常短文清空。
            title = TitleMatch.none();
            body = normalized.strip();
        }
        return new CleanedArticle(title.title(), body);
    }

    /** 把段落内的软换行合并成一行，段落之间统一保留一个空行。 */
    public static String mergeWrappedLines(String text, String target) {
        String normalized = normalizeNewlines(text);
        List<String> paragraphs = new ArrayList<>();
        for (String paragraph : PARAGRAPH_BREAK.split(normalized, -1)) {
            StringBuilder merged = new StringBuilder();
            for (String line : paragraph.split("\n", -1)) {
                String trimmed = line.strip();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (merged.length() == 0) {
                    merged.append(trimmed);
                } else {
                    merged.append(separator(merged.charAt(merged.length() - 1), trimmed.charAt(0)))
                            .append(trimmed);
                }
            }
            String cleanedParagraph = merged.toString().strip();
            if (!cleanedParagraph.isEmpty()) {
                paragraphs.add(cleanedParagraph);
            }
        }
        return String.join("\n\n", paragraphs);
    }

    private static String normalizeNewlines(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r\n", "\n").replace('\r', '\n').replace('\u00a0', ' ');
    }

    private static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            lines.add(line);
        }
        return lines;
    }

    private static boolean isNoise(String line) {
        if (line.isEmpty()) {
            return false;
        }
        return PAGE_NUMBER.matcher(line).matches()
                || DIRECTIONS.matcher(line).matches()
                || OPTION.matcher(line).matches()
                || QUESTION.matcher(line).matches();
    }

    /**
     * 只在第一行是「短、独立成段、不像完整句子」时才视为印刷标题。
     * 其余情况返回空标题，避免把正文首句误当标题删掉。
     */
    private static TitleMatch detectTitle(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty()) {
                continue;
            }
            if (isNoise(line)) {
                return TitleMatch.none();
            }
            boolean standalone = i + 1 >= lines.size() || lines.get(i + 1).isBlank();
            int words = line.split("\\s+").length;
            boolean sentenceLike = line.matches(".*[.!?。！？;；:：]$");
            if (standalone && line.length() <= 80 && words >= 3 && !sentenceLike) {
                return new TitleMatch(line, i);
            }
            return TitleMatch.none();
        }
        return TitleMatch.none();
    }

    private static String separator(char previous, char next) {
        // 中文换行不应被空格拆开；翻译原文常见中文段落。
        if (isHan(previous) || isHan(next)) {
            return "";
        }
        // 行首是标点时直接接上，避免出现「word ,」。
        if (",.;:!?%)]}".indexOf(next) >= 0) {
            return "";
        }
        if (previous == '-' && Character.isLowerCase(next)) {
            return "";
        }
        return " ";
    }

    private static boolean isHan(char ch) {
        return Character.UnicodeScript.of(ch) == Character.UnicodeScript.HAN;
    }

    /** 清理结果：title 可能为空字符串，body 保证非空。 */
    public record CleanedArticle(String title, String body) {
    }

    private record TitleMatch(String title, int lineIndex) {

        private static TitleMatch none() {
            return new TitleMatch("", -1);
        }
    }
}
