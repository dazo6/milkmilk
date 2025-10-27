package com.dazo66.milkmilk

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.Date

/**
 * 聚合后的连续行为，落库用于首页统计与详情展示。
 * startTime 作为唯一索引用于增量更新。
 */
@Entity(
    tableName = "aggregated_behaviors",
    indices = [Index(value = ["startTime"], unique = true)]
)
data class AggregatedBehavior(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val startTime: Date,
    val endTime: Date,
    val totalDurationSeconds: Long,
    val date: Date, // 归属日期（按开始时间归属）
    val sessionCount: Int
)
