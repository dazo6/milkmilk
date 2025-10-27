package com.dazo66.milkmilk

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.lifecycle.LiveData
import androidx.paging.PagingSource
import java.util.Date

class AppUsageRepository(context: Context) {
    private val appUsageDao = AppDatabase.getDatabase(context).appUsageDao()
    private val aggregatedBehaviorDao = AppDatabase.getDatabase(context).aggregatedBehaviorDao()

    // 插入使用记录
    suspend fun insertUsageRecord(record: AppUsageRecord) {
        appUsageDao.insert(record)
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
    fun getPagedUsageRecords(startDate: Date, endDate: Date, monitoredPackages: List<String>): PagingSource<Int, AppUsageRecord> {
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

    // 新增：删除单条记录
    suspend fun deleteRecord(recordId: Long) {
        appUsageDao.deleteRecord(recordId)
    }

    // 新增：清空全部记录
    suspend fun deleteAllRecords() {
        appUsageDao.deleteAllRecords()
        aggregatedBehaviorDao.clearAllAggregations()
    }

    // 行为聚合服务
    private val behaviorAggregationService = BehaviorAggregationService(context)

    // 获取每日行为统计（旧：直接计算）
    suspend fun getDailyBehaviorStats(startDate: Date, endDate: Date, monitoredPackages: List<String>): List<DailyBehaviorStats> {
        return behaviorAggregationService.calculateDailyBehaviorStats(startDate, endDate, monitoredPackages)
    }

    // 新增：从聚合表读取每日行为统计（首页切换目标）
    suspend fun getDailyBehaviorStatsFromAggregated(startDate: Date, endDate: Date, minSeconds: Long): List<DailyBehaviorStats> {
        return aggregatedBehaviorDao.getDailyStats(startDate, endDate, minSeconds)
    }


    // 新增：从聚合表读取区间的行为详情（映射为 ContinuousBehavior）
    suspend fun getAggregatedBehaviorsByRange(startDate: Date, endDate: Date, minSeconds: Long): List<ContinuousBehavior> {
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
    suspend fun getAggregatedBehaviorsForDate(date: Date, minSeconds: Long): List<ContinuousBehavior> {
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
        val aggregated = behaviorAggregationService.aggregateContinuousBehaviorsWithSources(startDate, endDate, monitoredPackages)
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
        val aggregated = behaviorAggregationService.aggregateContinuousBehaviorsWithSources(windowStart, windowEnd, monitoredPackages)
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
        val toUpdate = aggregated.filter { it.behavior.startTime.time in updateStart.time..updateEnd.time }
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
            srcIds.forEach { rid -> sources.add(AggregatedBehaviorSource(aggregatedBehaviorId = aggId, recordId = rid)) }
        }
        if (sources.isNotEmpty()) {
            aggregatedBehaviorDao.insertSources(sources)
        }
        // 日志与通知：增量更新完成
        Log.i("AppUsageRepository", "增量更新写入聚合行为 ${ids.size} 条；窗口 ${updateStart} .. ${updateEnd}")
        AggregationEvents.notifyUpdated()
    }
}