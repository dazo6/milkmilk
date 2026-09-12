package com.dazo66.milkmilk

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import java.util.Date

@Dao
interface DeletedUsageSessionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(session: DeletedUsageSession)

    // 同一应用的会话在正常情况下不会重叠；重叠即表示同一段被用户删除的行为。
    @Query("""
        SELECT COUNT(*) FROM deleted_usage_sessions
        WHERE packageName = :packageName
          AND startTime < :endTime
          AND endTime > :startTime
    """)
    suspend fun countOverlappingTombstones(
        packageName: String,
        startTime: Date,
        endTime: Date
    ): Int

    @Query("DELETE FROM deleted_usage_sessions WHERE deletedAt < :cutoff")
    suspend fun deleteBefore(cutoff: Date)
}
