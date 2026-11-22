package com.cethelper.android;

import org.json.JSONException;
import org.json.JSONObject;

final class DictionaryEntry {
    final String word;
    final String lemma;
    final String phonetic;
    final String partOfSpeech;
    final String cetLevel;
    final String meaning;
    final String explanation;
    final String exampleSentence;
    final String exampleTranslation;
    final String mode;

    DictionaryEntry(String word, String lemma, String phonetic, String partOfSpeech,
                    String cetLevel, String meaning, String explanation,
                    String exampleSentence, String exampleTranslation, String mode) {
        this.word = safe(word);
        this.lemma = safe(lemma);
        this.phonetic = safe(phonetic);
        this.partOfSpeech = safe(partOfSpeech);
        this.cetLevel = safe(cetLevel);
        this.meaning = safe(meaning);
        this.explanation = safe(explanation);
        this.exampleSentence = safe(exampleSentence);
        this.exampleTranslation = safe(exampleTranslation);
        this.mode = safe(mode);
    }

    static DictionaryEntry fromJson(JSONObject o) throws JSONException {
        if (o == null) {
            throw new JSONException("Missing dictionary entry");
        }
        String word = ApiClient.requiredText(o, "word");
        String lemma = ApiClient.optClean(o, "lemma");
        if (lemma.isEmpty()) {
            lemma = word;
        }
        String mode = ApiClient.optClean(o, "mode");
        if (mode.isEmpty()) {
            mode = "demo";
        }
        return new DictionaryEntry(
                word,
                lemma,
                ApiClient.optClean(o, "phonetic"),
                ApiClient.optClean(o, "partOfSpeech"),
                ApiClient.optClean(o, "cetLevel"),
                ApiClient.requiredText(o, "meaning"),
                ApiClient.optClean(o, "explanation"),
                ApiClient.optClean(o, "exampleSentence"),
                ApiClient.optClean(o, "exampleTranslation"),
                mode
        );
    }

    static DictionaryEntry fromHistory(DictionaryHistory h) {
        return new DictionaryEntry(
                h.word,
                h.lemma.isEmpty() ? h.word : h.lemma,
                h.phonetic,
                h.partOfSpeech,
                h.cetLevel,
                h.meaning,
                h.explanation,
                h.exampleSentence,
                "",
                h.mode
        );
    }

    DictionaryHistory toHistory(String serverUrl, long studentId, long now) {
        String keyWord = lemma.isEmpty() ? word : lemma;
        return new DictionaryHistory(
                serverUrl,
                studentId,
                keyWord,
                phonetic,
                partOfSpeech,
                lemma,
                cetLevel,
                meaning,
                explanation,
                exampleSentence,
                mode.isEmpty() ? "demo" : mode,
                now
        );
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
