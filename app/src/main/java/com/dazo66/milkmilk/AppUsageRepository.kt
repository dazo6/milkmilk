package com.dazo66.milkmilk

import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.paging.PagingSource
import androidx.room.withTransaction
import java.util.Date

class AppUsageRepository(context: Context) {
    private val appContext = context.applicationContext
    private val database = AppDatabase.getDatabase(appContext)
    private val appUsageDao = database.appUsageDao()
    private val aggregatedBehaviorDao = database.aggregatedBehaviorDao()
    private val deletedUsageSessionDao = database.deletedUsageSessionDao()
    private val recoveryPreferences = appContext.getSharedPreferences(
        RECOVERY_PREFERENCES,
        Context.MODE_PRIVATE
    )

    /**
     * 写入会话，并与同一应用的重叠会话合并。
     *
     * 服务实时采集、无障碍服务和 UsageEvents 补采可能会描述同一次前台使用。
     * 因此不能仅跳过新记录：要将所有相交区间收敛为一个 [最早开始, 最晚结束]
     * 的会话，避免时长被重复统计。
     */
    suspend fun insertUsageRecord(record: AppUsageRecord): AppUsageRecord = database.withTransaction {
        var mergedStart = record.startTime
        var mergedEnd = record.endTime
        val overlappingIds = linkedSetOf<Long>()

        // 新边界扩张后可能再碰到下一条记录，例如 [0, 5]、[4, 8]、[7, 10]。
        // 循环直到找全该连通的重叠区间。
        while (true) {
            val overlaps = appUsageDao.getOverlappingSessions(
                record.packageName,
                mergedStart,
                mergedEnd
            )
            val previousCount = overlappingIds.size
            overlaps.forEach { existing ->
                overlappingIds += existing.id
                if (existing.startTime < mergedStart) mergedStart = existing.startTime
                if (existing.endTime > mergedEnd) mergedEnd = existing.endTime
            }
            if (overlappingIds.size == previousCount) break
        }

        // 已有单条记录完全覆盖本次写入时无需重写，以保留其 ID 并避免无效更新。
        if (overlappingIds.size == 1) {
            val existing = appUsageDao.getOverlappingSessions(
                record.packageName,
                mergedStart,
                mergedEnd
            ).singleOrNull()
            if (existing != null && existing.startTime == mergedStart && existing.endTime == mergedEnd) {
                return@withTransaction existing
            }
        }

        if (overlappingIds.isNotEmpty()) {
            appUsageDao.deleteRecords(overlappingIds.toList())
        }
        val mergedRecord = record.copy(
            id = 0,
            startTime = mergedStart,
            endTime = mergedEnd,
            durationSeconds = (mergedEnd.time - mergedStart.time) / 1_000,
            date = mergedEnd
        )
        val id = appUsageDao.insert(mergedRecord)
        mergedRecord.copy(id = id)
    }

    // 获取特定应用的使用记录
    fun getAppUsageRecords(packageName: String): LiveData<List<AppUsageRecord>> {
        return appUsageDao.getAppUsageRecords(packageName)
    }

    // 获取指定日期范围内的使用记录
    fun getUsageRecordsByDateRange(startDate: Date, endDate: Date): LiveData<List<AppUsageRecord>> {
        return appUsageDao.getUsageRecordsByDateRange(startDate, endDate)
    }

    // 获取应用的使用日期列表
    fun getUsageDates(packageName: String, startDate: Date, endDate: Date): LiveData<List<Date>> {
        return appUsageDao.getUsageDates(packageName, startDate, endDate)
    }

    // 获取今日使用总时长
    fun getTodayUsageSummary(): LiveData<Long> {
        val now = Date()
        val cal = java.util.Calendar.getInstance().apply { time = now }
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val startOfDay = cal.time

        cal.set(java.util.Calendar.HOUR_OF_DAY, 23)
        cal.set(java.util.Calendar.MINUTE, 59)
        cal.set(java.util.Calendar.SECOND, 59)
        cal.set(java.util.Calendar.MILLISECOND, 999)
        val endOfDay = cal.time

        return appUsageDao.getTotalUsageTime(startOfDay, endOfDay)
    }

