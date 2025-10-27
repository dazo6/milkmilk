package com.dazo66.milkmilk

import java.util.Date

/**
 * 连续行为数据类
 * 表示一个连续的应用使用行为，可能包含多个短暂的应用切换
 */
data class ContinuousBehavior(
    val id: Long = 0,
    val startTime: Date,
    val endTime: Date,
    val totalDurationSeconds: Long,
    val date: Date, // 用于按日期查询
    val sessionCount: Int = 1 // 包含的会话数量
)

/**
 * 每日行为统计
 */
data class DailyBehaviorStats(
    val date: Date,
    val behaviorCount: Int, // 超过阈值2的行为次数
    val totalDurationSeconds: Long // 总时长
)

/**
 * 行为聚合配置
 */
data class BehaviorAggregationConfig(
    val threshold1Seconds: Long = 60, // 阈值1：应用切换间隔阈值（秒）
    val threshold2Seconds: Long = 300  // 阈值2：有效行为最小时长阈值（秒）
)