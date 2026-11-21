package com.cethelper.server.explain;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * demo 模式内置的语境释义库。
 *
 * <p>覆盖共同约定里的三条固定样例；未收录的组合返回 422，保证无模型凭据时也能联调。</p>
 */
@Component
public class DemoExplanationCatalog {

    /** 一条内置释义：原句 + 选词唯一定位一种语境。 */
    public record Entry(String sentence, String selection, String meaning, String explanation) {
    }

    private static final String REPORT_SENTENCE = "The report attributed the decline to lower demand.";
    private static final String STUDENTS_SENTENCE = "Some students decline the invitation.";

    private static final List<Entry> ENTRIES = List.of(
            new Entry(REPORT_SENTENCE, "decline", "下降",
                    "这里作名词，指报告提到的下降；报告将这一下降归因于需求走低"),
            new Entry(STUDENTS_SENTENCE, "decline", "婉拒",
                    "这里作动词，指学生谢绝邀请。"),
            new Entry(REPORT_SENTENCE, "lower demand", "较低的需求",
                    "需求较低；在这句话里，它是报告给出的下降原因"));

    /** 按原句与选词精确匹配（去掉首尾空白后比较），命中才返回。 */
    public Optional<ExplanationResponse> lookup(String selection, String sentence) {
        if (selection == null || sentence == null) {
            return Optional.empty();
        }
        String normalizedSelection = selection.trim();
        String normalizedSentence = sentence.trim();
        return ENTRIES.stream()
                .filter(entry -> entry.sentence().equals(normalizedSentence))
                .filter(entry -> entry.selection().equals(normalizedSelection))
                .findFirst()
                .map(entry -> new ExplanationResponse(entry.meaning(), entry.explanation(), "demo"));
    }

    /** 供文档与自测核对内置样例。 */
    public List<Entry> entries() {
        return ENTRIES;
    }
}
