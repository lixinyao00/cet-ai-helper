package com.cethelper.server.explain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * demo 模式内置的语境释义库与四六级基础词表。
 *
 * <p>先按「原句 + 选词」精确匹配三条精调样例（{@code contextual = true}）；
 * 未命中时再用内置四六级高频词表兜底（{@code contextual = false}），
 * 只有词表里也没有的选词才返回 422，避免 demo 模式下大量合法单词查不到释义。</p>
 */
@Component
public class DemoExplanationCatalog {

    /** 一条内置语境释义：原句 + 选词唯一定位一种语境。 */
    public record Entry(String sentence, String selection, String meaning, String explanation,
                        String phonetic, String partOfSpeech, String lemma, String cetLevel) {
    }

    /** 内置词表词条：不依赖原句的通用释义。 */
    public record WordEntry(String lemma, String phonetic, String partOfSpeech, String cetLevel,
                            String meaning, String explanation) {
    }

    private static final String REPORT_SENTENCE = "The report attributed the decline to lower demand.";
    private static final String STUDENTS_SENTENCE = "Some students decline the invitation.";

    private static final List<Entry> ENTRIES = List.of(
            new Entry(REPORT_SENTENCE, "decline", "下降",
                    "这里作名词，指报告提到的下降；报告将这一下降归因于需求走低",
                    "/dɪˈklaɪn/", "n.", "decline", "CET-4"),
            new Entry(STUDENTS_SENTENCE, "decline", "婉拒",
                    "这里作动词，指学生谢绝邀请。",
                    "/dɪˈklaɪn/", "v.", "decline", "CET-4"),
            new Entry(REPORT_SENTENCE, "lower demand", "较低的需求",
                    "需求较低；在这句话里，它是报告给出的下降原因",
                    "/ˌləʊə dɪˈmɑːnd/", "phr.", "lower demand", "CET-4"));

