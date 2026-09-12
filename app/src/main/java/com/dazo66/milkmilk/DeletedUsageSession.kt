package com.dazo66.milkmilk

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.Date

/**
 * 用户主动删除的会话标记。
 *
 * 系统的 UsageEvents 在删除后仍会保留数天；该标记让补采与实时写入都能识别
 * 用户的删除意图，避免把同一会话再次写回。
 */
@Entity(
    tableName = "deleted_usage_sessions",
    indices = [Index(value = ["packageName", "startTime", "endTime"]), Index(value = ["deletedAt"])]
)
data class DeletedUsageSession(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val packageName: String,
    val startTime: Date,
    val endTime: Date,
    val deletedAt: Date = Date()
)
