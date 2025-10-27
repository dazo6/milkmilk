package com.dazo66.milkmilk.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.dazo66.milkmilk.MainActivity
import com.dazo66.milkmilk.R
import com.dazo66.milkmilk.AppUsageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * 应用监控前台服务，确保应用在后台稳定运行
 */
class AppMonitorService : Service() {
    companion object {
        private const val TAG = "AppMonitorService"
        private const val NOTIFICATION_CHANNEL_ID = "app_monitor_channel"
        private const val NOTIFICATION_ID = 1001
        
        // 服务状态
        private var isServiceRunning = false
        
        // 启动服务
        fun startService(context: Context) {
            if (!isServiceRunning) {
                val intent = Intent(context, AppMonitorService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }
        
        // 停止服务
        fun stopService(context: Context) {
            val intent = Intent(context, AppMonitorService::class.java)
            context.stopService(intent)
        }
    }
    
    private val serviceScope = CoroutineScope(Dispatchers.Default)
    private var dailySummaryJob: Job? = null
    private var repository: AppUsageRepository? = null
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "服务创建")
        isServiceRunning = true
        repository = AppUsageRepository(this)
        
        // 创建通知渠道
        createNotificationChannel()
        
        // 启动前台服务（指定类型）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                createNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }
        
        // 启动每日统计任务
        scheduleDailySummary()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "服务启动")
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "服务销毁")
        isServiceRunning = false
        dailySummaryJob?.cancel()
    }
    
    /**
     * 创建通知渠道
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "应用监控服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持应用监控服务在后台运行"
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    /**
     * 创建前台服务通知
     */
    private fun createNotification(): Notification {
        // 创建打开应用的Intent
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, 
            PendingIntent.FLAG_IMMUTABLE
        )
        
        // 创建打开无障碍设置的Intent
        val accessibilityIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        val accessibilityPendingIntent = PendingIntent.getActivity(
            this, 1, accessibilityIntent,
            PendingIntent.FLAG_IMMUTABLE
        )
        
        // 构建通知
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("应用监控服务运行中")
            .setContentText("正在监控应用使用情况")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .addAction(0, "无障碍设置", accessibilityPendingIntent)
            .build()
    }
    
    /**
     * 安排每日统计任务
     */
    private fun scheduleDailySummary() {
        dailySummaryJob = serviceScope.launch {
            while (isActive) {
                // 计算到明天0点的时间
                val now = Calendar.getInstance()
                val nextDay = Calendar.getInstance().apply {
                    add(Calendar.DAY_OF_MONTH, 1)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val delayMillis = nextDay.timeInMillis - now.timeInMillis
                
                // 等待到明天0点
                delay(delayMillis)
                
                // 生成并发送每日统计通知
                generateDailySummary()
                
                // 每天执行一次
                delay(24 * 60 * 60 * 1000)
            }
        }
    }
    
    /**
     * 生成每日统计并发送通知
     */
    private fun generateDailySummary() {
        repository?.let { repo ->
            serviceScope.launch {
                try {
                    // 获取今日使用统计（使用非LiveData版本）
                    val todaySummary = repo.getTodayUsageSummaryDirect()
                    
                    if (todaySummary.isNotEmpty()) {
                        // 计算总使用时间
                        val totalUsageTime = todaySummary.sumOf { it.totalDuration } / 60000 // 转换为分钟
                        
                        // 找出使用最多的应用
                        val mostUsedApp = todaySummary.maxByOrNull { it.totalDuration }
                        
                        if (mostUsedApp != null) {
                            // 发送每日统计通知
                            val appName = try {
                                packageManager.getApplicationInfo(mostUsedApp.packageName, 0)
                                    .loadLabel(packageManager).toString()
                            } catch (e: Exception) {
                                mostUsedApp.packageName
                            }
                            
                            com.dazo66.milkmilk.notification.NotificationHelper.sendDailySummaryNotification(
                                context = this@AppMonitorService,
                                totalApps = todaySummary.size,
                                totalUsageTime = totalUsageTime.toInt(),
                                mostUsedApp = appName,
                                mostUsedTime = (mostUsedApp.totalDuration / 60000).toInt()
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "生成每日统计失败", e)
                }
            }
        }
    }
}