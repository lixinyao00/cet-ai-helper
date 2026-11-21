package com.cethelper.server.sentence;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * demo 模式内置的整句分析库。
 *
 * <p>前两条是内置文章里的原句（与语境释义的内置示例同一批句子），第三条是一句含从句的四六级风格长句，
 * 用来演示「先找主干、再把从句逐段挂回去」的拆解思路。未收录的句子返回 422，
 * 保证没有模型凭据时也能完整联调。</p>
 *
 * <p>构造时用 {@link SentenceChunkRules#placeInOriginalOrder} 校验每条内置数据：
 * 片段的 text 必须是原句中的连续原文且按原句顺序排列，写错就让服务启动失败，避免把坏数据发给客户端。</p>
 */
@Component
public class DemoSentenceCatalog {

    /** 内置文章第一句：decline 在这里是名词。 */
    static final String REPORT_SENTENCE = "The report attributed the decline to lower demand.";

    /** 内置文章第二句：decline 在这里是动词。 */
    static final String STUDENTS_SENTENCE = "Some students decline the invitation.";

    /** 含让步状语从句、定语从句、宾语从句与 what 表语从句的四六级风格长句。 */
    static final String CLAUSE_SENTENCE = "While many students who are preparing for the CET-6 exam believe that "
            + "memorizing word lists is enough, experienced teachers insist that reading widely is what really "
            + "builds vocabulary.";

    private static final List<SentenceAnalysisResponse> ENTRIES = List.of(
            new SentenceAnalysisResponse(
                    REPORT_SENTENCE,
                    "这份报告把这次下降归因于需求走低。",
                    "主干是 The report attributed the decline to lower demand：主语 The report，谓语 attributed，"
                            + "宾语 the decline，to lower demand 说明把下降归因到哪里。",
                    List.of(
                            new SentenceChunk("The report", "主语",
                                    "全句谈论的对象：这份报告。"),
                            new SentenceChunk("attributed", "谓语动词",
                                    "attribute 的过去式，意思是「把……归因于」，后面要接 attribute A to B 两部分。"),
                            new SentenceChunk("the decline", "宾语",
                                    "attributed 的宾语，指报告提到的这次下降；这里作名词。"),
                            new SentenceChunk("to lower demand", "介词短语作状语",
                                    "attribute A to B 中的 B：报告把下降归到需求走低上，而不是别的原因。")),
                    "demo"),
            new SentenceAnalysisResponse(
                    STUDENTS_SENTENCE,
                    "一些学生谢绝了这份邀请。",
                    "主干是 Some students decline the invitation：主语 Some students，谓语 decline，宾语 the invitation。",
                    List.of(
                            new SentenceChunk("Some students", "主语",
                                    "动作的发出者：一些学生。"),
                            new SentenceChunk("decline", "谓语动词",
                                    "这里作动词，表示「婉拒、谢绝」，不是「下降」。"),
                            new SentenceChunk("the invitation", "宾语",
                                    "decline 的宾语：那份邀请。")),
                    "demo"),
            new SentenceAnalysisResponse(
                    CLAUSE_SENTENCE,
                    "尽管许多正在备考六级的学生认为背单词表就够了，有经验的老师却坚持认为，广泛阅读才是真正积累词汇的方式。",
                    "主干是 experienced teachers insist that ...：主语 experienced teachers，谓语 insist，"
                            + "that 从句是老师坚持的内容。逗号前的 While many students (who ...) believe that ... "
                            + "只是让步状语从句，说明「学生怎么想」，读主干时可以先跳过。",
                    List.of(
                            new SentenceChunk("While many students", "让步状语从句（引导词 + 主语）",
                                    "While 引出「尽管、虽然」的让步，这句不是全句主干；many students 是从句主语。"),
                            new SentenceChunk("who are preparing for the CET-6 exam", "修饰 students 的定语从句",
                                    "补一句说明是哪类学生：正在备考六级的学生。拆主干时这段可以先跳过。"),
                            new SentenceChunk("believe", "让步从句的谓语",
                                    "学生的谓语动词，后面接 that 宾语从句，说明他们「认为」什么。"),
                            new SentenceChunk("that memorizing word lists is enough", "believe 的宾语从句",
                                    "学生认为的内容：背单词表就够了；memorizing word lists 是这个小从句的主语。"),
                            new SentenceChunk("experienced teachers insist", "主句主语 + 谓语",
                                    "逗号之后才是全句主干：有经验的老师坚持认为……；后面的 that 从句才是老师要说的观点。"),
                            new SentenceChunk("that reading widely is what really builds vocabulary",
                                    "insist 的宾语从句（内含 what 引导的表语从句）",
                                    "老师的观点：广泛阅读才真正积累词汇；reading widely 作主语，"
                                            + "what really builds vocabulary 作 is 后面的表语。")),
                    "demo"));

    public DemoSentenceCatalog() {
        // 启动即校验内置数据：片段必须逐字出自原句且按原句顺序排列。
        ENTRIES.forEach(entry -> SentenceChunkRules.placeInOriginalOrder(entry.sentence(), entry.chunks()));
    }

    /** 按原句精确匹配（去掉首尾空白后比较），命中才返回。 */
    public Optional<SentenceAnalysisResponse> lookup(String sentence) {
        if (sentence == null) {
            return Optional.empty();
        }
        String normalized = sentence.strip();
        return ENTRIES.stream()
                .filter(entry -> entry.sentence().equals(normalized))
                .findFirst();
    }

    /** 供文档与自测核对内置样例。 */
    public List<SentenceAnalysisResponse> entries() {
        return ENTRIES;
    }
}
