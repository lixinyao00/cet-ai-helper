package com.cethelper.android;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "practice_records",
        indices = {@Index(value = {"student_id", "created_at"}, name = "index_practice_records_student_id_created_at")}
)
public final class PracticeRecord {
    public static final String TYPE_WRITING = "WRITING";
    public static final String TYPE_TRANSLATION = "TRANSLATION";

    @PrimaryKey(autoGenerate = true)
    public final long id;

    @NonNull
    @ColumnInfo(name = "student_id")
    public final String studentId;

    @NonNull
    @ColumnInfo(name = "type")
    public final String type;

    @NonNull
    @ColumnInfo(name = "prompt_or_source")
    public final String promptOrSource;

    @NonNull
    @ColumnInfo(name = "draft")
    public final String draft;

    @NonNull
    @ColumnInfo(name = "review_json")
    public final String reviewJson;

    @ColumnInfo(name = "estimated_score", defaultValue = "-1")
    public final int estimatedScore;

    @ColumnInfo(name = "created_at")
    public final long createdAt;

    public PracticeRecord(long id, @NonNull String studentId, @NonNull String type,
                          @NonNull String promptOrSource, @NonNull String draft,
                          @NonNull String reviewJson, int estimatedScore, long createdAt) {
        this.id = id;
        this.studentId = studentId;
        this.type = type;
        this.promptOrSource = promptOrSource;
        this.draft = draft;
        this.reviewJson = reviewJson;
        this.estimatedScore = estimatedScore;
        this.createdAt = createdAt;
    }

    @Ignore
    public PracticeRecord(@NonNull String studentId, @NonNull String type,
                          @NonNull String promptOrSource, @NonNull String draft,
                          @NonNull String reviewJson, int estimatedScore, long createdAt) {
        this(0L, studentId, type, promptOrSource, draft, reviewJson, estimatedScore, createdAt);
    }

    static String ownerKey(ArticleOwner owner) {
        return owner.serverUrl() + "|" + owner.studentId();
    }
}
