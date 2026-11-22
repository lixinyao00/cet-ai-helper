package com.cethelper.android;

import android.speech.tts.TextToSpeech;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class DictionaryScreen {
    private final MainActivity host;
    private final DictionaryState state;
    private final boolean asTab;

    private View page;
    private EditText searchInput;
    private View searchClear;
    private TextView levelAll;
    private TextView levelCet4;
    private TextView levelCet6;
    private LinearLayout suggestionsContainer;
    private View historyTitle;
    private View historyClear;
    private LinearLayout historyChips;
    private TextView statusView;
    private View entryCard;
    private TextView wordView;
    private TextView phoneticView;
    private View speakButton;
    private TextView posView;
    private TextView levelView;
    private TextView meaningView;
    private TextView explanationView;
    private TextView exampleView;
    private TextView exampleTranslationView;
    private Button favoriteButton;
    private View askTutorButton;

    private String selectedLevel;
    private boolean suppressWatcher;
    private TextToSpeech tts;
    private boolean ttsReady;
    private String pendingSpeech;

    DictionaryScreen(MainActivity host, DictionaryState state, boolean asTab) {
        this.host = host;
        this.state = state;
        this.asTab = asTab;
    }

    void show() {
        page = asTab
                ? host.showL1Screen(R.layout.screen_dictionary, NavEntry.Screen.DICTIONARY)
                : host.showL2Screen(R.layout.screen_dictionary, host.getString(R.string.dict_title));

        View topBack = page.findViewById(R.id.top_bar_back);
        TextView topTitle = page.findViewById(R.id.top_bar_title);
        if (asTab) {
            UiViews.setVisible(topBack, false);
            if (topTitle != null) {
                topTitle.setText(R.string.dict_title);
            }
        }

        searchInput = page.findViewById(R.id.dict_search_input);
        searchClear = page.findViewById(R.id.dict_search_clear);
        levelAll = page.findViewById(R.id.dict_level_all);
        levelCet4 = page.findViewById(R.id.dict_level_cet4);
        levelCet6 = page.findViewById(R.id.dict_level_cet6);
        suggestionsContainer = page.findViewById(R.id.dict_suggestions);
        historyTitle = page.findViewById(R.id.dict_history_title);
        historyClear = page.findViewById(R.id.dict_history_clear);
        historyChips = page.findViewById(R.id.dict_history_chips);
        statusView = page.findViewById(R.id.dict_status);
        entryCard = page.findViewById(R.id.dict_entry_card);
        wordView = page.findViewById(R.id.dict_word);
        phoneticView = page.findViewById(R.id.dict_phonetic);
        speakButton = page.findViewById(R.id.dict_speak);
        posView = page.findViewById(R.id.dict_pos);
        levelView = page.findViewById(R.id.dict_level);
        meaningView = page.findViewById(R.id.dict_meaning);
        explanationView = page.findViewById(R.id.dict_explanation);
        exampleView = page.findViewById(R.id.dict_example);
        exampleTranslationView = page.findViewById(R.id.dict_example_translation);
        favoriteButton = page.findViewById(R.id.dict_favorite);
        askTutorButton = page.findViewById(R.id.dict_ask_tutor);

        bindSearch();
        bindLevelFilter();
        historyClear.setOnClickListener(v -> state.clearHistory(host));
        speakButton.setOnClickListener(v -> {
            if (state.current != null) {
                String target = !UiViews.safeText(state.current.word).isEmpty()
                        ? state.current.word : state.current.lemma;
                speak(target);
            }
        });
        favoriteButton.setOnClickListener(v -> {
            if (state.current != null) {
                state.toggleFavorite(host, state.current);
            }
        });
        askTutorButton.setOnClickListener(v -> {
            if (state.current != null) {
                openTutorForEntry(state.current);
            }
        });

        state.setListener(() -> {
            if (host.isCurrent(page)) {
                render();
            }
        });
        state.loadHistory(host);
        render();
    }

    void release() {
        state.setListener(null);
        pendingSpeech = null;
        ttsReady = false;
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
    }

    private void bindSearch() {
        String initial = UiViews.safeText(state.query);
        suppressWatcher = true;
        searchInput.setText(initial);
        searchInput.setSelection(initial.length());
        suppressWatcher = false;
        UiViews.setVisible(searchClear, !initial.isEmpty());

        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (suppressWatcher || !host.isCurrent(page)) {
                    return;
                }
                String q = s.toString();
                UiViews.setVisible(searchClear, !q.trim().isEmpty());
                state.suggest(host, q);
            }

            @Override public void afterTextChanged(Editable s) {}
        });

        searchInput.setOnEditorActionListener((v, actionId, event) -> {
            boolean isSearch = actionId == EditorInfo.IME_ACTION_SEARCH
                    || actionId == EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN);
            if (!isSearch) {
                return false;
            }
            submitLookup(searchInput.getText().toString());
            return true;
        });

        searchClear.setOnClickListener(v -> {
            suppressWatcher = true;
            searchInput.setText("");
            suppressWatcher = false;
            UiViews.setVisible(searchClear, false);
            state.query = "";
            state.suggest(host, "");
            renderSuggestions();
            searchInput.requestFocus();
        });
    }

    private void bindLevelFilter() {
        updateLevelChips();
        levelAll.setOnClickListener(v -> selectLevel(null));
        levelCet4.setOnClickListener(v -> selectLevel("CET-4"));
        levelCet6.setOnClickListener(v -> selectLevel("CET-6"));
    }

    private void selectLevel(String level) {
        selectedLevel = level;
        updateLevelChips();
        renderSuggestions();
    }

    private void updateLevelChips() {
        levelAll.setSelected(selectedLevel == null);
        levelCet4.setSelected("CET-4".equals(selectedLevel));
        levelCet6.setSelected("CET-6".equals(selectedLevel));
    }

    private void submitLookup(String raw) {
        String word = raw == null ? "" : raw.trim();
        if (word.isEmpty()) {
            return;
        }
        hideKeyboard();
        suppressWatcher = true;
        searchInput.setText(word);
        searchInput.setSelection(word.length());
        suppressWatcher = false;
        UiViews.setVisible(searchClear, true);
        state.lookup(host, word);
    }

    private void render() {
        String queryText = UiViews.safeText(state.query);
        if (!searchInput.hasFocus() && !queryText.equals(searchInput.getText().toString())) {
            suppressWatcher = true;
            searchInput.setText(queryText);
            searchInput.setSelection(queryText.length());
            suppressWatcher = false;
            UiViews.setVisible(searchClear, !queryText.isEmpty());
        }
        renderSuggestions();
        renderHistory();
        renderStatus();
        renderEntry();
    }

    private void renderSuggestions() {
        suggestionsContainer.removeAllViews();
        String inputTrimmed = searchInput.getText().toString().trim();
        List<DictionarySuggestion> raw = state.suggestions;
        if (raw == null || raw.isEmpty() || inputTrimmed.isEmpty()) {
            suggestionsContainer.setVisibility(View.GONE);
            return;
        }
        if (state.current != null && inputTrimmed.equalsIgnoreCase(UiViews.safeText(state.current.word))
                && !state.loading) {
            suggestionsContainer.setVisibility(View.GONE);
            return;
        }
        List<DictionarySuggestion> filtered = new ArrayList<>();
        for (DictionarySuggestion item : raw) {
            if (selectedLevel == null || selectedLevel.equalsIgnoreCase(UiViews.safeText(item.cetLevel))) {
                filtered.add(item);
            }
        }
        if (filtered.isEmpty()) {
            suggestionsContainer.setVisibility(View.GONE);
            return;
        }
        LayoutInflater inflater = LayoutInflater.from(host);
        for (DictionarySuggestion item : filtered) {
            View row = inflater.inflate(R.layout.row_dictionary_suggestion, suggestionsContainer, false);
            row.<TextView>findViewById(R.id.suggest_word).setText(UiViews.safeText(item.word));
            TextView phonetic = row.findViewById(R.id.suggest_phonetic);
            String posPhonetic = joinNonEmpty(item.phonetic, item.partOfSpeech);
            phonetic.setText(posPhonetic);
            UiViews.setVisible(phonetic, !posPhonetic.isEmpty());
            TextView level = row.findViewById(R.id.suggest_level);
            String lvl = UiViews.safeText(item.cetLevel);
            level.setText(lvl);
            UiViews.setVisible(level, !lvl.isEmpty());
            row.<TextView>findViewById(R.id.suggest_meaning).setText(UiViews.safeText(item.meaning));
            row.setOnClickListener(v -> submitLookup(item.word));
            suggestionsContainer.addView(row);
        }
        suggestionsContainer.setVisibility(View.VISIBLE);
    }

    private void renderHistory() {
        historyChips.removeAllViews();
        List<DictionaryHistory> history = state.history;
        boolean hasHistory = history != null && !history.isEmpty();
        UiViews.setVisible(historyTitle, hasHistory);
        UiViews.setVisible(historyClear, hasHistory);
        if (!hasHistory) {
            return;
        }
        int marginEnd = dp(8);
        for (DictionaryHistory item : history) {
            TextView chip = new TextView(host, null, 0, R.style.StudyChip);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(marginEnd);
            chip.setLayoutParams(lp);
            chip.setText(UiViews.safeText(item.word));
            chip.setOnClickListener(v -> submitLookup(item.word));
            historyChips.addView(chip);
        }
    }

    private void renderStatus() {
        if (state.loading) {
            statusView.setText(R.string.dict_loading);
            statusView.setVisibility(View.VISIBLE);
        } else if (state.error != null && !state.error.isEmpty()) {
            statusView.setText(state.error);
            statusView.setVisibility(View.VISIBLE);
        } else if (state.current == null && (state.history == null || state.history.isEmpty())) {
            statusView.setText(R.string.dict_empty_hint);
            statusView.setVisibility(View.VISIBLE);
        } else {
            statusView.setVisibility(View.GONE);
        }
    }

    private void renderEntry() {
        DictionaryEntry entry = state.current;
        if (entry == null) {
            entryCard.setVisibility(View.GONE);
            return;
        }
        entryCard.setVisibility(View.VISIBLE);
        String word = UiViews.safeText(entry.word);
        String lemma = UiViews.safeText(entry.lemma);
        if (!lemma.isEmpty() && !lemma.equalsIgnoreCase(word)) {
            wordView.setText(word + " (" + lemma + ")");
        } else {
            wordView.setText(!word.isEmpty() ? word : lemma);
        }

        String phonetic = UiViews.safeText(entry.phonetic);
        phoneticView.setText(phonetic);
        UiViews.setVisible(phoneticView, !phonetic.isEmpty());

        String pos = UiViews.safeText(entry.partOfSpeech);
        posView.setText(pos);
        UiViews.setVisible(posView, !pos.isEmpty());

        String level = UiViews.safeText(entry.cetLevel);
        levelView.setText(level);
        UiViews.setVisible(levelView, !level.isEmpty());

        meaningView.setText(UiViews.safeText(entry.meaning));
        String explanation = UiViews.safeText(entry.explanation);
        explanationView.setText(explanation);
        UiViews.setVisible(explanationView, !explanation.isEmpty());

        exampleView.setText(UiViews.safeText(entry.exampleSentence));
        String exampleTrans = UiViews.safeText(entry.exampleTranslation);
        exampleTranslationView.setText(exampleTrans);
        UiViews.setVisible(exampleTranslationView, !exampleTrans.isEmpty());

        boolean saved = state.isFavorite(entry);
        favoriteButton.setText(saved ? R.string.dict_favorite_added : R.string.dict_favorite_add);
        favoriteButton.setSelected(saved);
    }

    private void openTutorForEntry(DictionaryEntry entry) {
        String lemma = !UiViews.safeText(entry.lemma).isEmpty() ? entry.lemma : UiViews.safeText(entry.word);
        String focus = !UiViews.safeText(entry.word).isEmpty() ? entry.word : lemma;
        String example = UiViews.safeText(entry.exampleSentence);
        StringBuilder note = new StringBuilder();
        note.append("释义：").append(UiViews.safeText(entry.meaning));
        if (!UiViews.safeText(entry.explanation).isEmpty()) {
            note.append("\n").append(entry.explanation);
        }
        host.openTutorWithContext(new TutorContext(
                TutorContext.WORD,
                UiViews.truncate(lemma, 200),
                UiViews.truncate(focus, 2000),
                UiViews.truncate(example, 6000),
                UiViews.truncate(note.toString(), 4000)));
    }

    private void speak(String text) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        if (tts == null) {
            pendingSpeech = text;
            tts = new TextToSpeech(host.getApplicationContext(), status -> {
                if (status == TextToSpeech.SUCCESS && tts != null) {
                    tts.setLanguage(Locale.US);
                    ttsReady = true;
                    if (pendingSpeech != null) {
                        String toSpeak = pendingSpeech;
                        pendingSpeech = null;
                        tts.speak(toSpeak, TextToSpeech.QUEUE_FLUSH, null, "dict-speak");
                    }
                }
            });
        } else if (ttsReady) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "dict-speak");
        } else {
            pendingSpeech = text;
        }
    }

    private void hideKeyboard() {
        InputMethodManager imm = host.getSystemService(InputMethodManager.class);
        if (imm != null && searchInput != null) {
            imm.hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
        }
    }

    private static String joinNonEmpty(String first, String second) {
        String a = UiViews.safeText(first).trim();
        String b = UiViews.safeText(second).trim();
        if (a.isEmpty()) {
            return b;
        }
        if (b.isEmpty()) {
            return a;
        }
        return a + "  " + b;
    }

    private int dp(int value) {
        return Math.round(value * host.getResources().getDisplayMetrics().density);
    }
}
