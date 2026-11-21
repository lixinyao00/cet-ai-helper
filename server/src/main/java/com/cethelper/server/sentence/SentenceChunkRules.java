package com.cethelper.server.sentence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 片段校验与排序规则：demo 内置数据与 live 模型输出共用同一套约束。
 *
 * <p>客户端依赖的契约是「每个片段的 text 都是原句中的连续原文，并按原句顺序排列」。
 * 定位方式优先信任上游给出的顺序：从上一段结束位置向后匹配每段原文。
 * 这样同一句里出现两次相同标记（例如两个 that）时，先出现的那段用它自己的那次出现，
 * 后出现的那段用后面那次，不会因为「首次出现位置相同」被误判成乱序或重叠。
 * 只有按上游顺序定位失败（确实写反了）时，才按各段首次出现位置做稳定排序再试一次；
 * 两种定位都失败说明片段不在原句中、或片段确实互相重叠，一律视为不符合约定。</p>
 */
final class SentenceChunkRules {

    /** 一次拆解最多返回的片段数，防止模型输出异常膨胀。 */
    static final int MAX_CHUNKS = 20;

    /** 单个片段的长度上限，超过必然不是原句中的连续片段。 */
    static final int MAX_CHUNK_TEXT_LENGTH = 300;

    private SentenceChunkRules() {
    }

    /**
     * 校验并返回按原句顺序排列的片段。
     *
     * @param sentence 已去掉首尾空白的原句
     * @param chunks   待校验的片段（可能来自内置数据或模型）
     * @return 去掉片段与字段首尾空白、按原句顺序排列后的新列表（保留每段自己的 role 与 explanation）
     * @throws SentenceAnalysisFormatException 片段为空、字段缺失、片段不在原句中或片段互相重叠到无法放置
     */
    static List<SentenceChunk> placeInOriginalOrder(String sentence, List<SentenceChunk> chunks) {
        if (sentence == null || sentence.isBlank()) {
            throw new SentenceAnalysisFormatException("原句为空，无法定位片段");
        }
        if (chunks == null || chunks.isEmpty()) {
            throw new SentenceAnalysisFormatException("chunks 为空");
        }
        if (chunks.size() > MAX_CHUNKS) {
            throw new SentenceAnalysisFormatException("chunks 数量超过上限：" + chunks.size());
        }

        List<SentenceChunk> cleaned = new ArrayList<>(chunks.size());
        List<Integer> firstPositions = new ArrayList<>(chunks.size());
        for (SentenceChunk chunk : chunks) {
            if (chunk == null) {
                throw new SentenceAnalysisFormatException("chunks 含空元素");
            }
            String text = chunk.text() == null ? "" : chunk.text().strip();
            String role = chunk.role() == null ? "" : chunk.role().strip();
            String explanation = chunk.explanation() == null ? "" : chunk.explanation().strip();
            if (text.isEmpty() || role.isEmpty() || explanation.isEmpty()) {
                throw new SentenceAnalysisFormatException("片段缺少非空的 text/role/explanation");
            }
            if (text.length() > MAX_CHUNK_TEXT_LENGTH) {
                throw new SentenceAnalysisFormatException("片段过长：" + text.length());
            }
            int firstPosition = sentence.indexOf(text);
            if (firstPosition < 0) {
                throw new SentenceAnalysisFormatException("片段不是原句中的连续原文");
            }
            cleaned.add(new SentenceChunk(text, role, explanation));
            firstPositions.add(firstPosition);
        }

        // 优先按上游给出的顺序从上一段结束位置向后匹配：顺序本身正确时原样返回，
        // 重复标记（如两个 that）各自落在自己那次出现上。
        if (canPlaceForward(sentence, cleaned)) {
            return List.copyOf(cleaned);
        }

        // 顺序确实不对时才纠正：按首次出现位置稳定排序再定位一次，只对无歧义的乱序有效。
        List<Integer> order = new ArrayList<>(cleaned.size());
        for (int i = 0; i < cleaned.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.comparingInt(firstPositions::get));
        List<SentenceChunk> sortedByFirstOccurrence = new ArrayList<>(cleaned.size());
        for (int index : order) {
            sortedByFirstOccurrence.add(cleaned.get(index));
        }
        if (canPlaceForward(sentence, sortedByFirstOccurrence)) {
            return List.copyOf(sortedByFirstOccurrence);
        }

        throw new SentenceAnalysisFormatException("片段在原句中重叠或重复，无法按顺序放置");
    }

    /** 从上一段结束位置向后逐段匹配，全部命中即为可放置（片段之间不重叠）。 */
    private static boolean canPlaceForward(String sentence, List<SentenceChunk> chunks) {
        int cursor = 0;
        for (SentenceChunk chunk : chunks) {
            int found = sentence.indexOf(chunk.text(), cursor);
            if (found < 0) {
                return false;
            }
            cursor = found + chunk.text().length();
        }
        return true;
    }
}
