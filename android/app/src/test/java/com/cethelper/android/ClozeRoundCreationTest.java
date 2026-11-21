package com.cethelper.android;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link ClozeRound#create} 的容错与抽样测试。
 *
 * <p>收藏数据可能引用已删除的文章、已被编辑过的正文或过期的偏移量，
 * 这些「脏生词」必须被安静跳过，剩下可用的题目照常成局；
 * 单局最多 {@link ClozeRound#MAX_ROUND_SIZE} 题。</p>
 */
public final class ClozeRoundCreationTest {

    private static final String BODY = "They studied decline.";
    private static final int DECLINE_AT = BODY.indexOf("decline");

    @Test
    public void dirtyFavoritesAreSkippedWithoutThrowing() {
        List<Favorite> favorites = new ArrayList<>();
        // 1) 文章已不存在
        favorites.add(favorite("missing-article", BODY, DECLINE_AT));
        // 2) 偏移量对不上：收藏后正文被编辑过
        favorites.add(favorite("edited-article", BODY, 0));
        // 3) 句子已不在文章中
        favorites.add(favorite("stale-sentence", "A sentence that is no longer in the body.", 0));
        // 4) 正常条目
        favorites.add(favorite("article-d", BODY, DECLINE_AT));

        Map<String, ReadingArticle> articles = new HashMap<>();
        articles.put("edited-article", new ReadingArticle("edited-article", "Article B", BODY));
        articles.put("stale-sentence", new ReadingArticle("stale-sentence", "Article C", BODY));
        articles.put("article-d", new ReadingArticle("article-d", "Article D", BODY));

        ClozeRound.Creation creation = ClozeRound.create(favorites, articles::get, 0L, 10);

        assertNotNull("至少有一条可用收藏时应正常成局", creation.round());
        assertEquals("三条脏数据都应被跳过", 3, creation.skippedCount());
        assertEquals(1, creation.round().total());
        assertEquals("They studied ____.", creation.round().current().prompt());
    }

    @Test
    public void allDirtyFavoritesYieldNoRoundInsteadOfCrash() {
        List<Favorite> favorites = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            // 引用的文章都查不到。
            favorites.add(favorite("missing-" + i, BODY, DECLINE_AT));
        }

        ClozeRound.Creation creation = ClozeRound.create(favorites, id -> null, 0L, 10);

        assertNull("没有可用收藏时不应创建空局", creation.round());
        assertEquals(5, creation.skippedCount());
    }

    @Test
    public void nullFavoriteAndEmptyInputAreHandled() {
        List<Favorite> favorites = new ArrayList<>();
        favorites.add(null);

        ClozeRound.Creation onlyNull = ClozeRound.create(favorites, id -> null, 0L, 10);
        assertNull(onlyNull.round());
        assertEquals(1, onlyNull.skippedCount());

        ClozeRound.Creation empty = ClozeRound.create(List.of(), id -> null, 0L, 10);
        assertNull(empty.round());
        assertEquals(0, empty.skippedCount());

        assertNull(ClozeRound.create(null, id -> null, 0L, 10).round());
    }

    @Test
    public void moreThanTenQuestionsAreSampledToOneFullRound() {
        Map<String, ReadingArticle> articles = new HashMap<>();
        List<Favorite> favorites = new ArrayList<>();
        for (int i = 0; i < 14; i++) {
            String id = String.format("article-%02d", i);
            articles.put(id, new ReadingArticle(id, "Article " + i, BODY));
            favorites.add(favorite(id, BODY, DECLINE_AT));
        }

        ClozeRound.Creation creation = ClozeRound.create(favorites, articles::get, 0L, ClozeRound.MAX_ROUND_SIZE);

        assertNotNull(creation.round());
        assertEquals("单局最多 10 题", 10, ClozeRound.MAX_ROUND_SIZE);
        assertEquals(10, creation.round().total());
        assertEquals("超出上限的收藏不算脏数据，不应计入 skipped", 0, creation.skippedCount());
    }

    @Test
    public void customLimitIsHonored() {
        Map<String, ReadingArticle> articles = new HashMap<>();
        List<Favorite> favorites = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String id = "custom-" + i;
            articles.put(id, new ReadingArticle(id, "Article " + i, BODY));
            favorites.add(favorite(id, BODY, DECLINE_AT));
        }

        ClozeRound.Creation creation = ClozeRound.create(favorites, articles::get, 0L, 3);

        assertNotNull(creation.round());
        assertEquals(3, creation.round().total());
    }

    @Test
    public void sampledRoundIsPlayable() {
        Map<String, ReadingArticle> articles = new HashMap<>();
        List<Favorite> favorites = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            String id = "play-" + i;
            articles.put(id, new ReadingArticle(id, "Article " + i, BODY));
            favorites.add(favorite(id, BODY, DECLINE_AT));
        }

        ClozeRound round = ClozeRound.create(favorites, articles::get, 0L, ClozeRound.MAX_ROUND_SIZE).round();
        assertNotNull(round);

        assertTrue(round.total() <= ClozeRound.MAX_ROUND_SIZE);
        round.submit("DECLINE");
        assertEquals(ClozeRound.Result.CORRECT, round.result());
        assertEquals(1, round.correct());
    }

    /** 构造一条收藏：selection 固定为 decline，偏移量与正文对齐。 */
    private static Favorite favorite(String articleId, String body, int start) {
        return new Favorite("https://example.test", 42, articleId, start, start + "decline".length(),
                "decline", body, "测试释义", "测试解释", "demo", 0);
    }
}
