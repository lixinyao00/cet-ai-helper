package com.cethelper.android;

import org.json.JSONException;
import org.json.JSONObject;

final class DictionarySuggestion {
    final String word;
    final String phonetic;
    final String partOfSpeech;
    final String cetLevel;
    final String meaning;

    DictionarySuggestion(String word, String phonetic, String partOfSpeech,
                         String cetLevel, String meaning) {
        this.word = word == null ? "" : word;
        this.phonetic = phonetic == null ? "" : phonetic;
        this.partOfSpeech = partOfSpeech == null ? "" : partOfSpeech;
        this.cetLevel = cetLevel == null ? "" : cetLevel;
        this.meaning = meaning == null ? "" : meaning;
    }

    static DictionarySuggestion fromJson(JSONObject o) throws JSONException {
        if (o == null) {
            throw new JSONException("Missing suggestion object");
        }
        String word = ApiClient.requiredText(o, "word");
        return new DictionarySuggestion(
                word,
                ApiClient.optClean(o, "phonetic"),
                ApiClient.optClean(o, "partOfSpeech"),
                ApiClient.optClean(o, "cetLevel"),
                ApiClient.optClean(o, "meaning")
        );
    }
}
