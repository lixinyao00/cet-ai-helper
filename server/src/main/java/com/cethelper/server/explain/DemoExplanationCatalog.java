package com.cethelper.server.explain;

import java.util.ArrayList;
import java.util.Comparator;
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
 *
 * <p>词表同时服务词典接口：每个 lemma 都带有双语例句，例句中一定包含该词的字面形式，
 * 供 Android 端生成填空题。词表规模不少于 120 个 lemma，CET-4 与 CET-6 大致各半。</p>
 */
@Component
public class DemoExplanationCatalog {

    /** 一条内置语境释义：原句 + 选词唯一定位一种语境。 */
    public record Entry(String sentence, String selection, String meaning, String explanation,
                        String phonetic, String partOfSpeech, String lemma, String cetLevel) {
    }

    /** 内置词表词条：不依赖原句的通用释义，附带双语例句。 */
    public record WordEntry(String lemma, String phonetic, String partOfSpeech, String cetLevel,
                            String meaning, String explanation, String exampleSentence,
                            String exampleTranslation) {

        /** 兼容旧的 6 参构造：例句留空，旧调用方无需改动。 */
        public WordEntry(String lemma, String phonetic, String partOfSpeech, String cetLevel,
                         String meaning, String explanation) {
            this(lemma, phonetic, partOfSpeech, cetLevel, meaning, explanation, "", "");
        }
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

    /**
     * 词典接口专用：按原形/常见变形查找词条（如 {@code abandoned} → {@code abandon}）。
     *
     * <p>复用 {@link #stemCandidates(String)} 与 {@code undouble} 的词形还原规则，
     * 未收录或输入过长时返回空。</p>
     */
    public Optional<WordEntry> findWord(String word) {
        if (word == null) {
            return Optional.empty();
        }
        String normalized = normalize(word);
        if (normalized.isEmpty() || normalized.length() > 60) {
            return Optional.empty();
        }
        return Optional.ofNullable(resolve(normalized));
    }

    /**
     * 词典联想：按 lemma 前缀（字母序）→ 任一词形 key 前缀 → lemma 子串的优先级排序，
     * 并按 lemma 去重。{@code level} 为空表示不限级别，否则只返回该级别的词条。
     */
    public List<WordEntry> suggest(String prefix, String level, int limit) {
        String normalized = prefix == null ? "" : normalize(prefix);
        if (normalized.isEmpty() || limit <= 0) {
            return List.of();
        }
        String normalizedLevel = level == null || level.isBlank()
                ? null
                : level.strip().toUpperCase(Locale.ROOT);

        Map<String, WordEntry> byLemma = new LinkedHashMap<>();
        Map<String, Integer> ranks = new LinkedHashMap<>();
        for (Map.Entry<String, WordEntry> candidate : WORDS.entrySet()) {
            WordEntry entry = candidate.getValue();
            if (normalizedLevel != null && !entry.cetLevel().equalsIgnoreCase(normalizedLevel)) {
                continue;
            }
            byLemma.putIfAbsent(entry.lemma(), entry);
            int rank;
            if (entry.lemma().startsWith(normalized)) {
                rank = 0;
            } else if (candidate.getKey().startsWith(normalized)) {
                rank = 1;
            } else if (entry.lemma().contains(normalized)) {
                rank = 2;
            } else {
                continue;
            }
            ranks.merge(entry.lemma(), rank, Math::min);
        }
        return ranks.entrySet().stream()
                .sorted(Comparator
                        .<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                        .thenComparing(Map.Entry::getKey))
                .limit(limit)
                .map(entry -> byLemma.get(entry.getKey()))
                .toList();
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
        List<String> candidates = new ArrayList<>(9);
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
        put(words, w("the", "/ðə/", "art.", "CET-4", "这个；那个（定冠词）",
                "定冠词，特指双方都知道或上文已提到的人或事物。",
                "The report is on the desk.", "那份报告在桌子上。"));
        put(words, w("report", "/rɪˈpɔːt/", "n. / v.", "CET-4", "报告；报道",
                "可作名词表示书面报告，也可作动词表示汇报、报道。",
                "The report was published last week.", "这份报告上周发布了。"));
        put(words, w("attribute", "/əˈtrɪbjuːt/", "v. / n.", "CET-6", "把……归因于；属性",
                "attribute A to B 表示把 A 归因于 B；作名词时指属性、特质。",
                "We attribute the success to hard work.", "我们把成功归因于努力。"),
                "attribute", "attributes", "attributed", "attributing");
        put(words, w("decline", "/dɪˈklaɪn/", "v. / n.", "CET-4", "下降；婉拒",
                "作动词可指数量下降或礼貌拒绝，作名词指下降、衰退。",
                "Sales began to decline in autumn.", "销售额在秋天开始下降。"),
                "decline", "declines", "declined", "declining");
        put(words, w("to", "/tə/", "prep.", "CET-4", "到；向；对于",
                "介词，表示方向、对象或目的；也用于动词不定式前。",
                "She walked to the library after lunch.", "她午饭后走向图书馆。"));
        put(words, w("low", "/ləʊ/", "adj. / adv.", "CET-4", "低的；低声地",
                "表示高度或程度低；lower 是它的比较级，也可作动词表示降低。",
                "The prices are low during the sale.", "促销期间价格很低。"),
                "low", "lower", "lowest", "lowers", "lowered");
        put(words, w("demand", "/dɪˈmɑːnd/", "n. / v.", "CET-4", "需求；要求",
                "作名词指市场需求或需求方，作动词指强烈要求。",
                "There is a strong demand for online courses.", "在线课程的需求很大。"),
                "demand", "demands", "demanded", "demanding");
        put(words, w("some", "/sʌm/", "det. / pron.", "CET-4", "一些；某些",
                "用于不可数名词或复数名词前表示不确定的数量。",
                "Some students prefer to study at night.", "一些学生喜欢在晚上学习。"));
        put(words, w("student", "/ˈstjuːdnt/", "n.", "CET-4", "学生",
                "指在校学习的人；复数 students 泛指学生群体。",
                "Every student must bring a notebook.", "每个学生都必须带一个笔记本。"),
                "student", "students");
        put(words, w("invitation", "/ˌɪnvɪˈteɪʃn/", "n.", "CET-4", "邀请；请柬",
                "由动词 invite 变来的名词，指邀请这一行为或邀请函。",
                "She accepted the invitation to the party.", "她接受了聚会的邀请。"),
                "invitation", "invitations");
        put(words, w("read", "/riːd/", "v.", "CET-4", "阅读",
                "reading 是它的动名词/现在分词，也可作名词表示阅读。",
                "I read the news every morning.", "我每天早上读新闻。"),
                "read", "reads", "reading", "reader", "readers");
        put(words, w("habit", "/ˈhæbɪt/", "n.", "CET-4", "习惯",
                "指长期形成的、不易改变的行为方式，常与 form/develop 搭配。",
                "Reading before bed is a good habit.", "睡前阅读是个好习惯。"),
                "habit", "habits");
        put(words, w("shift", "/ʃɪft/", "n. / v.", "CET-4", "转变；移动",
                "作名词指变化、轮班，作动词指改变位置或方向。",
                "The night shift starts at ten o'clock.", "夜班十点开始。"),
                "shift", "shifts", "shifted", "shifting");
        put(words, w("neighborhood", "/ˈneɪbəhʊd/", "n.", "CET-6", "街区；邻里",
                "指居住的社区及其居民；英式拼写为 neighbourhood。",
                "Our neighborhood has a small park.", "我们的街区有一个小公园。"),
                "neighborhood", "neighborhoods", "neighbourhood", "neighbourhoods");
        put(words, w("bookstore", "/ˈbʊkstɔː/", "n.", "CET-4", "书店",
                "book + store 构成的合成词，英式英语常说 bookshop。",
                "The bookstore closes at nine.", "书店九点关门。"),
                "bookstore", "bookstores", "bookshop", "bookshops");
        put(words, w("digital", "/ˈdɪdʒɪtl/", "adj.", "CET-4", "数字的；数码的",
                "指以数字技术处理信息的，如 digital economy 数字经济。",
                "Digital tools have changed the way we learn.", "数字工具改变了我们的学习方式。"));
        put(words, w("physical", "/ˈfɪzɪkl/", "adj.", "CET-4", "物质的；身体的",
                "与 mental、digital 相对，指实体存在的或与身体有关的。",
                "Physical exercise keeps you healthy.", "体育锻炼让你保持健康。"));
        put(words, w("close", "/kləʊz/", "v. / adj.", "CET-4", "关闭；接近的",
                "作动词读 /kləʊz/ 表示关闭，作形容词读 /kləʊs/ 表示接近的。",
                "Please close the window before you leave.", "离开前请关上窗户。"),
                "close", "closes", "closed", "closing");
        put(words, w("lose", "/luːz/", "v.", "CET-4", "失去；丢失",
                "指不再拥有某物或未能保住，过去式为 lost。",
                "Do not lose your ticket.", "别把票弄丢了。"),
                "lose", "loses", "lost", "losing");
        put(words, w("place", "/pleɪs/", "n. / v.", "CET-4", "地方；放置",
                "作名词指地点、位置，作动词指安放。",
                "This is a quiet place to study.", "这是一个安静的学习场所。"),
                "place", "places", "placed", "placing");
        put(words, w("buy", "/baɪ/", "v.", "CET-4", "购买",
                "常用 buy sth for sb 或 buy sb sth，过去式为 bought。",
                "I want to buy a new dictionary.", "我想买一本新词典。"),
                "buy", "buys", "bought", "buying");
        put(words, w("paperback", "/ˈpeɪpəbæk/", "n.", "CET-6", "平装书",
                "指纸质封面的廉价版本图书，与 hardcover 相对。",
                "She bought a paperback novel for the trip.", "她为旅行买了一本平装小说。"),
                "paperback", "paperbacks");

        // —— 常见四六级核心词（CET-4） ——
        put(words, w("important", "/ɪmˈpɔːtnt/", "adj.", "CET-4", "重要的",
                "It is important for sb to do sth 是常见句型。",
                "It is important to review your notes.", "复习笔记很重要。"));
        put(words, w("develop", "/dɪˈveləp/", "v.", "CET-4", "发展；开发",
                "可指经济发展、能力培养或产品开发。",
                "The company plans to develop a new app.", "公司计划开发一款新应用。"),
                "develop", "develops", "developed", "developing", "development");
        put(words, w("economy", "/ɪˈkɒnəmi/", "n.", "CET-4", "经济",
                "指一个地区或国家的经济体系，digital economy 即数字经济。",
                "The local economy depends on tourism.", "当地经济依赖旅游业。"),
                "economy", "economies", "economic", "economical");
        put(words, w("skill", "/skɪl/", "n.", "CET-4", "技能；技巧",
                "常以复数 skills 出现，如 programming skills 编程能力。",
                "Writing is a skill that improves with practice.", "写作是一项通过练习提高的技能。"),
                "skill", "skills", "skilled", "skillful");
        put(words, w("company", "/ˈkʌmpəni/", "n.", "CET-4", "公司；陪伴",
                "作可数名词指企业，作不可数名词指陪伴。",
                "He works for a small company.", "他在一家小公司工作。"),
                "company", "companies");
        put(words, w("prefer", "/prɪˈfɜː/", "v.", "CET-4", "更喜欢",
                "prefer A to B / prefer to do sth 是常见搭配。",
                "I prefer tea to coffee.", "比起咖啡我更喜欢茶。"),
                "prefer", "prefers", "preferred", "preferring", "preference");
        put(words, w("hire", "/ˈhaɪə/", "v.", "CET-4", "雇佣；租用",
                "指付钱雇佣某人或租用物品，比 employ 更口语。",
                "The school will hire two new teachers.", "学校将聘用两名新教师。"),
                "hire", "hires", "hired", "hiring");
        put(words, w("young", "/jʌŋ/", "adj.", "CET-4", "年轻的",
                "young people 泛指年轻人，比较级为 younger。",
                "Young people often learn languages quickly.", "年轻人学语言通常很快。"));
        put(words, w("people", "/ˈpiːpl/", "n.", "CET-4", "人；人们",
                "集体名词，作复数使用；the people 可指人民。",
                "Many people read on the subway.", "许多人在乘地铁时阅读。"));
        put(words, w("think", "/θɪŋk/", "v.", "CET-4", "思考；认为",
                "think independently 表示独立思考，过去式为 thought。",
                "I think practice is the key to progress.", "我认为练习是进步的关键。"),
                "think", "thinks", "thought", "thinking");
        put(words, w("team", "/tiːm/", "n.", "CET-4", "团队",
                "work in a team 表示团队协作，teamwork 是名词形式。",
                "Our team meets every Monday.", "我们团队每周一开会。"),
                "team", "teams", "teamwork");
        put(words, w("graduate", "/ˈɡrædʒuət/", "n. / v.", "CET-4", "毕业生；毕业",
                "college graduates 指高校毕业生，作动词表示毕业。",
                "She will graduate from college next year.", "她明年大学毕业。"),
                "graduate", "graduates", "graduated", "graduating", "graduation");
        put(words, w("college", "/ˈkɒlɪdʒ/", "n.", "CET-4", "学院；大学",
                "college students 指大学生，与 university 常可互换。",
                "He is studying at a college in the city.", "他在城里的一所学院读书。"),
                "college", "colleges");
        put(words, w("university", "/ˌjuːnɪˈvɜːsəti/", "n.", "CET-4", "大学",
                "指综合性大学，复数 universities。",
                "The university has a large library.", "这所大学有一座大图书馆。"),
                "university", "universities");
        put(words, w("increase", "/ɪnˈkriːs/", "v. / n.", "CET-4", "增加；增长",
                "作动词重音在后，作名词重音在前；反义词为 decrease。",
                "The number of readers continues to increase.", "读者人数持续增加。"),
                "increase", "increases", "increased", "increasing", "increasingly");
        put(words, w("decrease", "/dɪˈkriːs/", "v. / n.", "CET-4", "减少；下降",
                "与 increase 相对，可作动词或名词。",
                "Costs decrease when demand falls.", "需求下降时成本也会下降。"),
                "decrease", "decreases", "decreased", "decreasing");
        put(words, w("improve", "/ɪmˈpruːv/", "v.", "CET-4", "改善；提高",
                "可指提高水平、改善条件，名词为 improvement。",
                "Daily practice will improve your writing.", "每天练习会提高你的写作。"),
                "improve", "improves", "improved", "improving", "improvement");
        put(words, w("society", "/səˈsaɪəti/", "n.", "CET-4", "社会",
                "指社会整体，也可指社团；复数 societies。",
                "Education plays a key role in society.", "教育在社会中起关键作用。"),
                "society", "societies");
        put(words, w("education", "/ˌedʒuˈkeɪʃn/", "n.", "CET-4", "教育",
                "由 educate 变来的名词，指教育过程或教育事业。",
                "Education opens doors to new opportunities.", "教育为新的机会打开大门。"));
        put(words, w("environment", "/ɪnˈvaɪrənmənt/", "n.", "CET-4", "环境",
                "指自然环境或 surrounding conditions，常与 protect 搭配。",
                "We should protect the environment.", "我们应该保护环境。"),
                "environment", "environments", "environmental");
        put(words, w("technology", "/tekˈnɒlədʒi/", "n.", "CET-4", "技术",
                "指科技与技术手段，复数 technologies。",
                "Technology makes communication faster.", "技术让交流更快捷。"),
                "technology", "technologies", "technological");
        put(words, w("culture", "/ˈkʌltʃə/", "n.", "CET-4", "文化",
                "指一个群体的价值观、习俗与艺术，形容词 cultural。",
                "Food is an important part of culture.", "饮食是文化的重要组成部分。"),
                "culture", "cultures", "cultural");
        put(words, w("benefit", "/ˈbenɪfɪt/", "n. / v.", "CET-4", "益处；受益",
                "benefit from 表示从……中受益，be beneficial to 表示对……有益。",
                "Students benefit from regular feedback.", "学生从定期反馈中受益。"),
                "benefit", "benefits", "benefited", "benefiting", "beneficial");
        put(words, w("volunteer", "/ˌvɒlənˈtɪə/", "n. / v.", "CET-4", "志愿者；自愿做",
                "作名词指志愿者，作动词指自愿做某事。",
                "She works as a volunteer at the library.", "她在图书馆做志愿者。"),
                "volunteer", "volunteers", "volunteered", "volunteering");
        put(words, w("community", "/kəˈmjuːnəti/", "n.", "CET-4", "社区；群体",
                "local community 指当地社区，复数 communities。",
                "The community built a new playground.", "社区修建了一个新操场。"),
                "community", "communities");
        put(words, w("experience", "/ɪkˈspɪəriəns/", "n. / v.", "CET-4", "经验；经历",
                "作不可数名词指经验，作可数名词指一段经历。",
                "Travel gives you valuable experience.", "旅行给你宝贵的经验。"),
                "experience", "experiences", "experienced");
        put(words, w("ability", "/əˈbɪləti/", "n.", "CET-4", "能力",
                "the ability to do sth 表示做某事的能力，复数 abilities。",
                "He has the ability to solve difficult problems.", "他有解决难题的能力。"),
                "ability", "abilities", "able");
        put(words, w("knowledge", "/ˈnɒlɪdʒ/", "n.", "CET-4", "知识",
                "不可数名词，常用 a piece of knowledge；搭配 gain knowledge。",
                "Reading broadens your knowledge.", "阅读拓宽你的知识面。"));
        put(words, w("opportunity", "/ˌɒpəˈtjuːnəti/", "n.", "CET-4", "机会",
                "opportunity to do sth 表示做某事的机会，复数 opportunities。",
                "This job is a great opportunity to learn.", "这份工作是很好的学习机会。"),
                "opportunity", "opportunities");
        put(words, w("challenge", "/ˈtʃælɪndʒ/", "n. / v.", "CET-4", "挑战",
                "作名词指难题，作动词指向……发起挑战。",
                "Learning grammar is a challenge for many students.", "学语法对许多学生来说是个挑战。"),
                "challenge", "challenges", "challenged", "challenging");
        put(words, w("success", "/səkˈses/", "n.", "CET-4", "成功",
                "指达成目标，形容词 successful，动词 succeed。",
                "Hard work is the key to success.", "努力是成功的关键。"),
                "success", "successes", "successful", "succeed");
        put(words, w("achieve", "/əˈtʃiːv/", "v.", "CET-4", "实现；达到",
                "achieve a goal 表示实现目标，名词为 achievement。",
                "You can achieve your goals with patience.", "有耐心你就能实现目标。"),
                "achieve", "achieves", "achieved", "achieving", "achievement");
        put(words, w("require", "/rɪˈkwaɪə/", "v.", "CET-4", "需要；要求",
                "require sb to do sth 表示要求某人做某事，名词 requirement。",
                "These tasks require careful planning.", "这些任务需要仔细规划。"),
                "require", "requires", "required", "requiring", "requirement");
        put(words, w("provide", "/prəˈvaɪd/", "v.", "CET-4", "提供",
                "provide sb with sth 或 provide sth for sb。",
                "The library provides free internet access.", "图书馆提供免费网络。"),
                "provide", "provides", "provided", "providing");
        put(words, w("consider", "/kənˈsɪdə/", "v.", "CET-4", "考虑；认为",
                "consider doing sth 表示考虑做某事，名词 consideration。",
                "Please consider joining our study group.", "请考虑加入我们的学习小组。"),
                "consider", "considers", "considered", "considering");
        put(words, w("involve", "/ɪnˈvɒlv/", "v.", "CET-6", "涉及；使参与",
                "be involved in 表示参与其中。",
                "The project will involve students from three classes.", "这个项目将涉及三个班的学生。"),
                "involve", "involves", "involved", "involving");
        put(words, w("manage", "/ˈmænɪdʒ/", "v.", "CET-4", "管理；设法做到",
                "manage to do sth 表示设法做成了某事，名词 manager。",
                "She manages to finish her work before dinner.", "她设法在晚饭前完成工作。"),
                "manage", "manages", "managed", "managing", "management");
        put(words, w("affect", "/əˈfekt/", "v.", "CET-4", "影响",
                "及物动词，affect 是动词、effect 是名词，注意区分。",
                "Lack of sleep can affect your memory.", "睡眠不足会影响你的记忆。"),
                "affect", "affects", "affected", "affecting");
        put(words, w("effect", "/ɪˈfekt/", "n.", "CET-4", "影响；效果",
                "have an effect on 表示对……有影响，常用复数 effects。",
                "The new rule had a positive effect on attendance.", "新规定对出勤率有积极影响。"),
                "effect", "effects", "effective");
        put(words, w("contribute", "/kənˈtrɪbjuːt/", "v.", "CET-4", "贡献；促成",
                "contribute to 表示对……有贡献或促成某事，名词 contribution。",
                "Everyone can contribute to a cleaner campus.", "每个人都能为更整洁的校园出力。"),
                "contribute", "contributes", "contributed", "contributing", "contribution");
        put(words, w("maintain", "/meɪnˈteɪn/", "v.", "CET-6", "维持；保养",
                "指保持某种状态或维护设备，名词 maintenance。",
                "It is important to maintain a steady pace.", "保持稳定的节奏很重要。"),
                "maintain", "maintains", "maintained", "maintaining");
        put(words, w("reduce", "/rɪˈdjuːs/", "v.", "CET-4", "减少；降低",
                "reduce sth by 表示减少了多少，名词 reduction。",
                "We should reduce the use of plastic bags.", "我们应该减少塑料袋的使用。"),
                "reduce", "reduces", "reduced", "reducing");
        put(words, w("support", "/səˈpɔːt/", "v. / n.", "CET-4", "支持；支撑",
                "可指支持观点或支撑重量，名词 supporter。",
                "Friends support each other during exams.", "朋友在考试期间互相支持。"),
                "support", "supports", "supported", "supporting");
        put(words, w("encourage", "/ɪnˈkʌrɪdʒ/", "v.", "CET-4", "鼓励",
                "encourage sb to do sth 表示鼓励某人做某事，名词 encouragement。",
                "Teachers encourage students to ask questions.", "老师鼓励学生提问。"),
                "encourage", "encourages", "encouraged", "encouraging");
        put(words, w("result", "/rɪˈzʌlt/", "n. / v.", "CET-4", "结果；导致",
                "as a result 表示因此；result in 表示导致。",
                "The result of the survey surprised everyone.", "调查结果让所有人吃惊。"),
                "result", "results", "resulted", "resulting");
        put(words, w("reason", "/ˈriːzn/", "n.", "CET-4", "原因；理由",
                "the reason for sth 表示某事的原因，动词为 reason。",
                "The main reason for the delay was rain.", "延误的主要原因是下雨。"),
                "reason", "reasons", "reasonable");
        put(words, w("language", "/ˈlæŋɡwɪdʒ/", "n.", "CET-4", "语言",
                "指自然语言或表达方式，复数 languages。",
                "Learning a language takes time and patience.", "学一门语言需要时间和耐心。"),
                "language", "languages");
        put(words, w("study", "/ˈstʌdi/", "v. / n.", "CET-4", "学习；研究",
                "作动词指学习或研究，作名词指学业、书房。",
                "I study English for an hour every day.", "我每天学一小时英语。"),
                "study", "studies", "studied", "studying");
        put(words, w("learn", "/lɜːn/", "v.", "CET-4", "学习；得知",
                "learn from 表示向……学习，过去式 learned 或 learnt。",
                "Children learn new words quickly.", "孩子们学新词很快。"),
                "learn", "learns", "learned", "learnt", "learning");
        put(words, w("word", "/wɜːd/", "n.", "CET-4", "单词；话语",
                "指语言单位或说的话，in other words 表示换句话说。",
                "Write each new word in a sentence.", "把每个新词写进句子里。"),
                "word", "words");
        put(words, w("sentence", "/ˈsentəns/", "n.", "CET-4", "句子",
                "指语法上完整的表达单位；也指判决。",
                "This sentence contains a useful phrase.", "这个句子包含一个有用的短语。"),
                "sentence", "sentences");
        put(words, w("meaning", "/ˈmiːnɪŋ/", "n.", "CET-4", "意思；意义",
                "指词句的含义或人生的意义，动词为 mean。",
                "Look up the meaning of unknown words.", "查一查生词的意思。"),
                "meaning", "meanings", "mean");

        // —— CET-6 高频词（衔接考研与六级阅读） ——
        put(words, w("abandon", "/əˈbændən/", "v.", "CET-6", "放弃；抛弃",
                "指彻底放弃某个计划、习惯或某件物品，也可指抛弃某人。",
                "They had to abandon the car in the snow.", "他们不得不把车丢在雪地里。"));
        put(words, w("absorb", "/əbˈzɔːb/", "v.", "CET-6", "吸收；专注于",
                "指吸收液体、热量或知识，be absorbed in 表示全神贯注于。",
                "Plants absorb water through their roots.", "植物通过根吸收水分。"));
        put(words, w("abstract", "/ˈæbstrækt/", "adj. / n.", "CET-6", "抽象的；摘要",
                "作形容词表示不具体的，作名词指论文摘要或抽象艺术。",
                "The abstract idea was hard to explain.", "这个抽象的概念很难解释。"));
        put(words, w("accelerate", "/əkˈseləreɪt/", "v.", "CET-6", "加速；促进",
                "指速度变快，也可指推动某个进程加快。",
                "The train began to accelerate after leaving the station.", "火车离开车站后开始加速。"));
        put(words, w("accomplish", "/əˈkʌmplɪʃ/", "v.", "CET-6", "完成；实现",
                "指成功完成某项任务或达到某个目标，名词 accomplishment。",
                "She managed to accomplish the task on time.", "她设法按时完成了任务。"));
        put(words, w("accumulate", "/əˈkjuːmjəleɪt/", "v.", "CET-6", "积累；堆积",
                "指知识、财富或物品逐渐增多，名词 accumulation。",
                "Dust can accumulate quickly on old books.", "旧书上的灰尘很快就会积起来。"));
        put(words, w("acknowledge", "/əkˈnɒlɪdʒ/", "v.", "CET-6", "承认；致谢",
                "指承认事实或收到并回应某物，名词 acknowledgement。",
                "He refused to acknowledge his mistake.", "他拒绝承认自己的错误。"));
        put(words, w("adequate", "/ˈædɪkwət/", "adj.", "CET-6", "足够的；恰当的",
                "指数量或质量满足需要，副词 adequately，反义词 inadequate。",
                "The room has adequate light for reading.", "这个房间的光线足够阅读。"));
        put(words, w("advocate", "/ˈædvəkeɪt/", "v. / n.", "CET-6", "提倡；拥护者",
                "作动词指公开支持某种做法，作名词指支持者。",
                "Many teachers advocate reading aloud every day.", "许多老师提倡每天朗读。"));
        put(words, w("allocate", "/ˈæləkeɪt/", "v.", "CET-6", "分配；拨给",
                "指按计划分配时间、资金或资源，名词 allocation。",
                "The school will allocate more funds to the library.", "学校将给图书馆拨更多资金。"));
        put(words, w("ambiguous", "/æmˈbɪɡjuəs/", "adj.", "CET-6", "模棱两可的",
                "指含义不明确、可作多种解释，名词 ambiguity。",
                "The ambiguous answer confused everyone.", "这个模棱两可的回答让所有人困惑。"));
        put(words, w("anticipate", "/ænˈtɪsɪpeɪt/", "v.", "CET-6", "预料；期望",
                "指预先想到某事会发生，名词 anticipation。",
                "We anticipate a rise in demand next month.", "我们预计下个月需求会上升。"));
        put(words, w("apparent", "/əˈpærənt/", "adj.", "CET-6", "明显的；表面上的",
                "指容易看出的，也可指表面上如此而实际未必，副词 apparently。",
                "It was apparent that the plan needed revision.", "很明显这个计划需要修改。"));
        put(words, w("assess", "/əˈses/", "v.", "CET-6", "评估；评定",
                "指对能力、价值或情况作出判断，名词 assessment。",
                "Teachers assess students' progress every term.", "老师每学期评估学生的进步。"));
        put(words, w("assumption", "/əˈsʌmpʃn/", "n.", "CET-6", "假设；承担",
                "指未经证实的假定，动词为 assume。",
                "The argument rests on a false assumption.", "这个论点建立在一个错误假设上。"));
        put(words, w("authentic", "/ɔːˈθentɪk/", "adj.", "CET-6", "真实的；正宗的",
                "指来源可靠、非伪造的，名词 authenticity。",
                "This is an authentic copy of the ancient map.", "这是古地图的真实副本。"));
        put(words, w("barrier", "/ˈbæriə/", "n.", "CET-6", "障碍；屏障",
                "指阻碍前进或交流的事物，常见搭配 language barrier。",
                "Language can be a barrier to communication.", "语言可能成为交流的障碍。"));
        put(words, w("bias", "/ˈbaɪəs/", "n. / v.", "CET-6", "偏见；使有偏见",
                "指不公正的倾向，形容词 biased。",
                "The report was criticized for its obvious bias.", "这份报告因明显的偏见受到批评。"));
        put(words, w("capacity", "/kəˈpæsəti/", "n.", "CET-6", "能力；容量",
                "指容纳量或完成某事的能力，常见搭配 at full capacity。",
                "The hall has a capacity of five hundred people.", "大厅可容纳五百人。"));
        put(words, w("comprehensive", "/ˌkɒmprɪˈhensɪv/", "adj.", "CET-6", "全面的；综合的",
                "指涵盖内容广泛而完整，名词 comprehension 也表示理解。",
                "The book offers a comprehensive guide to grammar.", "这本书提供了全面的语法指南。"));
        put(words, w("consistent", "/kənˈsɪstənt/", "adj.", "CET-6", "一致的；始终如一的",
                "指前后一致或持续稳定，名词 consistency，动词 consist。",
                "Her progress has been consistent all year.", "她这一年的进步一直很稳定。"));
        put(words, w("constitute", "/ˈkɒnstɪtjuːt/", "v.", "CET-6", "构成；组成",
                "指部分组成整体，常用 constitute a threat 表示构成威胁。",
                "These essays constitute the main part of the course.", "这些文章构成课程的主要部分。"));
        put(words, w("contemporary", "/kənˈtemprəri/", "adj. / n.", "CET-6", "当代的；同时代的人",
                "指属于当今时代的，也可指与某人同时代。",
                "The museum displays contemporary art from Asia.", "博物馆展出亚洲当代艺术。"));
        put(words, w("controversial", "/ˌkɒntrəˈvɜːʃl/", "adj.", "CET-6", "有争议的",
                "指引起广泛争论的，名词 controversy。",
                "The new policy remains controversial among parents.", "这项新政策在家长中仍有争议。"));
        put(words, w("crucial", "/ˈkruːʃl/", "adj.", "CET-6", "至关重要的",
                "指对结果起决定作用的，常见搭配 play a crucial role。",
                "Practice is crucial for language learners.", "练习对语言学习者至关重要。"));
        put(words, w("deliberate", "/dɪˈlɪbərət/", "adj. / v.", "CET-6", "故意的；仔细考虑",
                "作形容词指有意为之，作动词读 /dɪˈlɪbəreɪt/ 表示仔细商议。",
                "The mistake was deliberate, not accidental.", "这个错误是故意的，不是偶然的。"));
        put(words, w("demonstrate", "/ˈdemənstreɪt/", "v.", "CET-6", "证明；演示",
                "指用证据说明或实际操作展示，名词 demonstration。",
                "The experiment demonstrates how heat affects metal.", "这个实验说明了热量如何影响金属。"));
        put(words, w("diminish", "/dɪˈmɪnɪʃ/", "v.", "CET-6", "减少；削弱",
                "指数量、重要性或力量逐渐变小。",
                "Interest in the topic did not diminish over time.", "对这个话题的兴趣并未随时间减弱。"));
        put(words, w("distinguish", "/dɪˈstɪŋɡwɪʃ/", "v.", "CET-6", "区分；辨别",
                "distinguish A from B 表示把 A 与 B 区分开，形容词 distinct。",
                "Children learn to distinguish fact from opinion.", "孩子们学习区分事实与观点。"));
        put(words, w("diverse", "/daɪˈvɜːs/", "adj.", "CET-6", "多样的；不同的",
                "指种类丰富、各不相同，名词 diversity。",
                "The city has a diverse population.", "这座城市人口多元。"));
        put(words, w("eliminate", "/ɪˈlɪmɪneɪt/", "v.", "CET-6", "消除；淘汰",
                "指彻底去除某物或在比赛中淘汰对手，名词 elimination。",
                "We must eliminate waste in the production process.", "我们必须消除生产过程中的浪费。"));
        put(words, w("emerge", "/ɪˈmɜːdʒ/", "v.", "CET-6", "出现；浮现",
                "指从隐藏状态显现或新事物产生，名词 emergence。",
                "New evidence began to emerge during the trial.", "审判过程中开始出现新证据。"));
        put(words, w("enhance", "/ɪnˈhɑːns/", "v.", "CET-6", "提高；增强",
                "指改善质量或增强效果，名词 enhancement。",
                "Regular exercise can enhance your memory.", "规律锻炼可以增强记忆力。"));
        put(words, w("essential", "/ɪˈsenʃl/", "adj. / n.", "CET-6", "必要的；本质的",
                "指不可缺少的，名词 essence。",
                "Water is essential to all living things.", "水对所有生物都必不可少。"));
        put(words, w("evaluate", "/ɪˈvæljueɪt/", "v.", "CET-6", "评价；评估",
                "指系统地判断价值或效果，名词 evaluation。",
                "The committee will evaluate every application carefully.", "委员会将仔细评估每份申请。"));
        put(words, w("evident", "/ˈevɪdənt/", "adj.", "CET-6", "明显的",
                "指容易被看出的，名词 evidence 表示证据。",
                "It is evident that the team worked hard.", "很明显这个团队付出了努力。"));
        put(words, w("exaggerate", "/ɪɡˈzædʒəreɪt/", "v.", "CET-6", "夸大；夸张",
                "指把事实说得比实际更严重或更大，名词 exaggeration。",
                "Do not exaggerate the difficulty of the exam.", "不要夸大考试的难度。"));
        put(words, w("explicit", "/ɪkˈsplɪsɪt/", "adj.", "CET-6", "明确的；直截了当的",
                "指表达清楚、不含糊，反义词 implicit。",
                "The teacher gave explicit instructions for the essay.", "老师对作文给出了明确要求。"));
        put(words, w("facilitate", "/fəˈsɪlɪteɪt/", "v.", "CET-6", "促进；使便利",
                "指让某个过程更容易进行，名词 facilitation。",
                "Good notes can facilitate revision.", "好的笔记能让复习更顺利。"));
        put(words, w("fundamental", "/ˌfʌndəˈmentl/", "adj. / n.", "CET-6", "基本的；根本的",
                "指构成基础或最重要的，名词 fundament。",
                "Reading is a fundamental skill for students.", "阅读是学生的一项基本技能。"));
        put(words, w("generate", "/ˈdʒenəreɪt/", "v.", "CET-6", "产生；生成",
                "指产生能量、兴趣或收入，名词 generation。",
                "The new factory will generate hundreds of jobs.", "新工厂将创造数百个工作岗位。"));
        put(words, w("illustrate", "/ˈɪləstreɪt/", "v.", "CET-6", "说明；举例说明",
                "指用例子或图表解释，名词 illustration。",
                "The chart illustrates the changes in rainfall.", "这张图表说明了降雨量的变化。"));
        put(words, w("imply", "/ɪmˈplaɪ/", "v.", "CET-6", "暗示；意味着",
                "指没有直接说出但可推断出来，名词 implication。",
                "Her silence seemed to imply agreement.", "她的沉默似乎暗示同意。"));
        put(words, w("inevitable", "/ɪnˈevɪtəbl/", "adj.", "CET-6", "不可避免的",
                "指必然发生、无法避免，副词 inevitably。",
                "Change is inevitable in a growing city.", "在发展中的城市里，变化不可避免。"));
        put(words, w("innovate", "/ˈɪnəveɪt/", "v.", "CET-6", "创新；革新",
                "指引入新方法或新产品，名词 innovation。",
                "Companies must innovate to stay competitive.", "公司必须创新才能保持竞争力。"));
        put(words, w("integrate", "/ˈɪntɪɡreɪt/", "v.", "CET-6", "整合；融入",
                "integrate A into B 表示把 A 融入 B，名词 integration。",
                "The course integrates theory with practice.", "这门课程把理论与实践结合起来。"));
        put(words, w("interpret", "/ɪnˈtɜːprɪt/", "v.", "CET-6", "解释；口译",
                "指理解并说明含义，也可指口译，名词 interpretation。",
                "Different readers may interpret the poem differently.", "不同的读者对这首诗可能有不同的解读。"));
        put(words, w("justify", "/ˈdʒʌstɪfaɪ/", "v.", "CET-6", "证明……正当；为……辩护",
                "指提供理由说明某事合理，名词 justification。",
                "He tried to justify his absence from class.", "他试图为自己的缺课辩解。"));
        put(words, w("modify", "/ˈmɒdɪfaɪ/", "v.", "CET-6", "修改；调整",
                "指对计划、设计或行为作较小改动，名词 modification。",
                "You may modify the plan if necessary.", "如有必要，你可以修改计划。"));
        put(words, w("neglect", "/nɪˈɡlekt/", "v. / n.", "CET-6", "忽视；疏忽",
                "指没有给予应有的注意或照顾，形容词 negligent。",
                "Do not neglect the basic rules of grammar.", "不要忽视基本语法规则。"));
        put(words, w("obtain", "/əbˈteɪn/", "v.", "CET-6", "获得；得到",
                "指通过努力取得某物，比 get 更正式。",
                "Students can obtain the form from the office.", "学生可以从办公室领取表格。"));
        put(words, w("participate", "/pɑːˈtɪsɪpeɪt/", "v.", "CET-6", "参与；参加",
                "participate in 表示参加某项活动，名词 participation。",
                "All members are encouraged to participate in the discussion.", "鼓励所有成员参与讨论。"));
        put(words, w("perceive", "/pəˈsiːv/", "v.", "CET-6", "察觉；认为",
                "指通过感官注意到或用某种方式看待，名词 perception。",
                "We perceive the world through our senses.", "我们通过感官感知世界。"));
        put(words, w("persist", "/pəˈsɪst/", "v.", "CET-6", "坚持；持续存在",
                "指尽管困难仍继续，或问题持续存在，名词 persistence。",
                "If the problem persists, please contact us.", "如果问题持续存在，请联系我们。"));
        put(words, w("phenomenon", "/fəˈnɒmɪnən/", "n.", "CET-6", "现象",
                "指可观察到的现象，复数形式为 phenomena。",
                "This social phenomenon deserves careful study.", "这一社会现象值得仔细研究。"));
        put(words, w("priority", "/praɪˈɒrəti/", "n.", "CET-6", "优先事项；优先权",
                "指需要最先处理的事情，常见搭配 top priority。",
                "Safety is our highest priority.", "安全是我们最优先考虑的事。"));
        put(words, w("reluctant", "/rɪˈlʌktənt/", "adj.", "CET-6", "不情愿的；勉强的",
                "be reluctant to do sth 表示不愿做某事，名词 reluctance。",
                "She was reluctant to speak in public.", "她不愿意在公开场合发言。"));
        put(words, w("retain", "/rɪˈteɪn/", "v.", "CET-6", "保留；保持",
                "指继续拥有或保持某种状态，名词 retention。",
                "The old building retains much of its charm.", "这座老建筑仍保留着许多魅力。"));
        put(words, w("reveal", "/rɪˈviːl/", "v.", "CET-6", "揭示；透露",
                "指让原本隐藏的信息显现出来，名词 revelation。",
                "The survey reveals a change in reading habits.", "这项调查揭示了阅读习惯的变化。"));
        put(words, w("sustain", "/səˈsteɪn/", "v.", "CET-6", "维持；支撑",
                "指使某状态持续或承受重量，形容词 sustainable。",
                "It is hard to sustain such rapid growth.", "要维持这样快速的增长很困难。"));

        return Map.copyOf(words);
    }

    /** 完整词条：含双语例句。 */
    private static WordEntry w(String lemma, String phonetic, String partOfSpeech, String cetLevel,
                               String meaning, String explanation, String exampleSentence,
                               String exampleTranslation) {
        return new WordEntry(lemma, phonetic, partOfSpeech, cetLevel, meaning, explanation,
                exampleSentence, exampleTranslation);
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
