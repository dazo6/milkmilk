package com.dazo66.milkmilk

import androidx.lifecycle.LiveData
import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import java.util.Date

@Dao
interface AppUsageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: AppUsageRecord)

    @Query("SELECT * FROM app_usage_records WHERE packageName = :packageName ORDER BY startTime DESC")
    fun getAppUsageRecords(packageName: String): LiveData<List<AppUsageRecord>>

    @Query("SELECT * FROM app_usage_records WHERE startTime BETWEEN :startDate AND :endDate ORDER BY startTime DESC")
    fun getUsageRecordsByDateRange(startDate: Date, endDate: Date): LiveData<List<AppUsageRecord>>

    @Query("SELECT packageName, SUM(durationSeconds) as totalDuration FROM app_usage_records WHERE startTime BETWEEN :startDate AND :endDate GROUP BY packageName ORDER BY totalDuration DESC")
    fun getAppUsageSummary(startDate: Date, endDate: Date): LiveData<List<AppUsageSummary>>

    @Query("SELECT packageName, SUM(durationSeconds) as totalDuration FROM app_usage_records WHERE startTime BETWEEN :startDate AND :endDate GROUP BY packageName ORDER BY totalDuration DESC")
    suspend fun getAppUsageSummaryDirect(startDate: Date, endDate: Date): List<AppUsageSummary>

    @Query("SELECT SUM(durationSeconds) FROM app_usage_records WHERE packageName = :packageName AND startTime BETWEEN :startDate AND :endDate")
    suspend fun getTotalUsageTime(packageName: String, startDate: Date, endDate: Date): Long?

    @Query("SELECT SUM(durationSeconds) FROM app_usage_records WHERE startTime BETWEEN :startDate AND :endDate")
    fun getTotalUsageTime(startDate: Date, endDate: Date): LiveData<Long>

    @Query("SELECT DISTINCT date FROM app_usage_records WHERE packageName = :packageName AND date BETWEEN :startDate AND :endDate ORDER BY date")
    fun getUsageDates(packageName: String, startDate: Date, endDate: Date): LiveData<List<Date>>

    // 获取指定日期范围内的所有使用记录，按时间排序（用于连续行为聚合）
    @Query("SELECT * FROM app_usage_records WHERE startTime BETWEEN :startDate AND :endDate ORDER BY startTime ASC")
    suspend fun getUsageRecordsForAggregation(startDate: Date, endDate: Date): List<AppUsageRecord>

    // 获取监控应用在指定日期范围内的使用记录
    @Query("SELECT * FROM app_usage_records WHERE packageName IN (:monitoredPackages) AND startTime BETWEEN :startDate AND :endDate ORDER BY startTime ASC")
    suspend fun getMonitoredUsageRecordsForAggregation(
        monitoredPackages: List<String>,
        startDate: Date,
        endDate: Date
    ): List<AppUsageRecord>

    // 新增：获取全部会话记录（用于导出）
    @Query("SELECT * FROM app_usage_records ORDER BY startTime ASC")
    suspend fun getAllRecords(): List<AppUsageRecord>

    // 新增：检测时间区间是否与已有会话重叠（用于导入校验）
    @Query("SELECT COUNT(*) FROM app_usage_records WHERE startTime < :endTime AND endTime > :startTime")
    suspend fun countOverlappingSessions(startTime: Date, endTime: Date): Int

    // 新增：删除单条记录（通过ID）
    @Query("DELETE FROM app_usage_records WHERE id = :recordId")
    suspend fun deleteRecord(recordId: Long)

    // 新增：清空全部数据
    @Query("DELETE FROM app_usage_records")
    suspend fun deleteAllRecords()

    // PagingSource：按时间范围分页（不筛选包名）
    @Query("SELECT * FROM app_usage_records WHERE startTime BETWEEN :startDate AND :endDate ORDER BY startTime DESC")
    fun pagingByDateRange(startDate: Date, endDate: Date): PagingSource<Int, AppUsageRecord>

    // PagingSource：按时间范围+包名列表分页
    @Query("SELECT * FROM app_usage_records WHERE packageName IN (:packages) AND startTime BETWEEN :startDate AND :endDate ORDER BY startTime DESC")
    fun pagingByDateRangeAndPackages(
        packages: List<String>,
        startDate: Date,
        endDate: Date
    ): PagingSource<Int, AppUsageRecord>
}