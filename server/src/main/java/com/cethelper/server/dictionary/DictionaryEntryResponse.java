package com.cethelper.server.dictionary;

import com.cethelper.server.explain.DemoExplanationCatalog;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 查词成功响应。
 *
 * <p>{@code word} 是用户输入（规范化后小写），{@code lemma} 是还原后的原形；
 * {@code mode} 表示服务端运行模式（demo / live），与 SSE {@code meta.mode} 语义一致。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DictionaryEntryResponse(String word,
                                      String lemma,
                                      String phonetic,
                                      String partOfSpeech,
                                      String cetLevel,
                                      String meaning,
                                      String explanation,
                                      String exampleSentence,
                                      String exampleTranslation,
                                      String mode) {

    public static DictionaryEntryResponse from(String word, DemoExplanationCatalog.WordEntry entry, String mode) {
        return new DictionaryEntryResponse(word, entry.lemma(), entry.phonetic(), entry.partOfSpeech(),
                entry.cetLevel(), entry.meaning(), entry.explanation(), entry.exampleSentence(),
                entry.exampleTranslation(), mode);
    }
}
