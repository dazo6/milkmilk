package com.dazo66.milkmilk

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date

/**
 * 行为聚合服务
 * 负责将原始的应用使用记录聚合成连续行为，并统计有效行为次数
 */
class BehaviorAggregationService(private val context: Context) {
    // 从用户设置中动态读取聚合配置（秒）
    private fun currentConfig(): BehaviorAggregationConfig {
        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val t1 = prefs.getInt("threshold1", 50).toLong()
        val t2 = prefs.getInt("threshold2", 100).toLong()
        return BehaviorAggregationConfig(threshold1Seconds = t1, threshold2Seconds = t2)
    }

    companion object {
        private const val TAG = "BehaviorAggregation"
    }

    /**
     * 携带源事件ID的聚合结果
     */
    data class AggregatedBehaviorWithSources(
        val behavior: ContinuousBehavior,
        val sourceRecordIds: List<Long>
    )

    /**
     * 聚合指定日期范围内的连续行为（含源事件ID）
     */
    suspend fun aggregateContinuousBehaviorsWithSources(
        startDate: Date,
        endDate: Date,
        monitoredPackages: List<String>
    ): List<AggregatedBehaviorWithSources> = withContext(Dispatchers.IO) {
        val cfg = currentConfig()
        try {
            val appUsageDao = AppDatabase.getDatabase(context).appUsageDao()
            val records = appUsageDao.getUsageRecordsForAggregation(startDate, endDate)


            if (records.isEmpty()) return@withContext emptyList()

            val results = mutableListOf<AggregatedBehaviorWithSources>()
            var currentBehaviorStart: Date? = null
            var currentBehaviorEnd: Date? = null
            var currentSessionCount = 0
            var lastEndTime: Date? = null
            val currentSourceIds = mutableListOf<Long>()

            for (record in records) {

                if (currentBehaviorStart == null) {
                    currentBehaviorStart = record.startTime
                    currentBehaviorEnd = record.endTime
                    currentSessionCount = 1
                    currentSourceIds.clear()
                    currentSourceIds.add(record.id)
                } else {
                    val gapSeconds = (record.startTime.time - (lastEndTime?.time ?: currentBehaviorEnd!!.time)) / 1000
                    if (gapSeconds <= cfg.threshold1Seconds) {
                        // 继续同一连续行为
                        if (record.endTime.time > currentBehaviorEnd!!.time) {
                            currentBehaviorEnd = record.endTime
                        }
                        currentSessionCount += 1
                        currentSourceIds.add(record.id)
                    } else {
                        // 收尾上一个行为
                        val duration = (currentBehaviorEnd!!.time - currentBehaviorStart!!.time) / 1000
                        val behavior = ContinuousBehavior(
                            startTime = currentBehaviorStart!!,
                            endTime = currentBehaviorEnd!!,
                            totalDurationSeconds = duration,
                            date = getTodayDate(currentBehaviorStart!!),
                            sessionCount = currentSessionCount
                        )
                        results.add(AggregatedBehaviorWithSources(behavior, currentSourceIds.toList()))

                        // 开启新的行为
                        currentBehaviorStart = record.startTime
                        currentBehaviorEnd = record.endTime
                        currentSessionCount = 1
                        currentSourceIds.clear()
                        currentSourceIds.add(record.id)
                    }
                }
                lastEndTime = record.endTime
            }

            // 收尾处理最后一个行为
            if (currentBehaviorStart != null && currentBehaviorEnd != null) {
                val duration = (currentBehaviorEnd.time - currentBehaviorStart.time) / 1000
                val behavior = ContinuousBehavior(
                    startTime = currentBehaviorStart,
                    endTime = currentBehaviorEnd,
                    totalDurationSeconds = duration,
                    date = getTodayDate(currentBehaviorStart),
                    sessionCount = currentSessionCount
                )
                results.add(AggregatedBehaviorWithSources(behavior, currentSourceIds.toList()))
            }

            Log.i(TAG, "聚合完成（含源ID），共生成${results.size}个连续行为")
            results
        } catch (e: Exception) {
            Log.e(TAG, "行为聚合失败（含源ID）", e)
            emptyList()
        }
    }

