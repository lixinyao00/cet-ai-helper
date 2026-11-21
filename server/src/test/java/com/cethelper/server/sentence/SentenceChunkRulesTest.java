package com.cethelper.server.sentence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 片段定位规则的纯逻辑测试（不启动 Spring）：重复标记、乱序纠正、确实重叠三类边界。
 *
 * <p>关键回归：同一句里出现两个 that 时，两个片段的首次出现位置相同，
 * 旧的「按首次出现位置排序」会把两段挤到同一处、误判为无法定位；
 * 现在改为优先按上游顺序从上一段结束位置向后匹配。</p>
 */
@DisplayName("片段定位与排序规则")
class SentenceChunkRulesTest {

    private static final String DOUBLE_THAT = "They said that practice matters and that patience helps.";

    private static SentenceChunk chunk(String text, String role, String explanation) {
        return new SentenceChunk(text, role, explanation);
    }

    @Test
    @DisplayName("重复标记按给定顺序各自定位，role 与 explanation 原样保留")
    void duplicatedMarkersKeepGivenOrderAndExplanations() {
        List<SentenceChunk> chunks = List.of(
                chunk("They said", "主句主语 + 谓语", "主句：他们说"),
                chunk("that", "第一个宾语从句的引导词", "第一个 that 引出练习很重要"),
                chunk("practice matters", "第一个从句的主干", "练习很重要"),
                chunk("and", "并列连词", "并列两个从句"),
                chunk("that", "第二个宾语从句的引导词", "第二个 that 引出耐心有帮助"),
                chunk("patience helps", "第二个从句的主干", "耐心有帮助"));

        List<SentenceChunk> ordered = SentenceChunkRules.placeInOriginalOrder(DOUBLE_THAT, chunks);

        assertThat(ordered).as("顺序本身正确时必须原样返回，不能重排").containsExactlyElementsOf(chunks);
        assertThat(ordered.get(1).role()).isEqualTo("第一个宾语从句的引导词");
        assertThat(ordered.get(4).role()).isEqualTo("第二个宾语从句的引导词");
        assertThat(ordered.get(1).explanation()).isNotEqualTo(ordered.get(4).explanation());
    }

    @Test
    @DisplayName("无歧义的乱序片段仍被纠正为原句顺序")
    void unambiguousOutOfOrderChunksAreReordered() {
        List<SentenceChunk> reversed = List.of(
                chunk("patience helps", "第二个从句的主干", "耐心有帮助"),
                chunk("They said", "主句主语 + 谓语", "主句：他们说"),
                chunk("that practice matters", "第一个宾语从句", "第一个从句：练习很重要"));

        List<SentenceChunk> ordered = SentenceChunkRules.placeInOriginalOrder(
                "They said that practice matters and that patience helps.", reversed);

        assertThat(ordered).extracting(SentenceChunk::text)
                .containsExactly("They said", "that practice matters", "patience helps");
    }

    @Test
    @DisplayName("原句只有一个 that、却给了两个：抛格式异常（不会硬塞进同一处）")
    void duplicatedMarkerWithoutSecondOccurrenceIsRejected() {
        List<SentenceChunk> chunks = List.of(
                chunk("They said", "主句主语 + 谓语", "主句"),
                chunk("that", "第一个宾语从句的引导词", "第一个 that"),
                chunk("that", "第二个宾语从句的引导词", "第二个 that"));

        assertThatThrownBy(() -> SentenceChunkRules.placeInOriginalOrder(
                "They said that practice matters.", chunks))
                .isInstanceOf(SentenceAnalysisFormatException.class)
                .hasMessageContaining("无法按顺序放置");
    }

    @Test
    @DisplayName("片段确实互相重叠：抛格式异常")
    void genuinelyOverlappingChunksAreRejected() {
        List<SentenceChunk> chunks = List.of(
                chunk("practice matters", "整段", "练习很重要"),
                chunk("practice", "前半段", "练习"));

        assertThatThrownBy(() -> SentenceChunkRules.placeInOriginalOrder(
                "They said that practice matters.", chunks))
                .isInstanceOf(SentenceAnalysisFormatException.class);
    }

    @Test
    @DisplayName("片段不在原句中：抛格式异常")
    void chunkNotInSentenceIsRejected() {
        List<SentenceChunk> chunks = List.of(chunk("homework helps", "主干", "作业有帮助"));

        assertThatThrownBy(() -> SentenceChunkRules.placeInOriginalOrder(DOUBLE_THAT, chunks))
                .isInstanceOf(SentenceAnalysisFormatException.class)
                .hasMessageContaining("不是原句中的连续原文");
    }
}
