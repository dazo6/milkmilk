package com.dazo66.milkmilk

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import java.util.Date

@Dao
interface AggregatedBehaviorDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBehavior(behavior: AggregatedBehavior): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBehaviors(behaviors: List<AggregatedBehavior>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSources(sources: List<AggregatedBehaviorSource>)

    @Query("DELETE FROM aggregated_behaviors")
    suspend fun deleteAllAggregatedBehaviors()

    @Query("DELETE FROM aggregated_behavior_sources")
    suspend fun deleteAllSources()

    @Query("DELETE FROM aggregated_behaviors WHERE startTime BETWEEN :startDate AND :endDate")
    suspend fun deleteByStartTimeRange(startDate: Date, endDate: Date)

    @Query("DELETE FROM aggregated_behavior_sources WHERE aggregatedBehaviorId IN (SELECT id FROM aggregated_behaviors WHERE startTime BETWEEN :startDate AND :endDate)")
    suspend fun deleteSourcesByStartTimeRange(startDate: Date, endDate: Date)

    @Query("SELECT * FROM aggregated_behaviors WHERE date BETWEEN :startDate AND :endDate AND totalDurationSeconds >= :minSeconds ORDER BY date ASC, startTime ASC")
    suspend fun getBehaviorsByDateRange(startDate: Date, endDate: Date, minSeconds: Long): List<AggregatedBehavior>

    @Query("SELECT date as date, COUNT(*) as behaviorCount, SUM(totalDurationSeconds) as totalDurationSeconds FROM aggregated_behaviors WHERE date BETWEEN :startDate AND :endDate AND totalDurationSeconds >= :minSeconds GROUP BY date ORDER BY date ASC")
    suspend fun getDailyStats(startDate: Date, endDate: Date, minSeconds: Long): List<DailyBehaviorStats>

    @Query("SELECT * FROM aggregated_behaviors WHERE date = :date AND totalDurationSeconds >= :minSeconds ORDER BY startTime ASC")
    suspend fun getBehaviorsForDate(date: Date, minSeconds: Long): List<AggregatedBehavior>

    @Query("SELECT recordId FROM aggregated_behavior_sources WHERE aggregatedBehaviorId = :aggregatedBehaviorId")
    suspend fun getSourceRecordIds(aggregatedBehaviorId: Long): List<Long>

    @Transaction
    suspend fun clearAllAggregations() {
        deleteAllSources()
        deleteAllAggregatedBehaviors()
    }

    @Transaction
    suspend fun clearAggregationsByStartWindow(startDate: Date, endDate: Date) {
        deleteSourcesByStartTimeRange(startDate, endDate)
        deleteByStartTimeRange(startDate, endDate)
    }
}
