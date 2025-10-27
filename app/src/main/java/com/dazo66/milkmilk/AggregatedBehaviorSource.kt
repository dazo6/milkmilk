package com.dazo66.milkmilk

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * 关联表：记录每个聚合行为由哪些原始事件（AppUsageRecord）组成。
 */
@Entity(
    tableName = "aggregated_behavior_sources",
    primaryKeys = ["aggregatedBehaviorId", "recordId"],
    foreignKeys = [
        ForeignKey(
            entity = AggregatedBehavior::class,
            parentColumns = ["id"],
            childColumns = ["aggregatedBehaviorId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = AppUsageRecord::class,
            parentColumns = ["id"],
            childColumns = ["recordId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("recordId"), Index("aggregatedBehaviorId")]
)
data class AggregatedBehaviorSource(
    val aggregatedBehaviorId: Long,
    val recordId: Long
)