    // 原有方法保留
    suspend fun aggregateContinuousBehaviors(
        startDate: Date,
        endDate: Date,
        monitoredPackages: List<String>
    ): List<ContinuousBehavior> = withContext(Dispatchers.IO) {
        val cfg = currentConfig()
        try {
            val appUsageDao = AppDatabase.getDatabase(context).appUsageDao()
            // 当监控列表为空时，回退到全量记录，避免统计为空
            val records = if (monitoredPackages.isEmpty()) {
                appUsageDao.getUsageRecordsForAggregation(startDate, endDate)
            } else {
                appUsageDao.getMonitoredUsageRecordsForAggregation(monitoredPackages, startDate, endDate)
            }

            if (records.isEmpty()) {
                return@withContext emptyList()
            }

            val behaviors = mutableListOf<ContinuousBehavior>()
            var currentBehaviorStart: Date? = null
            var currentBehaviorEnd: Date? = null
            var currentSessionCount = 0
            var lastEndTime: Date? = null

            for (record in records) {
                // 监控列表为空：包含所有记录；否则只包含监控应用记录
                val includeRecord = monitoredPackages.isEmpty() || monitoredPackages.contains(record.packageName)
                if (!includeRecord) continue

                if (currentBehaviorStart == null) {
                    currentBehaviorStart = record.startTime
                    currentBehaviorEnd = record.endTime
                    currentSessionCount = 1
                } else {
                    val gapSeconds = (record.startTime.time - (lastEndTime?.time ?: currentBehaviorEnd!!.time)) / 1000
                    if (gapSeconds <= cfg.threshold1Seconds) {
                        // 继续聚合
                        if (record.endTime.time > currentBehaviorEnd!!.time) {
                            currentBehaviorEnd = record.endTime
                        }
                        currentSessionCount += 1
                    } else {
                        // 收尾处理
                        val duration = (currentBehaviorEnd!!.time - currentBehaviorStart!!.time) / 1000
                        behaviors.add(
                            ContinuousBehavior(
                                startTime = currentBehaviorStart!!,
                                endTime = currentBehaviorEnd!!,
                                totalDurationSeconds = duration,
                                date = getTodayDate(currentBehaviorStart!!),
                                sessionCount = currentSessionCount
                            )
                        )
                        // 开启新的行为
                        currentBehaviorStart = record.startTime
                        currentBehaviorEnd = record.endTime
                        currentSessionCount = 1
                    }
                }
                lastEndTime = record.endTime
            }

            // 收尾处理最后一个行为
            if (currentBehaviorStart != null && currentBehaviorEnd != null) {
                val duration = (currentBehaviorEnd.time - currentBehaviorStart.time) / 1000
                behaviors.add(
                    ContinuousBehavior(
                        startTime = currentBehaviorStart,
                        endTime = currentBehaviorEnd,
                        totalDurationSeconds = duration,
                        date = getTodayDate(currentBehaviorStart),
                        sessionCount = currentSessionCount
                    )
                )
            }

            Log.i(TAG, "聚合完成，共生成${behaviors.size}个连续行为")
            behaviors
        } catch (e: Exception) {
            Log.e(TAG, "行为聚合失败", e)
            emptyList()
        }
    }

    /**
     * 计算每日行为统计
     */
    suspend fun calculateDailyBehaviorStats(
        startDate: Date,
        endDate: Date,
        monitoredPackages: List<String>
    ): List<DailyBehaviorStats> = withContext(Dispatchers.IO) {
        val cfg = currentConfig()
        try {
            val behaviors = aggregateContinuousBehaviors(startDate, endDate, monitoredPackages)

            // 按日期分组统计
            val dailyStats = mutableMapOf<Date, MutableList<ContinuousBehavior>>()

            behaviors.forEach { behavior ->
                val dateKey = getTodayDate(behavior.startTime)
                dailyStats.getOrPut(dateKey) { mutableListOf() }.add(behavior)
            }

            // 计算每日统计
            dailyStats.map { (date, dayBehaviors) ->
                val validBehaviors = dayBehaviors.filter {
                    it.totalDurationSeconds >= cfg.threshold2Seconds
                }

                DailyBehaviorStats(
                    date = date,
                    behaviorCount = validBehaviors.size,
                    totalDurationSeconds = validBehaviors.sumOf { it.totalDurationSeconds }
                )
            }.sortedBy { it.date }
        } catch (e: Exception) {
            Log.e(TAG, "每日行为统计计算失败", e)
            emptyList()
        }
    }

    // 按日期区间获取有效连续行为列表
    suspend fun getValidBehaviorsForRange(
        startDate: Date,
        endDate: Date,
        monitoredPackages: List<String>
    ): List<ContinuousBehavior> = withContext(Dispatchers.IO) {
        val cfg = currentConfig()
        try {
            val behaviors = aggregateContinuousBehaviors(startDate, endDate, monitoredPackages)
            behaviors.filter { it.totalDurationSeconds >= cfg.threshold2Seconds }
        } catch (e: Exception) {
            Log.e(TAG, "获取区间有效行为列表失败", e)
            emptyList()
        }
    }
}

// 将日期归一化为当天00:00
private fun getTodayDate(date: Date): Date {
    val cal = java.util.Calendar.getInstance().apply { time = date }
    cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
    cal.set(java.util.Calendar.MINUTE, 0)
    cal.set(java.util.Calendar.SECOND, 0)
    cal.set(java.util.Calendar.MILLISECOND, 0)
    return cal.time
}