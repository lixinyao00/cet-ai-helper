package com.cethelper.android;

import android.os.Bundle;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class NavigationStack {
    private final List<NavEntry> stack = new ArrayList<>();

    NavigationStack() {
        stack.add(NavEntry.home());
    }

    void resetTo(NavEntry root) {
        if (root == null) {
            throw new IllegalArgumentException("root must not be null");
        }
        stack.clear();
        if (root.screen != NavEntry.Screen.HOME) {
            stack.add(NavEntry.home());
        }
        NavEntry normalized = NavEntry.isL1TabScreen(root.screen) && !root.isRoot()
                ? new NavEntry(root.screen, root.articleId, root.args, true)
                : root;
        stack.add(normalized);
    }

    void push(NavEntry e) {
        if (e == null) {
            throw new IllegalArgumentException("entry must not be null");
        }
        stack.add(e);
    }

    @Nullable
    NavEntry pop() {
        if (stack.size() <= 1) {
            return null;
        }
        stack.remove(stack.size() - 1);
        return stack.get(stack.size() - 1);
    }

    NavEntry top() {
        return stack.isEmpty() ? null : stack.get(stack.size() - 1);
    }

    int size() {
        return stack.size();
    }

    boolean isL1Top() {
        NavEntry current = top();
        return current != null && current.isRoot();
    }

    boolean contains(NavEntry.Screen screen) {
        for (NavEntry entry : stack) {
            if (entry.screen == screen) {
                return true;
            }
        }
        return false;
    }

    List<NavEntry> entries() {
        return Collections.unmodifiableList(new ArrayList<>(stack));
    }

    ArrayList<Bundle> toBundles() {
        ArrayList<Bundle> list = new ArrayList<>(stack.size());
        for (NavEntry entry : stack) {
            list.add(entry.toBundle());
        }
        return list;
    }

    static NavigationStack fromBundles(@Nullable ArrayList<Bundle> bundles) {
        NavigationStack nav = new NavigationStack();
        if (bundles == null || bundles.isEmpty()) {
            return nav;
        }
        nav.stack.clear();
        for (Bundle b : bundles) {
            nav.stack.add(NavEntry.fromBundle(b));
        }
        if (nav.stack.isEmpty()) {
            nav.stack.add(NavEntry.home());
        }
        return nav;
    }
}
