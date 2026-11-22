package com.cethelper.android;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
interface DictionaryHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(DictionaryHistory h);

    @Query("SELECT * FROM dictionary_history WHERE server_url = :serverUrl AND student_id = :studentId "
            + "ORDER BY looked_up_at DESC LIMIT :limit")
    List<DictionaryHistory> recent(String serverUrl, long studentId, int limit);

    @Query("DELETE FROM dictionary_history WHERE server_url = :serverUrl AND student_id = :studentId "
            + "AND word = :word")
    void delete(String serverUrl, long studentId, String word);

    @Query("DELETE FROM dictionary_history WHERE server_url = :serverUrl AND student_id = :studentId")
    void clear(String serverUrl, long studentId);
}
