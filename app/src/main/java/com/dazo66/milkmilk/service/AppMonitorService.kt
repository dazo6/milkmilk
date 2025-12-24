package com.dazo66.milkmilk.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.dazo66.milkmilk.AppUsageRepository
import com.dazo66.milkmilk.MainActivity
import com.dazo66.milkmilk.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar
import com.dazo66.milkmilk.utils.LogRecorder

/**
 * 应用监控前台服务，确保应用在后台稳定运行
 */
class AppMonitorService : Service(), androidx.lifecycle.LifecycleOwner, androidx.lifecycle.ViewModelStoreOwner, androidx.savedstate.SavedStateRegistryOwner {
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
    private var screenReceiver: BroadcastReceiver? = null
    private var isForegroundStarted: Boolean = false
    private var startedFromBoot: Boolean = false

    // 悬浮窗相关
    private var windowManager: android.view.WindowManager? = null
    private var floatingView: android.view.View? = null
    // Compose 状态
    private var floatingTextState = androidx.compose.runtime.mutableStateOf("Waiting...")
    private var isFloatingVisible = androidx.compose.runtime.mutableStateOf(true)
    
    // Lifecycle components for ComposeView
    private val lifecycleRegistry by lazy { LifecycleRegistry(this) }
    private val savedStateRegistryController by lazy { SavedStateRegistryController.create(this) }
    private val viewModelStoreInternal by lazy { ViewModelStore() }
    
