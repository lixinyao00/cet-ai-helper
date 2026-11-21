package com.cethelper.server.translation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * demo 模式内置的汉译英材料与可复验样例。
 *
 * <p>内置材料是一段两句的四六级风格中文，覆盖：三类提示（keyword / phrase / structure）、
 * 一份带漏译与生硬表达的草稿批改、一份学生自己补全后的批改、一份准确译文，以及一份合理但不同的译法。
 * 批改按「中文原文 + 学生译文」精确匹配，不同译文拿到不同反馈，不会一律返回同一份批改；
 * 未收录的组合返回 422，便于无模型凭据时联调。</p>
 *
 * <p>构造时用 {@link TranslationRules} 校验全部内置数据：提示里的中文片段必须逐字出自原文，
 * 批改引用的片段必须逐字出自对应输入，写错就让服务启动失败，避免把不可核对的内容发给客户端。</p>
 */
@Component
public class DemoTranslationCatalog {

    /** 内置材料：两句话，含关键词、短语与明显的从句/因果结构。 */
    static final String SOURCE_TEXT = "随着数字经济的快速发展，掌握基本的编程能力对高校毕业生越来越重要。"
            + "许多企业因此更愿意录用那些既能独立思考又能团队协作的年轻人。";

    /**
     * 草稿译文：两处漏译（「对高校毕业生」被泛化成 students、「越来越」没译出）与一处不地道搭配
     * （team cooperation 是「团队协作」的字面直译），三类问题各有独立依据。
     */
    static final String DRAFT_TRANSLATION = "With the rapid development of the digital economy, "
            + "programming skills are very important for students. Many companies prefer to hire young people "
            + "who can think independently and have good team cooperation.";

    /** 学生补全后的译文：草稿里被标出的漏译与不地道搭配都已改掉。 */
    static final String COMPLETED_TRANSLATION = "As the digital economy develops rapidly, "
            + "it is becoming increasingly important for college graduates to master basic programming skills. "
            + "As a result, many companies prefer to hire young people who can think independently "
            + "and work well in a team.";

    /** 准确译文样例。 */
    static final String ACCURATE_TRANSLATION = "As the digital economy develops rapidly, "
            + "mastering basic programming skills is becoming increasingly important for college graduates. "
            + "Therefore, many companies are more willing to hire young people who can both think independently "
            + "and work in a team.";

    /** 另一种合理的准确译法：结构完全不同但意思到位，同样不应被挑错。 */
    static final String ALTERNATE_TRANSLATION = "The rapid growth of the digital economy has made basic "
            + "programming skills increasingly important for university graduates. Consequently, companies are now "
            + "more inclined to recruit young people capable of both independent thinking and teamwork.";

    private static final List<ExpressionHint> KEYWORDS = List.of(
            new ExpressionHint("数字经济", List.of("the digital economy", "digital economy"),
                    "指以数字技术为基础的经济形态；作介词宾语时通常带定冠词 the。"),
            new ExpressionHint("编程能力", List.of("programming skills", "coding skills"),
                    "「能力」用复数 skills 更自然；也可写 ability to code。"),
            new ExpressionHint("高校毕业生", List.of("college graduates", "university graduates"),
                    "比 students 更具体，强调已经毕业；也可说 graduates from college。"),
            new ExpressionHint("录用", List.of("hire", "recruit"),
                    "正式语境的招聘多用 recruit；hire 更口语。"),
            new ExpressionHint("团队协作", List.of("teamwork", "team collaboration"),
                    "名词形式；作动词可说 work in a team。"));

    private static final List<ExpressionHint> PHRASES = List.of(
            new ExpressionHint("随着数字经济的快速发展",
                    List.of("with the rapid development of the digital economy",
                            "as the digital economy develops rapidly"),
                    "with + 名词短语更简洁；as 引导从句更强调过程。"),
            new ExpressionHint("越来越重要", List.of("increasingly important", "more and more important"),
                    "increasingly 更正式，四六级作文常用。"),
            new ExpressionHint("更愿意录用", List.of("be more willing to hire", "prefer to hire"),
                    "表达倾向时 be willing to 比 want to 更贴切。"),
            new ExpressionHint("既能独立思考又能团队协作",
                    List.of("both think independently and work in a team",
                            "not only think independently but also work in a team"),
                    "both ... and ... 对应「既能……又能……」；注意前后成分词性一致。"));

