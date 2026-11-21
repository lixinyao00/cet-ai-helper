package com.cethelper.android;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

record SentenceAnalysis(String sentence, String translation, String structure, String mode,
                        List<Fragment> fragments) {
    record Chunk(String text, String role, String explanation) {}
    record Fragment(Chunk chunk, int start, int end) {}

    static List<Fragment> locate(String sentence, List<Chunk> chunks) {
        List<Fragment> fragments = new ArrayList<>(chunks.size());
        int lastEnd = 0;
        for (Chunk chunk : chunks) {
            String text = chunk.text();
            int start = sentence.indexOf(text, lastEnd);
            if (text.isEmpty() || start < 0) {
                throw new IllegalArgumentException("Fragment is missing or out of order");
            }
            lastEnd = start + text.length();
            fragments.add(new Fragment(chunk, start, lastEnd));
        }
        return Collections.unmodifiableList(fragments);
    }
}
