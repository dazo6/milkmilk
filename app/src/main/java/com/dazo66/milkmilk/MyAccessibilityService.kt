package com.dazo66.milkmilk

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.dazo66.milkmilk.notification.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.Calendar

// MyAccessibilityService.kt
class MyAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "MyAccessibilityService"
        private const val NOTIFICATION_CHANNEL_ID = "app_usage_channel"
        private const val NOTIFICATION_ID = 1001
        
        // 共享数据，可以在服务和Activity之间共享
        val appUsageData = ConcurrentHashMap<String, AppUsageInfo>()
        
        // 监听的应用列表
        val monitoredApps = mutableSetOf<String>()

        // 切换确认与最小时长阈值
        private const val SWITCH_CONFIRM_MS = 1500L
        private const val MIN_SESSION_SECONDS = 3L
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default)
    private var trackingJob: Job? = null
    private var currentForegroundApp: String? = null
    private var appStartTime: Long = 0
    private lateinit var prefs: SharedPreferences
    private lateinit var repository: AppUsageRepository
    
    // 观测与待确认切换状态（用于防抖与确认）
    private var lastObservedPackageName: String? = null
    private var pendingSwitchPackageName: String? = null
    private var pendingSwitchStart: Long = 0L
    private var switchConfirmJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "无障碍服务已创建")
        
        // 创建通知渠道
        createNotificationChannel()
        
        // 初始化SharedPreferences
        prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        
        // 初始化数据库仓库
        repository = AppUsageRepository(this)
        
        // 加载设置
        loadSettings()

    }

    private fun currentConfig(): BehaviorAggregationConfig {
        val prefs = getApplicationContext().getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val t1 = prefs.getInt("threshold1", 50).toLong()
        val t2 = prefs.getInt("threshold2", 100).toLong()
        return BehaviorAggregationConfig(threshold1Seconds = t1, threshold2Seconds = t2)
    }

    private fun loadSettings() {
        
        // 加载监听的应用列表
        prefs.getStringSet("monitored_apps", setOf())?.let {
            monitoredApps.clear()
            monitoredApps.addAll(it)
        }
        
        // 加载已保存的使用数据
        val savedData = prefs.all.filter { it.key.startsWith("app_") }
        savedData.forEach { (key, value) ->
            if (key.startsWith("app_") && value is String) {
                try {
                    val parts = value.split(",")
                    val packageName = key.substring(4)
                    val usageInfo = AppUsageInfo(
                        packageName = packageName,
                        totalTimeInForeground = parts[0].toLong(),
                        lastOpened = Date(parts[1].toLong()),
                        openCount = parts[2].toInt()
                    )
                    appUsageData[packageName] = usageInfo
                } catch (e: Exception) {
                    Log.e(TAG, "加载应用数据失败: $key", e)
                }
            }
        }
    }


    private fun saveAppUsageData() {
        val editor = prefs.edit()
        appUsageData.forEach { (packageName, info) ->
            editor.putString(
                "app_$packageName",
                "${info.totalTimeInForeground},${info.lastOpened.time},${info.openCount}"
            )
        }
        editor.apply()
    }
    
    private fun saveUsageRecordToDatabase(packageName: String, startTime: Long, endTime: Long, durationSeconds: Long) {
        serviceScope.launch {
            try {
                // 获取应用名称
                val appName = try {
                    packageManager.getApplicationLabel(
                        packageManager.getApplicationInfo(packageName, 0)
                    ).toString()
                } catch (e: Exception) {
                    packageName
                }
                
                // 创建使用记录
                val repository = AppUsageRepository(this@MyAccessibilityService)
                val record = AppUsageRecord(
                    packageName = packageName,
                    appName = appName,
                    startTime = java.util.Date(startTime),
                    endTime = java.util.Date(endTime),
                    durationSeconds = durationSeconds,
                    date = java.util.Date(endTime)
                )
                kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        repository.insertUsageRecord(record)
                        // 新增实时数据后执行3天窗口增量更新（取7天事件）
                        val monitoredPackages = MyAccessibilityService.monitoredApps.toList()
                        repository.incrementalUpdateAround(record.startTime, monitoredPackages)
                    } catch (e: Exception) {
                        android.util.Log.e("MyAccessibilityService", "保存会话记录失败", e)
                    }
                }
                Log.i(TAG, "统计数据落库成功：$appName，会话时长${durationSeconds}秒")
            } catch (e: Exception) {
                Log.e(TAG, "统计数据落库失败", e)
            }
        }
    }
    
    private fun getTodayDate(): Date {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.time
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "无障碍服务已连接")
        
        // 配置无障碍服务
        val info = serviceInfo
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or 
                          AccessibilityEvent.TYPE_WINDOWS_CHANGED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.notificationTimeout = 100
        info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        serviceInfo = info
        
        // 启动应用追踪
        startAppTracking()
    }

    private fun startAppTracking() {
        trackingJob?.cancel()
        trackingJob = serviceScope.launch {
            while (true) {
                delay(1000) // 每秒检查一次
                currentForegroundApp?.let { packageName ->
                    if (monitoredApps.contains(packageName)) {
                        val info = appUsageData.getOrPut(packageName) {
                            AppUsageInfo(packageName)
                        }
                        info.totalTimeInForeground += 1
                        
                        // 检查是否达到阈值
                        checkThresholds(info)
                    }
                }
            }
        }
    }

    private fun checkThresholds(info: AppUsageInfo) {
        // 将秒转换为分钟进行比较
        val minutesUsed = info.totalTimeInForeground / 60
        val currentConfig = currentConfig()
        if (minutesUsed == currentConfig.threshold1Seconds || minutesUsed == currentConfig.threshold2Seconds) {
            // 发送通知
            sendUsageNotification(info.packageName, minutesUsed)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val packageName = event.packageName?.toString() ?: return
                val className = event.className?.toString() ?: ""

                // 记录最近观测的包名
                lastObservedPackageName = packageName

                if (isLauncher(packageName)) {
                    // 返回桌面立即截断
                    switchConfirmJob?.cancel()
                    logAppSwitch("Home Screen")
                    Log.i(TAG, "返回桌面，结束前台应用会话")
                    handleAppSwitch(null)
                } else {
                    // 仅当包名变化时才视为应用切换；同一应用内视图变更不结束会话
                    if (currentForegroundApp == null) {
                        logAppSwitch("$packageName/$className")
                        Log.i(TAG, "开始监控应用：$packageName/$className")
                        handleAppSwitch(packageName)
                    } else if (packageName != currentForegroundApp) {
                        // 使用切换确认延迟，避免短暂跳转/浮层导致会话截断
                        logAppSwitch("$packageName/$className")
                        Log.i(TAG, "检测到可能的应用切换：$currentForegroundApp -> $packageName/$className，开始确认计时")
                        pendingSwitchPackageName = packageName
                        pendingSwitchStart = System.currentTimeMillis()
                        switchConfirmJob?.cancel()
                        switchConfirmJob = serviceScope.launch {
                            delay(SWITCH_CONFIRM_MS)
                            if (lastObservedPackageName == pendingSwitchPackageName) {
                                Log.i(TAG, "应用切换确认成立：$currentForegroundApp -> $pendingSwitchPackageName")
                                handleAppSwitch(pendingSwitchPackageName)
                            } else {
                                Log.i(TAG, "应用切换确认失败，忽略：期望=$pendingSwitchPackageName 实际=$lastObservedPackageName")
                            }
                        }
                    } else {
                        // 同一应用内的视图变化，忽略，不进行会话截断
                        Log.d(TAG, "同一应用内视图变化，忽略：$packageName/$className")
                    }
                }
            }
        }
    }

    private fun handleAppSwitch(newPackageName: String?) {
        val now = System.currentTimeMillis()
        
        // 记录上一个应用的使用时间
        currentForegroundApp?.let { prevApp ->
            if (monitoredApps.contains(prevApp) && appStartTime > 0) {
                val usedTime = (now - appStartTime) / 1000 // 转换为秒
                if (usedTime >= MIN_SESSION_SECONDS) {
                    val info = appUsageData.getOrPut(prevApp) {
                        AppUsageInfo(prevApp)
                    }
                    info.totalTimeInForeground += usedTime

                    Log.i(TAG, "应用切换，记录会话：$prevApp，开始=${Date(appStartTime)}，结束=${Date(now)}，时长=${usedTime}秒，准备落库")
                    
                    // 保存到SharedPreferences
                    saveAppUsageData()
                    
                    // 保存到数据库
                    saveUsageRecordToDatabase(prevApp, appStartTime, now, usedTime)
                } else {
                    Log.i(TAG, "会话过短(<=${MIN_SESSION_SECONDS}s)，忽略落库：$prevApp，开始=${Date(appStartTime)}，结束=${Date(now)}，时长=${usedTime}秒")
                }
            }
        }
        
        // 更新当前应用
        currentForegroundApp = newPackageName
        appStartTime = now
        
        // 如果是监控的应用，记录打开次数和最后打开时间
        if (newPackageName != null && monitoredApps.contains(newPackageName)) {
            val info = appUsageData.getOrPut(newPackageName) {
                AppUsageInfo(newPackageName)
            }
            info.openCount++
            info.lastOpened = Date(now)
            Log.i(TAG, "开始监控应用会话：$newPackageName，开始时间=${Date(now)}")
        }
    }

    private fun analyzeViewHierarchy(rootNode: AccessibilityNodeInfo) {
        traverseNode(rootNode, 0)
    }

    private fun traverseNode(node: AccessibilityNodeInfo, depth: Int) {
        val viewId = node.viewIdResourceName
        val text = node.text

        (0 until node.childCount).forEach { i ->
            node.getChild(i)?.also { child ->
                traverseNode(child, depth + 1)
                child.recycle()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "应用使用监控"
            val descriptionText = "监控应用使用时间的通知"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun sendUsageNotification(packageName: String, minutesUsed: Long) {
        try {
            // 获取应用名称
            val appName = try {
                packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(packageName, 0)
                ).toString()
            } catch (e: Exception) {
                packageName
            }
            
            /*// 根据使用时间和阈值发送不同级别的通知
            when {
                minutesUsed >= threshold2 -> {
                    NotificationHelper.sendUsageThresholdNotification(
                        context = this,
                        appName = appName,
                        usageTime = minutesUsed.toInt(),
                        isThreshold1 = false
                    )
                }
                minutesUsed >= threshold1 -> {
                    NotificationHelper.sendUsageThresholdNotification(
                        context = this,
                        appName = appName,
                        usageTime = minutesUsed.toInt(),
                        isThreshold1 = true
                    )
                }
            }*/
        } catch (e: Exception) {
            Log.e(TAG, "发送通知失败", e)
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "无障碍服务被中断")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "无障碍服务被销毁")
        trackingJob?.cancel()
        saveAppUsageData()
    }

    private fun isLauncher(packageName: String): Boolean {
        return packageName in setOf(
            "com.android.launcher",
            "com.google.android.apps.nexuslauncher",
            "com.google.android.launcher",
            "com.miui.home",
            "com.huawei.android.launcher"
        )
    }

    private fun logAppSwitch(info: String) {
        // 实现日志记录逻辑
        Log.i(TAG, info)
    }
}


private fun eventTypeToString(type: Int): String {
    return when (type) {
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "TYPE_WINDOW_STATE_CHANGED"
        AccessibilityEvent.TYPE_WINDOWS_CHANGED -> "TYPE_WINDOWS_CHANGED"
        AccessibilityEvent.TYPE_VIEW_CLICKED -> "TYPE_VIEW_CLICKED"
        AccessibilityEvent.TYPE_VIEW_SCROLLED -> "TYPE_VIEW_SCROLLED"
        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "TYPE_VIEW_TEXT_CHANGED"
        AccessibilityEvent.TYPE_VIEW_FOCUSED -> "TYPE_VIEW_FOCUSED"
        AccessibilityEvent.TYPE_VIEW_SELECTED -> "TYPE_VIEW_SELECTED"
        AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> "TYPE_VIEW_LONG_CLICKED"
        AccessibilityEvent.TYPE_ANNOUNCEMENT -> "TYPE_ANNOUNCEMENT"
        AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> "TYPE_NOTIFICATION_STATE_CHANGED"
        else -> "TYPE_" + type
    }
}