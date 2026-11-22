package com.cethelper.server.tutor;

import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * demo 模式的助教答复：不调用模型，按上下文类型与关键词生成预设回答。
 *
 * <p>答复带有四六级助教口吻，并尽量引用 {@code focusText}，便于客户端演示与自动化测试。</p>
 */
@Component
public class DemoTutorCatalog {

    /** demo 答复正文与推荐追问。 */
    public record DemoReply(String text, List<String> followUps) {
    }

    public DemoReply reply(TutorContextPayload context, String lastUserMessage) {
        TutorContextPayload effective = context == null ? TutorContextPayload.free() : context;
        String type = normalizeType(effective.type());
        String focus = blankToNull(effective.focusText());
        String userText = lastUserMessage == null ? "" : lastUserMessage.strip();

        StringBuilder text = new StringBuilder();
        switch (type) {
            case TutorContextPayload.WORD -> {
                if (focus != null) {
                    text.append("我们先看 ").append(focus).append(" 这个词。")
                            .append("在四六级阅读里，判断它的词性和常见搭配比背单一释义更重要：")
                            .append("先看它在句中作主语、谓语还是修饰成分，再结合前后文确定具体含义。")
                            .append("你可以试着用 ").append(focus)
                            .append(" 自己造一个短句，我会帮你检查搭配是否自然。");
                } else {
                    text.append("你好，我是你的四六级英语助教。")
                            .append("你可以把要分析的单词发给我，我会从词义、常见搭配、词性变化和易混词四个方面帮你拆解。")
                            .append("如果这个词来自某篇阅读，也可以把原句一起发来，我结合语境解释会更准确。");
                }
                if (userText.contains("区别") || userText.contains("辨析")) {
                    text.append("辨析近义词时，先比较核心义，再比较搭配和语体正式程度。");
                }
            }
            case TutorContextPayload.SENTENCE -> {
                if (focus != null) {
                    text.append("我们按“先主干、后修饰”的顺序看这句话：").append(focus)
                            .append("。先找到主语和谓语，再把从句、介词短语和非谓语结构逐一挂回主干。")
                            .append("四六级长难句的难点通常不在生词，而在修饰成分的层次，理清层次后再翻译就顺了。");
                } else {
                    text.append("把你想分析的英文长句发给我，我会先划出主干，再说明每个从句和修饰成分的作用。")
                            .append("你也可以告诉我哪一部分最难理解，我会针对那一块给出更细的拆解。");
                }
            }
            case TutorContextPayload.TRANSLATION -> {
                if (focus != null) {
                    text.append("关于这段译文：").append(focus)
                            .append("。评价翻译时先看信息是否完整，再看中文是否通顺，最后检查有没有生硬直译。")
                            .append("四六级翻译更看重表达自然，遇到中文长句可以先拆分意群，再用合适的连接词重组。");
                } else {
                    text.append("把你的中文原文和英文译文发给我，我会从准确性、通顺度和用词三个角度给出修改建议。")
                            .append("如果某一句特别难处理，也可以单独拿出来，我们一起拆分意群。");
                }
            }
            case TutorContextPayload.WRITING -> {
                if (focus != null) {
                    text.append("这篇作文的主题是“").append(focus)
                            .append("”。建议先用一句明确的中心句立论，再按“观点—例证—总结”展开主体段。")
                            .append("语言上注意避免重复使用同一个连接词，并检查主谓一致和时态。");
                } else {
                    text.append("把你的作文题目和正文发给我，我会从结构、用词和语法三个方面给出修改建议。")
                            .append("如果时间有限，优先检查开头段是否点题、主体段是否有例证、结尾是否回应题目。");
                }
            }
            default -> {
                text.append("你好，我是你的四六级英语助教。")
                        .append("无论是词汇辨析、长难句拆解、翻译润色还是写作提分，都可以直接问我。")
                        .append("告诉我你的目标和当前分数，我还能帮你安排一份可执行的复习计划。");
            }
        }
        return new DemoReply(text.toString(), followUps(effective));
    }

    /** live 模式生成 done 事件时也用本地规则产出推荐追问，不再调用模型。 */
    public List<String> followUps(TutorContextPayload context) {
        String type = normalizeType(context == null ? null : context.type());
        return switch (type) {
            case TutorContextPayload.WORD -> List.of(
                    "能再给我两个例句吗？",
                    "它的名词形式怎么用？",
                    "和近义词有什么区别？");
            case TutorContextPayload.SENTENCE -> List.of(
                    "帮我划一下句子主干。",
                    "这个从句修饰哪个成分？",
                    "能换一种更简单的说法吗？");
            case TutorContextPayload.TRANSLATION -> List.of(
                    "哪里翻译得不够地道？",
                    "能给我一个参考译文吗？",
                    "这个词还有别的译法吗？");
            case TutorContextPayload.WRITING -> List.of(
                    "帮我改一下开头段。",
                    "有哪些高级替换词？",
                    "结构上还能怎么调整？");
            default -> List.of(
                    "帮我制定一个四六级复习计划。",
                    "阅读题总是做不完怎么办？",
                    "作文如何快速提分？");
        };
    }

    private static String normalizeType(String type) {
        if (type == null || type.isBlank()) {
            return TutorContextPayload.FREE;
        }
        return type.strip().toUpperCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }
}