    private static final StructureHint STRUCTURE = new StructureHint(
            "全段两句。第一句主干是「掌握基本的编程能力对高校毕业生越来越重要」：动名词短语作主语，"
                    + "「对……越来越重要」是谓语部分，句首「随着……」是时间状语。第二句主干是「许多企业更愿意录用年轻人」，"
                    + "后面用定语从句补出年轻人应有的两项能力，「因此」承接第一句的结论。",
            List.of(
                    new ClauseHint("随着数字经济的快速发展", "时间状语",
                            "可用 With the rapid development of the digital economy 或 "
                                    + "As the digital economy develops rapidly 引导，放在句首。"),
                    new ClauseHint("既能独立思考又能团队协作", "修饰「年轻人」的定语从句",
                            "用 who can both think independently and work in a team 作后置定语，不要拆成两个并列句。")),
            List.of(
                    new ConnectiveHint("因此", "Therefore, 或 As a result, 承接前一句的结论，显式表达因果。"),
                    new ConnectiveHint("那些", "用 those who ... 或 young people who ... 引出后置定语，"
                            + "指代「那些……的年轻人」。")));

    private static final List<ReviewIssue> DRAFT_ISSUES = List.of(
            new ReviewIssue(IssueType.OMISSION, "对高校毕业生",
                    "学生只写成 for students，把「高校毕业生」这个具体对象泛化了，原文里的毕业身份没有译出。",
                    "补成 for college graduates。"),
            new ReviewIssue(IssueType.OMISSION, "越来越",
                    "原文是「越来越重要」，译文用 are very important 只说了「很重要」，漏掉程度随时间变化的意思。",
                    "改为 are becoming increasingly important。"),
            new ReviewIssue(IssueType.UNNATURAL, "have good team cooperation",
                    "team cooperation 是「团队协作」的字面直译，英语里更常说 teamwork；have good ... cooperation "
                            + "这个搭配也不自然，读起来是翻译腔。",
                    "改为 work well in a team（或 have strong teamwork skills）。"));

    private static final String DRAFT_FEEDBACK = "能看懂大意，但第一句把「对高校毕业生」泛化成了 students、"
            + "漏掉「越来越」，另外 have good team cooperation 是不地道的搭配。按提示补全并换掉这处搭配即可。";

    private static final String COMPLETED_FEEDBACK = "补全后信息完整：「对高校毕业生」「越来越」都已交代，"
            + "team cooperation 也换成了 work well in a team，搭配自然。上一版被标出的问题都不再出现，可以提交。";

    private static final String ACCURATE_FEEDBACK = "两句话都准确自然：as 引导时间状语，动名词短语作主语，"
            + "Therefore 承接因果，who 从句补出两项能力。可以提交。";

    private static final String ALTERNATE_FEEDBACK = "另一种合理译法：名词化主语 The rapid growth ... has made ... "
            + "准确传达原文，capable of both ... 对应「既能……又能……」。译法不同但意思到位，无需修改。";

    /** 四六级汉译英按 15 分制评分，档位说明与分数保持一致。 */
    private static final int SCORE_MAX = 15;
    private static final String DRAFT_SCORE_BAND = "第四档（7-9分）：基本表达原文意思，但有较多漏译与不地道搭配。";
    private static final String COMPLETED_SCORE_BAND = "第二档（10-12分）：基本达意，无明显漏译，个别表达仍可更自然。";
    private static final String ACCURATE_SCORE_BAND = "第一档（13-15分）：译文准确、表达流畅，结构与用词符合四六级要求。";
    private static final String ALTERNATE_SCORE_BAND = "第一档（13-15分）：译法不同但准确自然，同样属于高分段。";

    private final List<TranslationHintResponse> hints;
    private final List<TranslationReviewResponse> reviews;

    public DemoTranslationCatalog() {
        this.hints = buildHints();
        this.reviews = buildReviews();
    }