    // 配置监听
    private val prefsChangeListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == "floating_window_enabled") {
            val enabled = prefs.getBoolean(key, false)
            setFloatingWindowVisibility(enabled)
        }
    }

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = viewModelStoreInternal

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        
        Log.d(TAG, "服务创建")
        isServiceRunning = true
        repository = AppUsageRepository(this)
        
        // 注册配置监听
        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(prefsChangeListener)

        // 创建通知渠道
        createNotificationChannel()
        
        // 初始化悬浮窗
        if (prefs.getBoolean("floating_window_enabled", false)) {
            initFloatingWindow()
        }

        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        // 前台启动移动到 onStartCommand，根据启动来源（如 BOOT_COMPLETED）再决定是否提升为前台

        // 启动每日统计任务（监控逻辑挪到 onStartCommand，以便获知启动来源）
        // scheduleDailySummary()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "服务启动")
        LogRecorder.record(this, "AppMonitorService 服务启动")
        val fromBoot = intent?.getStringExtra("start_mode") == "boot"
        startedFromBoot = fromBoot
        // 非开机场景（例如应用启动或用户交互触发），立即提升为前台以保证存活
        if (!fromBoot) {
            ensureForeground()
        }
        // 在获知启动来源后启动监控逻辑，避免在 BOOT 阶段误提升前台
        startUsageFallbackMonitoring()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        Log.d(TAG, "服务销毁")
        
        getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(prefsChangeListener)
            
        isServiceRunning = false
        dailySummaryJob?.cancel()
        usageMonitorJob?.cancel()
        // 注销屏幕广播接收器
        screenReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {
            }
        }
        screenReceiver = null
        removeFloatingWindow()
    }

    /**
     * 初始化悬浮窗
     */
    private fun initFloatingWindow() {
        if (!Settings.canDrawOverlays(this)) return
        try {
            if (windowManager == null) {
                windowManager = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            }
            if (floatingView == null) {
                val layoutParams = android.view.WindowManager.LayoutParams().apply {
                    type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    } else {
                        android.view.WindowManager.LayoutParams.TYPE_PHONE
                    }
                    format = android.graphics.PixelFormat.TRANSLUCENT
                    flags = android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    width = android.view.WindowManager.LayoutParams.WRAP_CONTENT
                    height = android.view.WindowManager.LayoutParams.WRAP_CONTENT
                    gravity = android.view.Gravity.TOP or android.view.Gravity.START
                    x = 0
                    y = if(isFloatingVisible.value) 200 else -100
                }
                
                val composeView = ComposeView(this).apply {
                    setViewTreeLifecycleOwner(this@AppMonitorService)
                    setViewTreeViewModelStoreOwner(this@AppMonitorService)
                    setViewTreeSavedStateRegistryOwner(this@AppMonitorService)
                    setContent {
                        if (isFloatingVisible.value) {
                            androidx.compose.material3.MaterialTheme {
                                androidx.compose.foundation.layout.Box(
                                    modifier = Modifier
                                        .background(Color(0x99000000))
                                        .padding(8.dp)
                                ) {
                                    androidx.compose.material3.Text(
                                        text = floatingTextState.value,
                                        color = Color.White,
                                        fontSize = 14.sp
                                    )
                                }
                            }
                        }
                    }
                }
                
                floatingView = composeView
                windowManager?.addView(floatingView, layoutParams)
            }
        } catch (e: Exception) {
            Log.e(TAG, "初始化悬浮窗失败", e)
        }
    }

    /**
     * 设置悬浮窗可见性
     */
    private fun setFloatingWindowVisibility(visible: Boolean) {
        isFloatingVisible.value = visible
        
        if (visible) {
            // 如果需要显示但悬浮窗未初始化，则初始化
            removeFloatingWindow()
            updateFloatingWindow(currentForegroundPkg)
            // 更新 LayoutParams 为可交互（虽然目前没有交互逻辑，但保持正常状态）
            try {
                val params = floatingView?.layoutParams as? android.view.WindowManager.LayoutParams
                if (params != null) {
                    params.flags = params.flags and android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                    windowManager?.updateViewLayout(floatingView, params)
                }
            } catch (e: Exception) {
                Log.e(TAG, "更新悬浮窗可见性失败", e)
            }
        } else {
            // 隐藏时：更新 LayoutParams 为不可触摸，防止透明窗口阻挡操作
            try {
                removeFloatingWindow()
                updateFloatingWindow("");
                if (floatingView != null) {
                    val params = floatingView?.layoutParams as? android.view.WindowManager.LayoutParams
                    if (params != null) {
                        params.flags = params.flags or android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        windowManager?.updateViewLayout(floatingView, params)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "更新悬浮窗隐藏状态失败", e)
            }
        }
    }

    /**
     * 更新悬浮窗内容
     */
    private fun updateFloatingWindow(text: String?) {
        floatingTextState.value = text ?: "等待数据..."
        if (floatingView == null) {
            initFloatingWindow()
        }
    }

    /**
     * 移除悬浮窗
     */
    private fun removeFloatingWindow() {
        try {
            if (floatingView != null && windowManager != null) {
                windowManager?.removeView(floatingView)
                floatingView = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "移除悬浮窗失败", e)
        }
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

        // 注册屏幕事件广播，用于将锁屏视为一次应用切换边界
        if (screenReceiver == null) {
            screenReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    when (intent.action) {
                        Intent.ACTION_SCREEN_OFF -> {
                            // 屏幕关闭（通常伴随锁屏）：结束当前会话并作为切换边界
                            Log.i(TAG, "屏幕关闭，作为应用切换边界处理")
                            handleAppSwitch(null)
                        }

                        Intent.ACTION_USER_PRESENT -> {
                            // 解锁完成：无需强制切换，下一次前台包变化时会重新开始会话
                            // 同时在用户解锁后将服务提升为前台，符合平台限制
                            startedFromBoot = false
                            ensureForeground()
                        }

                        Intent.ACTION_SCREEN_ON -> {
                            // 点亮屏幕：无需处理
                        }
                    }
                }
            }
            try {
                val filter = IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_USER_PRESENT)
                    addAction(Intent.ACTION_SCREEN_ON)
                }
                registerReceiver(screenReceiver, filter)
            } catch (e: Exception) {
                Log.e(TAG, "注册屏幕广播接收器失败", e)
            }
        }

        usageMonitorJob = serviceScope.launch {
            while (isActive) {
                try {
                    // 当无障碍不可用时，使用 UsageStats 获取前台应用

                    val pkg = getForegroundAppByUsageEvents()
                    if (pkg != null && pkg != currentForegroundPkg) {
                        Log.i(TAG, "监听到应用变化$currentForegroundPkg -> $pkg")
                        LogRecorder.record(this@AppMonitorService, "监听到应用变化$currentForegroundPkg -> $pkg")
                        handleAppSwitch(pkg)
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
        
        // 更新悬浮窗
        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        if (prefs.getBoolean("floating_window_enabled", false)) {
            updateFloatingWindow(newPackageName)
        }
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
     * 确保服务以前台运行，避免重复调用
     */
    private fun ensureForeground() {
        if (isForegroundStarted) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    createNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, createNotification())
            }
            isForegroundStarted = true
        } catch (e: Exception) {
            Log.e(TAG, "提升为前台服务失败", e)
        }
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
