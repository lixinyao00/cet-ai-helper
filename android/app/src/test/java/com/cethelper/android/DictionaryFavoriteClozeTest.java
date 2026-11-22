package com.cethelper.android;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class DictionaryFavoriteClozeTest {
    @Test
    public void dictionaryFavoriteWithExampleProducesClozeQuestionWithoutArticle() {
        DictionaryEntry entry = new DictionaryEntry(
                "abandoned",
                "abandon",
                "/əˈbændən/",
                "v.",
                "CET-4",
                "放弃；抛弃",
                "用法说明：abandon sth",
                "They had to abandon the car in the snow.",
                "他们不得不把车丢弃在雪地里。",
                "demo"
        );
        Favorite fav = Favorite.fromDictionary("https://example.test", 7L, entry, 1000L);

        assertEquals(Favorite.DICT_ARTICLE_ID, fav.articleId);
        assertEquals(Favorite.SOURCE_DICTIONARY, fav.sourceType);
        assertEquals("abandon", fav.selection);
        assertEquals("abandon", fav.lemma);
        assertEquals("CET-4", fav.cetLevel);
        assertTrue(fav.explanation.endsWith("\n例句翻译：他们不得不把车丢弃在雪地里。"));
        assertEquals(12, fav.startOffset);
        assertEquals(19, fav.endOffset);

        ClozeQuestion q = ClozeQuestion.from(null, fav);
        assertNotNull(q);
        assertEquals("They had to ____ the car in the snow.", q.prompt());
        assertEquals("词典查词", q.questionTitle());
        assertEquals(Favorite.DICT_ARTICLE_ID, q.article().id());
        assertEquals("词典查词", q.article().title());
        assertTrue(q.matches("  ABANDON "));
        assertFalse(q.matches("abandoned"));
    }

    @Test
    public void dictionaryFavoriteFallsBackToCaseInsensitiveIndexOfWhenOffsetsZero() {
        Favorite fav = new Favorite(
                "https://example.test", 7L, Favorite.DICT_ARTICLE_ID,
                0, 0, "abandon",
                "Abandon hope all ye who enter here.",
                "放弃", "用法说明", "demo", 1000L,
                "/əˈbændən/", "v.", "abandon", "CET-4", Favorite.SOURCE_DICTIONARY
        );

        ClozeQuestion q = ClozeQuestion.from(null, fav);
        assertNotNull(q);
        assertEquals("____ hope all ye who enter here.", q.prompt());
        assertTrue(q.matches("abandon"));
    }

    @Test
    public void dictionaryFavoriteWithoutExampleOrMatchReturnsNull() {
        DictionaryEntry noExample = new DictionaryEntry(
                "abandon", "abandon", "", "v.", "CET-4",
                "放弃", "说明", "", "", "demo"
        );
        Favorite emptyFav = Favorite.fromDictionary("https://example.test", 7L, noExample, 1000L);
        assertEquals(0, emptyFav.startOffset);
        assertEquals(0, emptyFav.endOffset);
        assertNull(ClozeQuestion.from(null, emptyFav));

        DictionaryEntry unmatched = new DictionaryEntry(
                "abandon", "abandon", "", "v.", "CET-4",
                "放弃", "说明", "This sentence does not contain the target word.", "", "demo"
        );
        Favorite unmatchedFav = Favorite.fromDictionary("https://example.test", 7L, unmatched, 1000L);
        assertNull(ClozeQuestion.from(null, unmatchedFav));
    }

    @Test
    public void nonDictionaryFavoriteStillRequiresArticle() {
        Favorite readerFav = new Favorite(
                "https://example.test", 7L, "article-1",
                13, 20, "decline",
                "They studied decline.",
                "下降", "解释", "demo", 1000L
        );

        assertNull(ClozeQuestion.from(null, readerFav));
    }

    @Test
    public void clozeRoundCreateIncludesValidDictionaryFavoritesWithoutCallingArticleLookup() {
        DictionaryEntry validEntry = new DictionaryEntry(
                "abandon", "abandon", "/əˈbændən/", "v.", "CET-4",
                "放弃", "说明", "They had to abandon the car in the snow.", "", "demo"
        );
        DictionaryEntry emptyEntry = new DictionaryEntry(
                "empty", "empty", "", "adj.", "CET-4",
                "空的", "说明", "", "", "demo"
        );
        String body = "They studied decline.";
        Favorite dictValid = Favorite.fromDictionary("https://example.test", 7L, validEntry, 3L);
        Favorite dictEmpty = Favorite.fromDictionary("https://example.test", 7L, emptyEntry, 2L);
        Favorite readerMissing = new Favorite("https://example.test", 7L, "missing-1",
                13, 20, "decline", body, "下降", "解释", "demo", 1L);
        Favorite readerValid = new Favorite("https://example.test", 7L, "art-1",
                13, 20, "decline", body, "下降", "解释", "demo", 4L);

        Map<String, ReadingArticle> articles = new HashMap<>();
        articles.put("art-1", new ReadingArticle("art-1", "Article 1", body));

        AtomicInteger dictLookupCalls = new AtomicInteger();
        List<Favorite> all = new ArrayList<>(List.of(dictValid, dictEmpty, readerMissing, readerValid));
        ClozeRound.Creation creation = ClozeRound.create(all, id -> {
            if (Favorite.DICT_ARTICLE_ID.equals(id)) {
                dictLookupCalls.incrementAndGet();
            }
            return articles.get(id);
        }, 0L, 10);

        assertEquals(0, dictLookupCalls.get());
        assertNotNull(creation.round());
        assertEquals(2, creation.round().total());
        assertEquals(2, creation.skippedCount());
    }
}
