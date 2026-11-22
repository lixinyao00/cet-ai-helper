package com.cethelper.android;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;

@Entity(
        tableName = "dictionary_history",
        primaryKeys = {"server_url", "student_id", "word"},
        indices = @Index(
                value = {"server_url", "student_id", "looked_up_at"},
                name = "index_dictionary_history_server_url_student_id_looked_up_at"
        )
)
public final class DictionaryHistory {
    @NonNull
    @ColumnInfo(name = "server_url")
    public final String serverUrl;

    @ColumnInfo(name = "student_id")
    public final long studentId;

    @NonNull
    @ColumnInfo(name = "word")
    public final String word;

    @NonNull
    @ColumnInfo(name = "phonetic", defaultValue = "")
    public final String phonetic;

    @NonNull
    @ColumnInfo(name = "part_of_speech", defaultValue = "")
    public final String partOfSpeech;

    @NonNull
    @ColumnInfo(name = "lemma", defaultValue = "")
    public final String lemma;

    @NonNull
    @ColumnInfo(name = "cet_level", defaultValue = "")
    public final String cetLevel;

    @NonNull
    @ColumnInfo(name = "meaning")
    public final String meaning;

    @NonNull
    @ColumnInfo(name = "explanation")
    public final String explanation;

    @NonNull
    @ColumnInfo(name = "example_sentence", defaultValue = "")
    public final String exampleSentence;

    @NonNull
    @ColumnInfo(name = "mode", defaultValue = "demo")
    public final String mode;

    @ColumnInfo(name = "looked_up_at")
    public final long lookedUpAt;

    public DictionaryHistory(@NonNull String serverUrl, long studentId, @NonNull String word,
                             @NonNull String phonetic, @NonNull String partOfSpeech,
                             @NonNull String lemma, @NonNull String cetLevel,
                             @NonNull String meaning, @NonNull String explanation,
                             @NonNull String exampleSentence, @NonNull String mode,
                             long lookedUpAt) {
        this.serverUrl = serverUrl;
        this.studentId = studentId;
        this.word = word;
        this.phonetic = phonetic;
        this.partOfSpeech = partOfSpeech;
        this.lemma = lemma;
        this.cetLevel = cetLevel;
        this.meaning = meaning;
        this.explanation = explanation;
        this.exampleSentence = exampleSentence;
        this.mode = mode;
        this.lookedUpAt = lookedUpAt;
    }
}
