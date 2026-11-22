package com.cethelper.server.dictionary;

/** 查词请求：{@code {"word":"abandoned"}}。 */
public record DictionaryLookupRequest(String word) {
}
