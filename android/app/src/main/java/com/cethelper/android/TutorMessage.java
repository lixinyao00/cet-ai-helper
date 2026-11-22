package com.cethelper.android;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "tutor_messages",
        indices = @Index(
                value = {"server_url", "student_id", "session_key", "created_at"},
                name = "index_tutor_messages_server_url_student_id_session_key_created_at"
        )
)
public final class TutorMessage {
    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";

    @PrimaryKey(autoGenerate = true)
    public final long id;

    @NonNull
    @ColumnInfo(name = "server_url")
    public final String serverUrl;

    @ColumnInfo(name = "student_id")
    public final long studentId;

    @NonNull
    @ColumnInfo(name = "session_key")
    public final String sessionKey;

    @NonNull
    @ColumnInfo(name = "role")
    public final String role;

    @NonNull
    @ColumnInfo(name = "content")
    public final String content;

    @NonNull
    @ColumnInfo(name = "context_type", defaultValue = "")
    public final String contextType;

    @NonNull
    @ColumnInfo(name = "context_summary", defaultValue = "")
    public final String contextSummary;

    @NonNull
    @ColumnInfo(name = "mode", defaultValue = "")
    public final String mode;

    @ColumnInfo(name = "created_at")
    public final long createdAt;

    public TutorMessage(long id, @NonNull String serverUrl, long studentId,
                        @NonNull String sessionKey, @NonNull String role, @NonNull String content,
                        @NonNull String contextType, @NonNull String contextSummary,
                        @NonNull String mode, long createdAt) {
        this.id = id;
        this.serverUrl = serverUrl;
        this.studentId = studentId;
        this.sessionKey = sessionKey;
        this.role = role;
        this.content = content;
        this.contextType = contextType;
        this.contextSummary = contextSummary;
        this.mode = mode;
        this.createdAt = createdAt;
    }

    @Ignore
    public TutorMessage(@NonNull String serverUrl, long studentId,
                        @NonNull String sessionKey, @NonNull String role, @NonNull String content,
                        @NonNull String contextType, @NonNull String contextSummary,
                        @NonNull String mode, long createdAt) {
        this(0L, serverUrl, studentId, sessionKey, role, content, contextType, contextSummary, mode, createdAt);
    }
}
