package com.dazo66.milkmilk

import java.util.Date

// 应用使用信息数据类
data class AppUsageInfo(
    val packageName: String,
    var totalTimeInForeground: Long = 0, // 前台使用时间（秒）
    var lastOpened: Date = Date(), // 最后打开时间
    var openCount: Int = 0 // 打开次数
)