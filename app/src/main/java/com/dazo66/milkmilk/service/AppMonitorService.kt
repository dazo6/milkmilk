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
import com.dazo66.milkmilk.AppUsageRepository
import com.dazo66.milkmilk.MainActivity
import com.dazo66.milkmilk.R
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

    // UsageStats 回退监控相关
    private var usageMonitorJob: Job? = null
    private var currentForegroundPkg: String? = null
    private var sessionStartTime: Long = 0L
    private val MIN_SESSION_SECONDS = 3L

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

        // 启动基于 UsageStats 的回退监控（当无障碍不可用时生效）
        startUsageFallbackMonitoring()
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
        usageMonitorJob?.cancel()
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
     * 启动基于 UsageStats 的回退监控
     */
    private fun startUsageFallbackMonitoring() {
        usageMonitorJob?.cancel()
        usageMonitorJob = serviceScope.launch {
            while (isActive) {
                try {
                    // 当无障碍不可用时，使用 UsageStats 获取前台应用
                    if (!isAccessibilityEnabled()) {
                        val pkg = getForegroundAppByUsageEvents()
                        if (pkg != null && pkg != currentForegroundPkg) {
                            Log.i(TAG, "监听到应用变化$currentForegroundPkg -> $pkg")
                            handleAppSwitch(pkg)
                        }
                    } else {
                        // 无障碍可用时，避免重复记录，清空会话状态
                        if (currentForegroundPkg != null) {
                            handleAppSwitch(null)
                        }
                        currentForegroundPkg = null
                        sessionStartTime = 0L
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "UsageStats 回退监控失败", e)
                } finally {
                    delay(1000)
                }
            }
        }
    }

    /**
     * 使用 UsageEvents 拿到最近前台应用包名
     */
    private fun getForegroundAppByUsageEvents(): String? {
        return try {
            val usm =
                getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
            val end = System.currentTimeMillis()
            val begin = end - 10_000 // 查最近10秒事件
            val events = usm.queryEvents(begin, end)
            val e = android.app.usage.UsageEvents.Event()
            var lastPkg: String? = null
            var lastTs = 0L
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND && e.timeStamp > lastTs) {
                    lastPkg = e.packageName
                    lastTs = e.timeStamp
                }
            }
            lastPkg
        } catch (ex: Exception) {
            null
        }
    }

    /**
     * 无障碍服务是否已启用（检查本应用的服务）
     */
    private fun isAccessibilityEnabled(): Boolean {
        return try {
            val serviceName = packageName + ".MyAccessibilityService"
            val enabled =
                Settings.Secure.getInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
            if (enabled == 1) {
                val enabledServices = Settings.Secure.getString(
                    contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
                enabledServices?.contains(serviceName) == true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 监控应用列表（从 SharedPreferences 读取）
     */
    private fun monitoredPackages(): Set<String> {
        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        return prefs.getStringSet("monitored_apps", emptySet()) ?: emptySet()
    }

    /**
     * 处理应用切换与会话落库（仅监控列表）
     */
    private fun handleAppSwitch(newPackageName: String?) {
        val now = System.currentTimeMillis()

        // 记录上一个应用的使用时间
        currentForegroundPkg?.let { prevApp ->
            val monitored = monitoredPackages()
            if (monitored.contains(prevApp) && sessionStartTime > 0) {
                val usedTime = (now - sessionStartTime) / 1000 // 秒
                if (usedTime >= MIN_SESSION_SECONDS) {
                    saveUsageRecord(prevApp, sessionStartTime, now, usedTime, monitored)
                }
            }
        }

        // 更新当前应用
        currentForegroundPkg = newPackageName
        sessionStartTime = now
    }

    /**
     * 保存会话到数据库并触发增量聚合
     */
    private fun saveUsageRecord(
        packageName: String,
        startTime: Long,
        endTime: Long,
        durationSeconds: Long,
        monitored: Set<String>
    ) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val appName = try {
                    packageManager.getApplicationLabel(
                        packageManager.getApplicationInfo(packageName, 0)
                    ).toString()
                } catch (e: Exception) {
                    packageName
                }

                val record = com.dazo66.milkmilk.AppUsageRecord(
                    packageName = packageName,
                    appName = appName,
                    startTime = java.util.Date(startTime),
                    endTime = java.util.Date(endTime),
                    durationSeconds = durationSeconds,
                    date = java.util.Date(endTime)
                )
                repository?.insertUsageRecord(record)
                // 增量聚合更新：以开始时间为索引窗口
                repository?.incrementalUpdateAround(record.startTime, monitored.toList())
            } catch (e: Exception) {
                Log.e(TAG, "保存 UsageStats 会话失败", e)
            }
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
                        val totalUsageTime =
                            todaySummary.sumOf { it.totalDuration } / 60000 // 转换为分钟

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
