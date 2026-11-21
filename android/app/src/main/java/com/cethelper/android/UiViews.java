package com.cethelper.android;

import android.view.View;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class UiViews {
    private static final Pattern ENGLISH_WORD = Pattern.compile("[A-Za-z]+(?:['’\\-][A-Za-z]+)*");

    private UiViews() {}

    @SuppressWarnings("unchecked")
    static <T extends View> T findOptionalView(View root, String idName) {
        if (root == null || idName == null || idName.isEmpty()) {
            return null;
        }
        int id = root.getResources().getIdentifier(idName, "id", root.getContext().getPackageName());
        if (id == 0) {
            return null;
        }
        View found = root.findViewById(id);
        return found == null ? null : (T) found;
    }

    static <T extends View> T findOptionalView(View root, String idName, Class<T> type) {
        View found = findOptionalView(root, idName);
        return type.isInstance(found) ? type.cast(found) : null;
    }

    static int countEnglishWords(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        Matcher matcher = ENGLISH_WORD.matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    static String formatWritingWordCount(int words) {
        if (words == 0) {
            return "0 词 · 四六级建议 120-180 词";
        }
        String hint;
        if (words < 120) {
            hint = "偏少";
        } else if (words <= 180) {
            hint = "达标";
        } else {
            hint = "超出建议";
        }
        return words + " / 120-180 词（" + hint + "）";
    }

    static String formatTranslationWordCount(int words) {
        return "当前 " + words + " 词";
    }
}
