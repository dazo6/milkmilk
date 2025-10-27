package com.dazo66.milkmilk

data class AppUsageSummary(
    val packageName: String,
    val totalDuration: Long, // 总使用时长（秒）
    val appName: String? = null // 应用名称，可为空
)