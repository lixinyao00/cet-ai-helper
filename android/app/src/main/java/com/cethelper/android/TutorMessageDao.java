package com.cethelper.android;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
interface TutorMessageDao {
    @Insert
    long insert(TutorMessage m);

    @Query("SELECT * FROM tutor_messages WHERE server_url = :serverUrl AND student_id = :studentId "
            + "AND session_key = :sessionKey ORDER BY created_at ASC, id ASC")
    List<TutorMessage> session(String serverUrl, long studentId, String sessionKey);

    @Query("DELETE FROM tutor_messages WHERE server_url = :serverUrl AND student_id = :studentId "
            + "AND session_key = :sessionKey")
    void clearSession(String serverUrl, long studentId, String sessionKey);
}
