package com.cethelper.server.dictionary;

import com.cethelper.server.explain.DemoExplanationCatalog;

/** 词典联想条目，字段名与 Android 端 {@code DictionarySuggestion} 严格一致。 */
public record DictionarySuggestion(String word, String phonetic, String partOfSpeech,
                                   String cetLevel, String meaning) {

    public static DictionarySuggestion from(DemoExplanationCatalog.WordEntry entry) {
        return new DictionarySuggestion(entry.lemma(), entry.phonetic(), entry.partOfSpeech(),
                entry.cetLevel(), entry.meaning());
    }
}
