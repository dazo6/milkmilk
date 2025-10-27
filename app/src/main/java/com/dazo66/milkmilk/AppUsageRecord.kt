package com.dazo66.milkmilk

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Date

@Entity(tableName = "app_usage_records")
data class AppUsageRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val packageName: String,
    val appName: String,
    val startTime: Date,
    val endTime: Date,
    val durationSeconds: Long,
    val date: Date // 用于按日期查询
)