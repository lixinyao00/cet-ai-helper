package com.cethelper.android;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
interface PracticeRecordDao {
    @Insert
    long insert(PracticeRecord record);

    @Query("SELECT * FROM practice_records WHERE student_id = :studentId AND type = :type "
            + "ORDER BY created_at DESC LIMIT :limit")
    List<PracticeRecord> listByType(String studentId, String type, int limit);

    @Query("SELECT * FROM practice_records WHERE student_id = :studentId "
            + "ORDER BY created_at DESC LIMIT :limit")
    List<PracticeRecord> listRecent(String studentId, int limit);

    @Query("DELETE FROM practice_records WHERE id = :id")
    void delete(long id);
}
