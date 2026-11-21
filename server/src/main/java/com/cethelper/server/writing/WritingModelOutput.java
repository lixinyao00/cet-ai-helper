package com.cethelper.server.writing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

final class WritingModelOutput {

    private WritingModelOutput() {
    }

    static ObjectReader reader(ObjectMapper mapper) {
        return mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
    }

    static JsonNode readObject(ObjectReader reader, String content) throws JsonProcessingException {
        JsonNode payload = reader.readTree(content);
        if (payload == null || !payload.isObject()) {
            throw new IllegalArgumentException();
        }
        return payload;
    }

    static String requireChineseText(JsonNode node) {
        String value = requireText(node, 400);
        if (value.codePoints().noneMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN)) {
            throw new IllegalArgumentException();
        }
        return value;
    }

    static String requireText(JsonNode node, int maxLength) {
        if (!node.isTextual() || node.textValue().isBlank() || node.textValue().length() > maxLength) {
            throw new IllegalArgumentException();
        }
        return node.textValue();
    }
}