    // 获取今日使用统计（直接返回，非LiveData版本）
    suspend fun getTodayUsageSummaryDirect(): List<AppUsageSummary> {
        val now = Date()
        val cal = java.util.Calendar.getInstance().apply { time = now }
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val startOfDay = cal.time

        cal.set(java.util.Calendar.HOUR_OF_DAY, 23)
        cal.set(java.util.Calendar.MINUTE, 59)
        cal.set(java.util.Calendar.SECOND, 59)
        cal.set(java.util.Calendar.MILLISECOND, 999)
        val endOfDay = cal.time

        return appUsageDao.getAppUsageSummaryDirect(startOfDay, endOfDay)
    }

    // 获取本周使用总时长
    fun getWeekUsageSummary(): LiveData<Long> {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.DAY_OF_WEEK, cal.firstDayOfWeek)
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val startOfWeek = cal.time

        cal.add(java.util.Calendar.DAY_OF_WEEK, 6)
        cal.set(java.util.Calendar.HOUR_OF_DAY, 23)
        cal.set(java.util.Calendar.MINUTE, 59)
        cal.set(java.util.Calendar.SECOND, 59)
        cal.set(java.util.Calendar.MILLISECOND, 999)
        val endOfWeek = cal.time

        return appUsageDao.getTotalUsageTime(startOfWeek, endOfWeek)
    }

    // 获取本月使用总时长
    fun getMonthUsageSummary(): LiveData<Long> {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.DAY_OF_MONTH, 1)
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val startOfMonth = cal.time

        cal.add(java.util.Calendar.MONTH, 1)
        cal.add(java.util.Calendar.DAY_OF_MONTH, -1)
        cal.set(java.util.Calendar.HOUR_OF_DAY, 23)
        cal.set(java.util.Calendar.MINUTE, 59)
        cal.set(java.util.Calendar.SECOND, 59)
        cal.set(java.util.Calendar.MILLISECOND, 999)
        val endOfMonth = cal.time

        return appUsageDao.getTotalUsageTime(startOfMonth, endOfMonth)
    }

    // 获取本年使用总时长
    fun getYearUsageSummary(): LiveData<Long> {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.DAY_OF_YEAR, 1)
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val startOfYear = cal.time

        cal.set(java.util.Calendar.MONTH, 11) // 12月
        cal.set(java.util.Calendar.DAY_OF_MONTH, 31)
        cal.set(java.util.Calendar.HOUR_OF_DAY, 23)
        cal.set(java.util.Calendar.MINUTE, 59)
        cal.set(java.util.Calendar.SECOND, 59)
        cal.set(java.util.Calendar.MILLISECOND, 999)
        val endOfYear = cal.time

        return appUsageDao.getTotalUsageTime(startOfYear, endOfYear)
    }

    // 获取分页数据（按时间范围）
    fun getPagedUsageRecords(
        startDate: Date,
        endDate: Date,
        monitoredPackages: List<String>
    ): PagingSource<Int, AppUsageRecord> {
        return if (monitoredPackages.isEmpty()) {
            appUsageDao.pagingByDateRange(startDate, endDate)
        } else {
            appUsageDao.pagingByDateRangeAndPackages(monitoredPackages, startDate, endDate)
        }
    }

    // 新增：获取全部记录（导出使用）
    suspend fun getAllRecordsDirect(): List<AppUsageRecord> {
        return appUsageDao.getAllRecords()
    }

    // 新增：导入校验（是否有重叠）
    suspend fun hasOverlap(startTime: Date, endTime: Date): Boolean {
        return appUsageDao.countOverlappingSessions(startTime, endTime) > 0
    }

    /** 删除记录并保留短期墓碑，防止仍存在于 UsageEvents 中的同一会话被补回。 */
    suspend fun deleteRecord(record: AppUsageRecord) {
        database.withTransaction {
            deletedUsageSessionDao.insert(
                DeletedUsageSession(
                    packageName = record.packageName,
                    startTime = record.startTime,
                    endTime = record.endTime
                )
            )
            appUsageDao.deleteRecord(record.id)
        }
    }

    /**
     * 清空数据后忽略清空时刻之前的系统事件；否则 Worker 的三天回看窗口会把
     * 已清空的数据重新写入。
     */
    suspend fun deleteAllRecords() {
        database.withTransaction {
            appUsageDao.deleteAllRecords()
            aggregatedBehaviorDao.clearAllAggregations()
        }
        recoveryPreferences.edit()
            .putLong(RECOVERY_IGNORE_BEFORE, System.currentTimeMillis())
            .apply()
    }

    suspend fun isRecoverySuppressed(
        packageName: String,
        startTime: Date,
        endTime: Date
    ): Boolean {
        return deletedUsageSessionDao.countOverlappingTombstones(
            packageName,
            startTime,
            endTime
        ) > 0
    }

    fun recoveryIgnoreBefore(): Long = recoveryPreferences.getLong(RECOVERY_IGNORE_BEFORE, 0L)

    suspend fun pruneRecoveryTombstones(cutoff: Long) {
        deletedUsageSessionDao.deleteBefore(Date(cutoff))
    }

    // 行为聚合服务
    private val behaviorAggregationService = BehaviorAggregationService(context)

    // 获取每日行为统计（旧：直接计算）
    suspend fun getDailyBehaviorStats(
        startDate: Date,
        endDate: Date,
        monitoredPackages: List<String>
    ): List<DailyBehaviorStats> {
        return behaviorAggregationService.calculateDailyBehaviorStats(
            startDate,
            endDate,
            monitoredPackages
        )
    }

    // 新增：从聚合表读取每日行为统计（首页切换目标）
    suspend fun getDailyBehaviorStatsFromAggregated(
        startDate: Date,
        endDate: Date,
        minSeconds: Long
    ): List<DailyBehaviorStats> {
        return aggregatedBehaviorDao.getDailyStats(startDate, endDate, minSeconds)
    }


    // 新增：从聚合表读取区间的行为详情（映射为 ContinuousBehavior）
    suspend fun getAggregatedBehaviorsByRange(
        startDate: Date,
        endDate: Date,
        minSeconds: Long
    ): List<ContinuousBehavior> {
        val rows = aggregatedBehaviorDao.getBehaviorsByDateRange(startDate, endDate, minSeconds)
        return rows.map {
            ContinuousBehavior(
                id = it.id,
                startTime = it.startTime,
                endTime = it.endTime,
                totalDurationSeconds = ((it.endTime.time - it.startTime.time) / 1000),
                date = it.date,
                sessionCount = it.sessionCount
            )
        }
    }

    // 新增：从聚合表读取指定日期的行为详情（映射为 ContinuousBehavior）
    suspend fun getAggregatedBehaviorsForDate(
        date: Date,
        minSeconds: Long
    ): List<ContinuousBehavior> {
        val rows = aggregatedBehaviorDao.getBehaviorsForDate(date, minSeconds)
        return rows.map {
            ContinuousBehavior(
                id = it.id,
                startTime = it.startTime,
                endTime = it.endTime,
                totalDurationSeconds = ((it.endTime.time - it.startTime.time) / 1000),
                date = it.date,
                sessionCount = it.sessionCount
            )
        }
    }

    // ========= 聚合表持久化与重算 =========

    // 全量重算：清空聚合表 -> 全范围聚合 -> 批量落库
    suspend fun recomputeAllAggregations(monitoredPackages: List<String>) {
        // 1) 清空聚合表
        aggregatedBehaviorDao.clearAllAggregations()
        // 2) 确定全量时间范围
        val all = appUsageDao.getAllRecords()
        if (all.isEmpty()) return
        val startDate = all.first().startTime
        val endDate = all.last().endTime
        // 3) 聚合（携带源ID）
        val aggregated = behaviorAggregationService.aggregateContinuousBehaviorsWithSources(
            startDate,
            endDate,
            monitoredPackages
        )
        if (aggregated.isEmpty()) return
        // 4) 批量插入行为
        val behaviorRows = aggregated.map {
            AggregatedBehavior(
                startTime = it.behavior.startTime,
                endTime = it.behavior.endTime,
                totalDurationSeconds = it.behavior.totalDurationSeconds,
                date = it.behavior.date,
                sessionCount = it.behavior.sessionCount
            )
        }
        val ids = aggregatedBehaviorDao.insertBehaviors(behaviorRows)
        // 5) 批量插入来源映射
        val sources = mutableListOf<AggregatedBehaviorSource>()
        for (i in ids.indices) {
            val aggId = ids[i]
            val srcIds = aggregated[i].sourceRecordIds
            srcIds.forEach { rid ->
                sources.add(AggregatedBehaviorSource(aggregatedBehaviorId = aggId, recordId = rid))
            }
        }
        if (sources.isNotEmpty()) {
            aggregatedBehaviorDao.insertSources(sources)
        }
        // 日志与通知：全量重算完成
        Log.i("AppUsageRepository", "全量重算写入聚合行为 ${ids.size} 条")
        AggregationEvents.notifyUpdated()
    }

    // 增量更新：以某次新增记录的开始时间为索引，更新其后三天的行为；聚合时取前7天事件以兼容跨天
    suspend fun incrementalUpdateAround(startIndexTime: Date, monitoredPackages: List<String>) {
        val cal = java.util.Calendar.getInstance()
        cal.time = startIndexTime
        // 计算窗口：聚合事件窗口 = startIndexTime - 7天 到 startIndexTime + 3天末尾
        val windowStart = java.util.Calendar.getInstance().apply {
            time = startIndexTime
            add(java.util.Calendar.DAY_OF_YEAR, -7)
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.time
        val windowEnd = java.util.Calendar.getInstance().apply {
            time = startIndexTime
            add(java.util.Calendar.DAY_OF_YEAR, 3)
            set(java.util.Calendar.HOUR_OF_DAY, 23)
            set(java.util.Calendar.MINUTE, 59)
            set(java.util.Calendar.SECOND, 59)
            set(java.util.Calendar.MILLISECOND, 999)
        }.time

        // 聚合（含源ID）
        val aggregated = behaviorAggregationService.aggregateContinuousBehaviorsWithSources(
            windowStart,
            windowEnd,
            monitoredPackages
        )
        if (aggregated.isEmpty()) return

        // 过滤：只更新开始时间位于 startIndexTime .. startIndexTime+3d 的行为
        val updateStart = java.util.Calendar.getInstance().apply {
            time = startIndexTime
            add(java.util.Calendar.DAY_OF_YEAR, -3)
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.time
        var updateEnd = windowEnd
        val toUpdate =
            aggregated.filter { it.behavior.startTime.time in updateStart.time..updateEnd.time }
        if (toUpdate.isEmpty()) return


        // 先清理该开始时间窗口（索引日起后3天）内的已有聚合
        aggregatedBehaviorDao.clearAggregationsByStartWindow(updateStart, updateEnd)

        // 批量插入行为
        val behaviorRows = toUpdate.map {
            AggregatedBehavior(
                startTime = it.behavior.startTime,
                endTime = it.behavior.endTime,
                totalDurationSeconds = it.behavior.totalDurationSeconds,
                date = it.behavior.date,
                sessionCount = it.behavior.sessionCount
            )
        }
        val ids = aggregatedBehaviorDao.insertBehaviors(behaviorRows)
        // 插入来源映射
        val sources = mutableListOf<AggregatedBehaviorSource>()
        for (i in ids.indices) {
            val aggId = ids[i]
            val srcIds = toUpdate[i].sourceRecordIds
            srcIds.forEach { rid ->
                sources.add(
                    AggregatedBehaviorSource(
                        aggregatedBehaviorId = aggId,
                        recordId = rid
                    )
                )
            }
        }
        if (sources.isNotEmpty()) {
            aggregatedBehaviorDao.insertSources(sources)
        }
        // 日志与通知：增量更新完成
        Log.i(
            "AppUsageRepository",
            "增量更新写入聚合行为 ${ids.size} 条；窗口 ${updateStart} .. ${updateEnd}"
        )
        AggregationEvents.notifyUpdated()
    }
}

private const val RECOVERY_PREFERENCES = "usage_recovery"
private const val RECOVERY_IGNORE_BEFORE = "ignore_before"