    /** 空白规范化：多个空白（含全角空格）压成一个半角空格，便于短语匹配。 */
    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\p{Zs}]+");

    private static final Map<String, WordEntry> WORDS = buildWords();

    /** 按原句与选词精确匹配（去掉首尾空白后比较）；未命中再查内置四六级词表。 */
    public Optional<ExplanationResponse> lookup(String selection, String sentence) {
        if (selection == null || sentence == null) {
            return Optional.empty();
        }
        String normalizedSelection = selection.trim();
        String normalizedSentence = sentence.trim();
        Optional<ExplanationResponse> contextual = ENTRIES.stream()
                .filter(entry -> entry.sentence().equals(normalizedSentence))
                .filter(entry -> entry.selection().equals(normalizedSelection))
                .findFirst()
                .map(entry -> new ExplanationResponse(entry.meaning(), entry.explanation(), "demo",
                        entry.phonetic(), entry.partOfSpeech(), entry.lemma(), entry.cetLevel(), true));
        if (contextual.isPresent()) {
            return contextual;
        }
        return lookupWord(normalizedSelection);
    }

    /** 只查内置词表：命中返回 contextual=false 的通用释义。 */
    public Optional<ExplanationResponse> lookupWord(String selection) {
        if (selection == null) {
            return Optional.empty();
        }
        String normalized = normalize(selection);
        if (normalized.isEmpty() || normalized.length() > 60) {
            return Optional.empty();
        }
        WordEntry entry = resolve(normalized);
        if (entry == null) {
            return Optional.empty();
        }
        return Optional.of(new ExplanationResponse(entry.meaning(), entry.explanation(), "demo",
                entry.phonetic(), entry.partOfSpeech(), entry.lemma(), entry.cetLevel(), false));
    }

    /** 供文档与自测核对内置样例。 */
    public List<Entry> entries() {
        return ENTRIES;
    }

    /** 供文档与自测核对内置词表。 */
    public Map<String, WordEntry> words() {
        return WORDS;
    }

    /** 全小写、去首尾空白、压缩内部空白。 */
    private static String normalize(String selection) {
        return WHITESPACE.matcher(selection.strip().toLowerCase(Locale.ROOT)).replaceAll(" ");
    }

    /** 精确命中优先；否则尝试常见词尾（-s/-es/-ies/-ed/-ied/-ing）还原到原形。 */
    private static WordEntry resolve(String normalized) {
        WordEntry exact = WORDS.get(normalized);
        if (exact != null) {
            return exact;
        }
        if (normalized.indexOf(' ') >= 0) {
            return null;
        }
        for (String candidate : stemCandidates(normalized)) {
            WordEntry entry = WORDS.get(candidate);
            if (entry != null) {
                return entry;
            }
        }
        return null;
    }

    private static List<String> stemCandidates(String word) {
        List<String> candidates = new java.util.ArrayList<>(6);
        if (word.length() > 3 && word.endsWith("ies")) {
            candidates.add(word.substring(0, word.length() - 3) + "y");
        }
        if (word.length() > 3 && word.endsWith("es")) {
            candidates.add(word.substring(0, word.length() - 2));
        }
        if (word.length() > 2 && word.endsWith("s")) {
            candidates.add(word.substring(0, word.length() - 1));
        }
        if (word.length() > 4 && word.endsWith("ied")) {
            candidates.add(word.substring(0, word.length() - 3) + "y");
        }
        if (word.length() > 3 && word.endsWith("ed")) {
            String base = word.substring(0, word.length() - 2);
            candidates.add(base);
            candidates.add(base + "e");
            candidates.add(undouble(base));
        }
        if (word.length() > 4 && word.endsWith("ing")) {
            String base = word.substring(0, word.length() - 3);
            candidates.add(base);
            candidates.add(base + "e");
            candidates.add(undouble(base));
        }
        return candidates;
    }

    /** stopping -> stop：去掉重复的结尾辅音。 */
    private static String undouble(String base) {
        int length = base.length();
        if (length >= 2) {
            char last = base.charAt(length - 1);
            char previous = base.charAt(length - 2);
            if (last == previous && "bcdfgklmnprstvz".indexOf(last) >= 0) {
                return base.substring(0, length - 1);
            }
        }
        return base;
    }

    private static Map<String, WordEntry> buildWords() {
        Map<String, WordEntry> words = new LinkedHashMap<>();
        // —— 内置示例句中的全部单词 ——
        put(words, word("the", "/ðə/", "art.", "CET-4", "这个；那个（定冠词）",
                "定冠词，特指双方都知道或上文已提到的人或事物。"));
        put(words, word("report", "/rɪˈpɔːt/", "n. / v.", "CET-4", "报告；报道",
                "可作名词表示书面报告，也可作动词表示汇报、报道。"));
        WordEntry attribute = word("attribute", "/əˈtrɪbjuːt/", "v. / n.", "CET-6", "把……归因于；属性",
                "attribute A to B 表示把 A 归因于 B；作名词时指属性、特质。");
        put(words, attribute, "attribute", "attributes", "attributed", "attributing");
        put(words, word("decline", "/dɪˈklaɪn/", "v. / n.", "CET-4", "下降；婉拒",
                "作动词可指数量下降或礼貌拒绝，作名词指下降、衰退。"),
                "decline", "declines", "declined", "declining");
        put(words, word("to", "/tə/", "prep.", "CET-4", "到；向；对于",
                "介词，表示方向、对象或目的；也用于动词不定式前。"));
        put(words, word("low", "/ləʊ/", "adj. / adv.", "CET-4", "低的；低声地",
                "表示高度或程度低；lower 是它的比较级，也可作动词表示降低。"),
                "low", "lower", "lowest", "lowers", "lowered");
        put(words, word("demand", "/dɪˈmɑːnd/", "n. / v.", "CET-4", "需求；要求",
                "作名词指市场需求或需求方，作动词指强烈要求。"),
                "demand", "demands", "demanded", "demanding");
        put(words, word("some", "/sʌm/", "det. / pron.", "CET-4", "一些；某些",
                "用于不可数名词或复数名词前表示不确定的数量。"));
        put(words, word("student", "/ˈstjuːdnt/", "n.", "CET-4", "学生",
                "指在校学习的人；复数 students 泛指学生群体。"),
                "student", "students");
        put(words, word("invitation", "/ˌɪnvɪˈteɪʃn/", "n.", "CET-4", "邀请；请柬",
                "由动词 invite 变来的名词，指邀请这一行为或邀请函。"),
                "invitation", "invitations");
        put(words, word("read", "/riːd/", "v.", "CET-4", "阅读",
                "reading 是它的动名词/现在分词，也可作名词表示阅读。"),
                "read", "reads", "reading", "reader", "readers");
        put(words, word("habit", "/ˈhæbɪt/", "n.", "CET-4", "习惯",
                "指长期形成的、不易改变的行为方式，常与 form/develop 搭配。"),
                "habit", "habits");
        put(words, word("shift", "/ʃɪft/", "n. / v.", "CET-4", "转变；移动",
                "作名词指变化、轮班，作动词指改变位置或方向。"),
                "shift", "shifts", "shifted", "shifting");
        put(words, word("neighborhood", "/ˈneɪbəhʊd/", "n.", "CET-6", "街区；邻里",
                "指居住的社区及其居民；英式拼写为 neighbourhood。"),
                "neighborhood", "neighborhoods", "neighbourhood", "neighbourhoods");
        put(words, word("bookstore", "/ˈbʊkstɔː/", "n.", "CET-4", "书店",
                "book + store 构成的合成词，英式英语常说 bookshop。"),
                "bookstore", "bookstores", "bookshop", "bookshops");
        put(words, word("digital", "/ˈdɪdʒɪtl/", "adj.", "CET-4", "数字的；数码的",
                "指以数字技术处理信息的，如 digital economy 数字经济。"));
        put(words, word("physical", "/ˈfɪzɪkl/", "adj.", "CET-4", "物质的；身体的",
                "与 mental、digital 相对，指实体存在的或与身体有关的。"));
        put(words, word("close", "/kləʊz/", "v. / adj.", "CET-4", "关闭；接近的",
                "作动词读 /kləʊz/ 表示关闭，作形容词读 /kləʊs/ 表示接近的。"),
                "close", "closes", "closed", "closing");
        put(words, word("lose", "/luːz/", "v.", "CET-4", "失去；丢失",
                "指不再拥有某物或未能保住，过去式为 lost。"),
                "lose", "loses", "lost", "losing");
        put(words, word("place", "/pleɪs/", "n. / v.", "CET-4", "地方；放置",
                "作名词指地点、位置，作动词指安放。"),
                "place", "places", "placed", "placing");
        put(words, word("buy", "/baɪ/", "v.", "CET-4", "购买",
                "常用 buy sth for sb 或 buy sb sth，过去式为 bought。"),
                "buy", "buys", "bought", "buying");
        put(words, word("paperback", "/ˈpeɪpəbæk/", "n.", "CET-6", "平装书",
                "指纸质封面的廉价版本图书，与 hardcover 相对。"),
                "paperback", "paperbacks");

        // —— 常见四六级核心词 ——
        put(words, word("important", "/ɪmˈpɔːtnt/", "adj.", "CET-4", "重要的",
                "It is important for sb to do sth 是常见句型。"));
        put(words, word("develop", "/dɪˈveləp/", "v.", "CET-4", "发展；开发",
                "可指经济发展、能力培养或产品开发。"),
                "develop", "develops", "developed", "developing", "development");
        put(words, word("economy", "/ɪˈkɒnəmi/", "n.", "CET-4", "经济",
                "指一个地区或国家的经济体系，digital economy 即数字经济。"),
                "economy", "economies", "economic", "economical");
        put(words, word("skill", "/skɪl/", "n.", "CET-4", "技能；技巧",
                "常以复数 skills 出现，如 programming skills 编程能力。"),
                "skill", "skills", "skilled", "skillful");
        put(words, word("company", "/ˈkʌmpəni/", "n.", "CET-4", "公司；陪伴",
                "作可数名词指企业，作不可数名词指陪伴。"),
                "company", "companies");
        put(words, word("prefer", "/prɪˈfɜː/", "v.", "CET-4", "更喜欢",
                "prefer A to B / prefer to do sth 是常见搭配。"),
                "prefer", "prefers", "preferred", "preferring", "preference");
        put(words, word("hire", "/ˈhaɪə/", "v.", "CET-4", "雇佣；租用",
                "指付钱雇佣某人或租用物品，比 employ 更口语。"),
                "hire", "hires", "hired", "hiring");
        put(words, word("young", "/jʌŋ/", "adj.", "CET-4", "年轻的",
                "young people 泛指年轻人，比较级为 younger。"));
        put(words, word("people", "/ˈpiːpl/", "n.", "CET-4", "人；人们",
                "集体名词，作复数使用；the people 可指人民。"));
        put(words, word("think", "/θɪŋk/", "v.", "CET-4", "思考；认为",
                "think independently 表示独立思考，过去式为 thought。"),
                "think", "thinks", "thought", "thinking");
        put(words, word("team", "/tiːm/", "n.", "CET-4", "团队",
                "work in a team 表示团队协作，teamwork 是名词形式。"),
                "team", "teams", "teamwork");
        put(words, word("graduate", "/ˈɡrædʒuət/", "n. / v.", "CET-4", "毕业生；毕业",
                "college graduates 指高校毕业生，作动词表示毕业。"),
                "graduate", "graduates", "graduated", "graduating", "graduation");
        put(words, word("college", "/ˈkɒlɪdʒ/", "n.", "CET-4", "学院；大学",
                "college students 指大学生，与 university 常可互换。"),
                "college", "colleges");
        put(words, word("university", "/ˌjuːnɪˈvɜːsəti/", "n.", "CET-4", "大学",
                "指综合性大学，复数 universities。"),
                "university", "universities");
        put(words, word("increase", "/ɪnˈkriːs/", "v. / n.", "CET-4", "增加；增长",
                "作动词重音在后，作名词重音在前；反义词为 decrease。"),
                "increase", "increases", "increased", "increasing", "increasingly");
        put(words, word("decrease", "/dɪˈkriːs/", "v. / n.", "CET-4", "减少；下降",
                "与 increase 相对，可作动词或名词。"),
                "decrease", "decreases", "decreased", "decreasing");
        put(words, word("improve", "/ɪmˈpruːv/", "v.", "CET-4", "改善；提高",
                "可指提高水平、改善条件，名词为 improvement。"),
                "improve", "improves", "improved", "improving", "improvement");
        put(words, word("society", "/səˈsaɪəti/", "n.", "CET-4", "社会",
                "指社会整体，也可指社团；复数 societies。"),
                "society", "societies");
        put(words, word("education", "/ˌedʒuˈkeɪʃn/", "n.", "CET-4", "教育",
                "由 educate 变来的名词，指教育过程或教育事业。"));
        put(words, word("environment", "/ɪnˈvaɪrənmənt/", "n.", "CET-4", "环境",
                "指自然环境或 surrounding conditions，常与 protect 搭配。"),
                "environment", "environments", "environmental");
        put(words, word("technology", "/tekˈnɒlədʒi/", "n.", "CET-4", "技术",
                "指科技与技术手段，复数 technologies。"),
                "technology", "technologies", "technological");
        put(words, word("culture", "/ˈkʌltʃə/", "n.", "CET-4", "文化",
                "指一个群体的价值观、习俗与艺术，形容词 cultural。"),
                "culture", "cultures", "cultural");
        put(words, word("benefit", "/ˈbenɪfɪt/", "n. / v.", "CET-4", "益处；受益",
                "benefit from 表示从……中受益，be beneficial to 表示对……有益。"),
                "benefit", "benefits", "benefited", "benefiting", "beneficial");
        put(words, word("volunteer", "/ˌvɒlənˈtɪə/", "n. / v.", "CET-4", "志愿者；自愿做",
                "作名词指志愿者，作动词指自愿做某事。"),
                "volunteer", "volunteers", "volunteered", "volunteering");
        put(words, word("community", "/kəˈmjuːnəti/", "n.", "CET-4", "社区；群体",
                "local community 指当地社区，复数 communities。"),
                "community", "communities");
        put(words, word("experience", "/ɪkˈspɪəriəns/", "n. / v.", "CET-4", "经验；经历",
                "作不可数名词指经验，作可数名词指一段经历。"),
                "experience", "experiences", "experienced");
        put(words, word("ability", "/əˈbɪləti/", "n.", "CET-4", "能力",
                "the ability to do sth 表示做某事的能力，复数 abilities。"),
                "ability", "abilities", "able");
        put(words, word("knowledge", "/ˈnɒlɪdʒ/", "n.", "CET-4", "知识",
                "不可数名词，常用 a piece of knowledge；搭配 gain knowledge。"));
        put(words, word("opportunity", "/ˌɒpəˈtjuːnəti/", "n.", "CET-4", "机会",
                "opportunity to do sth 表示做某事的机会，复数 opportunities。"),
                "opportunity", "opportunities");
        put(words, word("challenge", "/ˈtʃælɪndʒ/", "n. / v.", "CET-4", "挑战",
                "作名词指难题，作动词指向……发起挑战。"),
                "challenge", "challenges", "challenged", "challenging");
        put(words, word("success", "/səkˈses/", "n.", "CET-4", "成功",
                "指达成目标，形容词 successful，动词 succeed。"),
                "success", "successes", "successful", "succeed");
        put(words, word("achieve", "/əˈtʃiːv/", "v.", "CET-4", "实现；达到",
                "achieve a goal 表示实现目标，名词为 achievement。"),
                "achieve", "achieves", "achieved", "achieving", "achievement");
        put(words, word("require", "/rɪˈkwaɪə/", "v.", "CET-4", "需要；要求",
                "require sb to do sth 表示要求某人做某事，名词 requirement。"),
                "require", "requires", "required", "requiring", "requirement");
        put(words, word("provide", "/prəˈvaɪd/", "v.", "CET-4", "提供",
                "provide sb with sth 或 provide sth for sb。"),
                "provide", "provides", "provided", "providing");
        put(words, word("consider", "/kənˈsɪdə/", "v.", "CET-4", "考虑；认为",
                "consider doing sth 表示考虑做某事，名词 consideration。"),
                "consider", "considers", "considered", "considering");
        put(words, word("involve", "/ɪnˈvɒlv/", "v.", "CET-6", "涉及；使参与",
                "be involved in 表示参与其中。"),
                "involve", "involves", "involved", "involving");
        put(words, word("manage", "/ˈmænɪdʒ/", "v.", "CET-4", "管理；设法做到",
                "manage to do sth 表示设法做成了某事，名词 manager。"),
                "manage", "manages", "managed", "managing", "management");
        put(words, word("affect", "/əˈfekt/", "v.", "CET-4", "影响",
                "及物动词，affect 是动词、effect 是名词，注意区分。"),
                "affect", "affects", "affected", "affecting");
        put(words, word("effect", "/ɪˈfekt/", "n.", "CET-4", "影响；效果",
                "have an effect on 表示对……有影响，常用复数 effects。"),
                "effect", "effects", "effective");
        put(words, word("contribute", "/kənˈtrɪbjuːt/", "v.", "CET-4", "贡献；促成",
                "contribute to 表示对……有贡献或促成某事，名词 contribution。"),
                "contribute", "contributes", "contributed", "contributing", "contribution");
        put(words, word("maintain", "/meɪnˈteɪn/", "v.", "CET-6", "维持；保养",
                "指保持某种状态或维护设备，名词 maintenance。"),
                "maintain", "maintains", "maintained", "maintaining");
        put(words, word("reduce", "/rɪˈdjuːs/", "v.", "CET-4", "减少；降低",
                "reduce sth by 表示减少了多少，名词 reduction。"),
                "reduce", "reduces", "reduced", "reducing");
        put(words, word("support", "/səˈpɔːt/", "v. / n.", "CET-4", "支持；支撑",
                "可指支持观点或支撑重量，名词 supporter。"),
                "support", "supports", "supported", "supporting");
        put(words, word("encourage", "/ɪnˈkʌrɪdʒ/", "v.", "CET-4", "鼓励",
                "encourage sb to do sth 表示鼓励某人做某事，名词 encouragement。"),
                "encourage", "encourages", "encouraged", "encouraging");
        put(words, word("result", "/rɪˈzʌlt/", "n. / v.", "CET-4", "结果；导致",
                "as a result 表示因此；result in 表示导致。"),
                "result", "results", "resulted", "resulting");
        put(words, word("reason", "/ˈriːzn/", "n.", "CET-4", "原因；理由",
                "the reason for sth 表示某事的原因，动词为 reason。"),
                "reason", "reasons", "reasonable");
        put(words, word("language", "/ˈlæŋɡwɪdʒ/", "n.", "CET-4", "语言",
                "指自然语言或表达方式，复数 languages。"),
                "language", "languages");
        put(words, word("study", "/ˈstʌdi/", "v. / n.", "CET-4", "学习；研究",
                "作动词指学习或研究，作名词指学业、书房。"),
                "study", "studies", "studied", "studying");
        put(words, word("learn", "/lɜːn/", "v.", "CET-4", "学习；得知",
                "learn from 表示向……学习，过去式 learned 或 learnt。"),
                "learn", "learns", "learned", "learnt", "learning");
        put(words, word("word", "/wɜːd/", "n.", "CET-4", "单词；话语",
                "指语言单位或说的话，in other words 表示换句话说。"),
                "word", "words");
        put(words, word("sentence", "/ˈsentəns/", "n.", "CET-4", "句子",
                "指语法上完整的表达单位；也指判决。"),
                "sentence", "sentences");
        put(words, word("meaning", "/ˈmiːnɪŋ/", "n.", "CET-4", "意思；意义",
                "指词句的含义或人生的意义，动词为 mean。"),
                "meaning", "meanings", "mean");
        return Map.copyOf(words);
    }

    private static WordEntry word(String lemma, String phonetic, String partOfSpeech, String cetLevel,
                                  String meaning, String explanation) {
        return new WordEntry(lemma, phonetic, partOfSpeech, cetLevel, meaning, explanation);
    }

    /** 同一个词条登记多个拼写/词形；未给 key 时以 lemma 为主键。 */
    private static void put(Map<String, WordEntry> words, WordEntry entry, String... keys) {
        if (keys.length == 0) {
            words.put(entry.lemma(), entry);
            return;
        }
        for (String key : keys) {
            words.put(key, entry);
        }
    }
}