    /** 按原句与提示类型精确匹配（去掉首尾空白后比较）。 */
    public Optional<TranslationHintResponse> lookupHint(String sourceText, HintType hintType) {
        if (sourceText == null || hintType == null) {
            return Optional.empty();
        }
        return hints.stream()
                .filter(hint -> hint.sourceText().equals(sourceText))
                .filter(hint -> hintType.value().equals(hint.hintType()))
                .findFirst();
    }

    /** 按「中文原文 + 学生译文」精确匹配；不同译文各自对应不同反馈。 */
    public Optional<TranslationReviewResponse> lookupReview(String sourceText, String studentTranslation) {
        if (sourceText == null || studentTranslation == null) {
            return Optional.empty();
        }
        return reviews.stream()
                .filter(review -> review.sourceText().equals(sourceText))
                .filter(review -> review.studentTranslation().equals(studentTranslation))
                .findFirst();
    }

    /** 供文档与自测核对内置样例。 */
    public List<TranslationHintResponse> hints() {
        return hints;
    }

    /** 供文档与自测核对内置样例。 */
    public List<TranslationReviewResponse> reviews() {
        return reviews;
    }

    private static List<TranslationHintResponse> buildHints() {
        List<TranslationHintResponse> built = new ArrayList<>(HintType.values().length);
        built.add(new TranslationHintResponse(SOURCE_TEXT, HintType.KEYWORD.value(),
                TranslationRules.requireExpressionHints(SOURCE_TEXT, KEYWORDS, "keywords"), null, null, "demo"));
        built.add(new TranslationHintResponse(SOURCE_TEXT, HintType.PHRASE.value(), null,
                TranslationRules.requireExpressionHints(SOURCE_TEXT, PHRASES, "phrases"), null, "demo"));
        built.add(new TranslationHintResponse(SOURCE_TEXT, HintType.STRUCTURE.value(), null, null,
                TranslationRules.requireStructureHint(SOURCE_TEXT, STRUCTURE), "demo"));
        Set<String> covered = new HashSet<>();
        built.forEach(hint -> covered.add(hint.hintType()));
        if (covered.size() != HintType.values().length) {
            throw new IllegalStateException("demo 提示样例未覆盖全部 hintType：" + covered);
        }
        return List.copyOf(built);
    }

    private static List<TranslationReviewResponse> buildReviews() {
        List<TranslationReviewResponse> built = List.of(
                review(DRAFT_TRANSLATION, DRAFT_FEEDBACK, DRAFT_ISSUES, 9, DRAFT_SCORE_BAND),
                review(COMPLETED_TRANSLATION, COMPLETED_FEEDBACK, List.of(), 12, COMPLETED_SCORE_BAND),
                review(ACCURATE_TRANSLATION, ACCURATE_FEEDBACK, List.of(), 14, ACCURATE_SCORE_BAND),
                review(ALTERNATE_TRANSLATION, ALTERNATE_FEEDBACK, List.of(), 14, ALTERNATE_SCORE_BAND));
        // 译文不能重复：否则「不同译文不同批改」的演示会退化成同一份结果。
        Set<String> translations = new HashSet<>();
        for (TranslationReviewResponse review : built) {
            if (!translations.add(review.studentTranslation())) {
                throw new IllegalStateException("demo 批改样例存在重复译文");
            }
        }
        boolean hasIssueCase = built.stream().anyMatch(review -> !review.issues().isEmpty());
        boolean hasCleanCase = built.stream().anyMatch(review -> review.issues().isEmpty());
        if (!hasIssueCase || !hasCleanCase) {
            throw new IllegalStateException("demo 批改样例必须同时覆盖「有问题」与「没有问题」两种结果");
        }
        return built;
    }

    private static TranslationReviewResponse review(String studentTranslation, String overallFeedback,
                                                    List<ReviewIssue> issues, int estimatedScore,
                                                    String scoreBand) {
        return new TranslationReviewResponse(
                SOURCE_TEXT,
                studentTranslation,
                TranslationRules.requireText(overallFeedback, "overallFeedback", TranslationRules.MAX_TEXT_LENGTH),
                TranslationRules.requireIssues(SOURCE_TEXT, studentTranslation, issues),
                "demo",
                estimatedScore,
                SCORE_MAX,
                scoreBand,
                ACCURATE_TRANSLATION);
    }
}
