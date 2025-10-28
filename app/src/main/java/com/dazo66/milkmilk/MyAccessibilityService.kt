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
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import com.dazo66.milkmilk.BuildConfig
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

        private val systemPackages = setOf(
            "com.android.systemui"
        )
        private val imePackages = setOf(
            "com.google.android.inputmethod.latin",
            "com.samsung.android.honeyboard",
            "com.baidu.input",
            "com.tencent.qqpinyin",
            "com.iflytek.inputmethod",
            // Sogou 输入法常见包名
            "com.sohu.inputmethod.sogou",
            "com.sogou.inputmethod",
            // 厂商输入法常见包名
            "com.miui.inputmethod",     // 小米
            "com.coloros.ime",          // OPPO/ColorOS
            "com.vivo.ime",             // vivo
            "com.meizu.inputmethod",    // 魅族
            "com.huawei.inputmethod"    // 华为
        )
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

    // 调试悬浮窗相关
    private var overlayView: TextView? = null
    private var windowManager: WindowManager? = null
    private var overlayAdded: Boolean = false
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private val mainHandler = Handler(Looper.getMainLooper())

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

        // 初始化窗口管理器
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // 监听设置变化以动态开关悬浮窗
        prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "debug_overlay_enabled" || key == "app_foreground") {
                ensureOverlayState()
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        ensureOverlayState()

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
                val rawPkg = event.packageName?.toString() ?: return
                val className = event.className?.toString() ?: ""

                val activePkg = getActiveWindowPackageName()
                val rootActivePkg = getRootActiveWindowPackageName()
                val candidate = when {
                    activePkg != null && activePkg == rawPkg -> rawPkg
                    rootActivePkg != null && rootActivePkg == rawPkg -> rawPkg
                    activePkg != null -> activePkg
                    rootActivePkg != null -> rootActivePkg
                    else -> rawPkg
                }

                if (shouldIgnorePackage(candidate)) return

                lastObservedPackageName = candidate

                if (isLauncher(candidate)) {
                    switchConfirmJob?.cancel()
                    logAppSwitch("Home Screen")
                    Log.i(TAG, "返回桌面，结束前台应用会话")
                    handleAppSwitch(null)
                } else {
                    if (currentForegroundApp == null) {
                        logAppSwitch("$candidate/$className")
                        Log.i(TAG, "开始监控应用：$candidate/$className")
                        handleAppSwitch(candidate)
                    } else if (candidate != currentForegroundApp) {
                        logAppSwitch("$candidate/$className")
                        Log.i(TAG, "检测到可能的应用切换：$currentForegroundApp -> $candidate/$className，开始确认计时")
                        pendingSwitchPackageName = candidate
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
                        Log.d(TAG, "同一应用内视图变化，忽略：$candidate/$className")
                    }
                }
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                val activePkg = getActiveWindowPackageName() ?: getRootActiveWindowPackageName()
                if (activePkg.isNullOrEmpty()) return
                if (shouldIgnorePackage(activePkg)) return

                lastObservedPackageName = activePkg

                if (currentForegroundApp == null) {
                    Log.i(TAG, "开始监控应用：$activePkg")
                    handleAppSwitch(activePkg)
                } else if (activePkg != currentForegroundApp) {
                    Log.i(TAG, "检测到窗口活跃变化：$currentForegroundApp -> $activePkg，开始确认计时")
                    pendingSwitchPackageName = activePkg
                    pendingSwitchStart = System.currentTimeMillis()
                    switchConfirmJob?.cancel()
                    switchConfirmJob = serviceScope.launch {
                        delay(SWITCH_CONFIRM_MS)
                        if (lastObservedPackageName == pendingSwitchPackageName) {
                            Log.i(TAG, "窗口活跃变化确认成立：$currentForegroundApp -> $pendingSwitchPackageName")
                            handleAppSwitch(pendingSwitchPackageName)
                        }
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

        // 更新调试悬浮窗文案并确保显示状态
        updateOverlayText(newPackageName)
        ensureOverlayState()
        
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
        // 清理悬浮窗监听与视图
        prefsListener?.let { prefs.unregisterOnSharedPreferenceChangeListener(it) }
        removeOverlay()
    }

    private fun isOverlayEnabled(): Boolean {
        // 仅在 Debug 构建允许悬浮窗
        return BuildConfig.DEBUG && prefs.getBoolean("debug_overlay_enabled", false)
    }

    private fun isAppInForeground(): Boolean {
        return prefs.getBoolean("app_foreground", true)
    }

    private fun ensureOverlayState() {
        if (isOverlayEnabled() && !isAppInForeground()) {
            addOverlayIfNeeded()
        } else {
            removeOverlay()
        }
    }

    private fun addOverlayIfNeeded() {
        if (overlayAdded) return
        mainHandler.post {
            if (overlayAdded) return@post
            try {
                val tv = TextView(this)
                tv.text = "当前前台: 未知"
                tv.setTextColor(Color.WHITE)
                tv.textSize = 12f
                tv.setPadding(12, 8, 12, 8)
                tv.setBackgroundColor(Color.parseColor("#80000000"))

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                )
                params.gravity = Gravity.TOP or Gravity.END
                params.x = 24
                params.y = 120

                windowManager?.addView(tv, params)
                overlayView = tv
                overlayAdded = true
                Log.d(TAG, "调试悬浮窗已添加")
            } catch (e: Exception) {
                Log.e(TAG, "添加调试悬浮窗失败: ${e.message}")
            }
        }
    }

    private fun removeOverlay() {
        if (!overlayAdded) return
        mainHandler.post {
            if (!overlayAdded) return@post
            try {
                overlayView?.let { windowManager?.removeView(it) }
                overlayView = null
                overlayAdded = false
                Log.d(TAG, "调试悬浮窗已移除")
            } catch (e: Exception) {
                Log.e(TAG, "移除调试悬浮窗失败: ${e.message}")
            }
        }
    }

    private fun updateOverlayText(packageName: String?) {
        mainHandler.post {
            val tv = overlayView ?: return@post
            val text = try {
                if (packageName.isNullOrBlank()) {
                    "当前前台: 未知"
                } else {
                    val appName = packageManager.getApplicationLabel(
                        packageManager.getApplicationInfo(packageName, 0)
                    ).toString()
                    "当前前台: ${appName} (${packageName})"
                }
            } catch (e: Exception) {
                "当前前台: ${packageName ?: "未知"}"
            }
            tv.text = text
        }
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

    private fun shouldIgnorePackage(pkg: String): Boolean {
        return pkg in systemPackages || pkg in imePackages || pkg.contains("inputmethod", true)
    }

    private fun getActiveWindowPackageName(): String? {
        return try {
            val ws = windows ?: return null
            val active = ws.firstOrNull { it.isActive }
            val root = active?.root
            root?.packageName?.toString()
        } catch (e: Exception) {
            null
        }
    }

    private fun getRootActiveWindowPackageName(): String? {
        return try {
            val root = rootInActiveWindow
            root?.packageName?.toString()
        } catch (e: Exception) {
            null
        }
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
