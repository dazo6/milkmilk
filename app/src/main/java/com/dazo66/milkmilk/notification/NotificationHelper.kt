package com.dazo66.milkmilk.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.dazo66.milkmilk.MainActivity
import com.dazo66.milkmilk.R

/**
 * 通知帮助类，用于创建和发送应用使用时间通知
 */
object NotificationHelper {
    private const val CHANNEL_ID = "app_usage_channel"
    private const val CHANNEL_NAME = "应用使用时间"
    private const val CHANNEL_DESCRIPTION = "应用使用时间监控通知"
    
    private const val NOTIFICATION_ID_THRESHOLD_1 = 1001
    private const val NOTIFICATION_ID_THRESHOLD_2 = 1002
    
    /**
     * 创建通知渠道
     */
    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = CHANNEL_DESCRIPTION
            }
            
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    /**
     * 发送应用使用时间阈值通知
     * @param context 上下文
     * @param appName 应用名称
     * @param usageTime 使用时间（分钟）
     * @param isThreshold1 是否是阈值1（true为阈值1，false为阈值2）
     */
    fun sendUsageThresholdNotification(
        context: Context,
        appName: String,
        usageTime: Int,
        isThreshold1: Boolean
    ) {
        val notificationId = if (isThreshold1) NOTIFICATION_ID_THRESHOLD_1 else NOTIFICATION_ID_THRESHOLD_2
        val thresholdText = if (isThreshold1) "第一阈值" else "第二阈值"
        
        // 创建打开应用的Intent
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent, 
            PendingIntent.FLAG_IMMUTABLE
        )
        
        // 构建通知
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification) // 确保在drawable中有此图标
            .setContentTitle("应用使用时间提醒")
            .setContentText("$appName 已使用 $usageTime 分钟，达到$thresholdText")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        
        // 发送通知
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notificationId, notification)
    }
    
    /**
     * 发送每日使用统计通知
     * @param context 上下文
     * @param totalApps 监控的应用总数
     * @param totalUsageTime 总使用时间（分钟）
     * @param mostUsedApp 使用最多的应用
     * @param mostUsedTime 最多使用时间（分钟）
     */
    fun sendDailySummaryNotification(
        context: Context,
        totalApps: Int,
        totalUsageTime: Int,
        mostUsedApp: String,
        mostUsedTime: Int
    ) {
        // 创建打开应用的Intent
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent, 
            PendingIntent.FLAG_IMMUTABLE
        )
        
        // 构建通知
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("每日应用使用统计")
            .setContentText("今日共监控 $totalApps 个应用，总使用时间 $totalUsageTime 分钟")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("今日共监控 $totalApps 个应用，总使用时间 $totalUsageTime 分钟\n" +
                        "使用最多的应用: $mostUsedApp ($mostUsedTime 分钟)"))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        
        // 发送通知
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(1003, notification)
    }
}