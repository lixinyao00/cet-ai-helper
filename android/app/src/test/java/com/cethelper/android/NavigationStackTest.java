package com.cethelper.android;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class NavigationStackTest {
    @Test
    public void initialStackHasHomeRootAndCannotPopFurther() {
        NavigationStack nav = new NavigationStack();

        assertEquals(1, nav.size());
        assertEquals(NavEntry.Screen.HOME, nav.top().screen);
        assertTrue(nav.isL1Top());
        assertNull(nav.pop());
        assertEquals(1, nav.size());
    }

    @Test
    public void resetToNonHomeTabPadsHomeUnderneath() {
        NavigationStack nav = new NavigationStack();
        nav.resetTo(NavEntry.tab(NavEntry.Screen.VOCABULARY));

        assertEquals(2, nav.size());
        assertEquals(NavEntry.Screen.VOCABULARY, nav.top().screen);
        assertTrue(nav.isL1Top());

        NavEntry poppedTo = nav.pop();
        assertNotNull(poppedTo);
        assertEquals(NavEntry.Screen.HOME, poppedTo.screen);
        assertTrue(nav.isL1Top());
        assertNull(nav.pop());
    }

    @Test
    public void resetToHomeClearsDeepStackToSingleHome() {
        NavigationStack nav = new NavigationStack();
        nav.resetTo(NavEntry.tab(NavEntry.Screen.VOCABULARY));
        nav.push(NavEntry.of(NavEntry.Screen.CLOZE));
        nav.push(NavEntry.reader("demo", null));
        assertEquals(4, nav.size());
        assertFalse(nav.isL1Top());

        nav.resetTo(NavEntry.home());
        assertEquals(1, nav.size());
        assertEquals(NavEntry.Screen.HOME, nav.top().screen);
        assertTrue(nav.isL1Top());
    }

    @Test
    public void vocabularyToReaderAndBackPreservesVocabularyTab() {
        NavigationStack nav = new NavigationStack();
        nav.resetTo(NavEntry.tab(NavEntry.Screen.VOCABULARY));
        nav.push(NavEntry.reader("article-42", null));

        assertEquals(3, nav.size());
        assertFalse(nav.isL1Top());
        assertEquals(NavEntry.Screen.READER, nav.top().screen);
        assertEquals("article-42", nav.top().articleId);

        NavEntry back = nav.pop();
        assertNotNull(back);
        assertEquals(NavEntry.Screen.VOCABULARY, back.screen);
        assertTrue(nav.isL1Top());
    }

    @Test
    public void clozeToReaderBackToClozeBackToVocabulary() {
        NavigationStack nav = new NavigationStack();
        nav.resetTo(NavEntry.tab(NavEntry.Screen.VOCABULARY));
        nav.push(NavEntry.of(NavEntry.Screen.CLOZE));
        nav.push(NavEntry.reader("demo", null));

        assertEquals(4, nav.size());
        assertFalse(nav.isL1Top());

        NavEntry backToCloze = nav.pop();
        assertNotNull(backToCloze);
        assertEquals(NavEntry.Screen.CLOZE, backToCloze.screen);
        assertFalse(nav.isL1Top());
        assertTrue(nav.contains(NavEntry.Screen.CLOZE));

        NavEntry backToVocab = nav.pop();
        assertNotNull(backToVocab);
        assertEquals(NavEntry.Screen.VOCABULARY, backToVocab.screen);
        assertTrue(nav.isL1Top());
        assertFalse(nav.contains(NavEntry.Screen.CLOZE));
    }

    @Test
    public void pushingTutorOverTranslationOrWritingKeepsParentInStack() {
        NavigationStack nav = new NavigationStack();
        nav.push(NavEntry.of(NavEntry.Screen.TRANSLATION));
        nav.push(NavEntry.tutor(null, false));

        assertEquals(3, nav.size());
        assertFalse(nav.isL1Top());
        assertTrue(nav.contains(NavEntry.Screen.TRANSLATION));

        NavEntry backToTranslation = nav.pop();
        assertNotNull(backToTranslation);
        assertEquals(NavEntry.Screen.TRANSLATION, backToTranslation.screen);
        assertTrue(nav.contains(NavEntry.Screen.TRANSLATION));

        NavEntry backToHome = nav.pop();
        assertNotNull(backToHome);
        assertEquals(NavEntry.Screen.HOME, backToHome.screen);
        assertFalse(nav.contains(NavEntry.Screen.TRANSLATION));
    }

    @Test
    public void nonTabScreensAreNeverRoot() {
        NavEntry reader = new NavEntry(NavEntry.Screen.READER, "id", null, true);
        NavEntry cloze = new NavEntry(NavEntry.Screen.CLOZE, null, null, true);
        NavEntry l2Dict = NavEntry.dictionary(null, false);
        NavEntry l1Dict = NavEntry.tab(NavEntry.Screen.DICTIONARY);

        assertFalse(reader.isRoot());
        assertFalse(cloze.isRoot());
        assertFalse(l2Dict.isRoot());
        assertTrue(l1Dict.isRoot());
    }
}
