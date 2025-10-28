package com.dazo66.milkmilk

// Removed unused import to fix unresolved reference
// import com.dazo66.milkmilk.ui.formatDuration
import android.app.AppOpsManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import com.dazo66.milkmilk.BuildConfig
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat.startActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import com.dazo66.milkmilk.service.AppMonitorService
import com.dazo66.milkmilk.ui.StatisticsTab
import com.dazo66.milkmilk.ui.theme.MilkmilkTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 检查并请求必要权限
        checkUsageAccessPermission(this)
        checkAccessibilityEnabled(this)

        // 启动监控服务
        AppMonitorService.startService(this)

        setContent {
            MilkmilkTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Greeting(
                        name = "Android",
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}


private fun isAccessibilityEnabled(activity: ComponentActivity): Boolean {
    val serviceName: String = activity.getPackageName() + ".MyAccessibilityService"
    val enabled: Int = Settings.Secure.getInt(
        activity.getContentResolver(),
        Settings.Secure.ACCESSIBILITY_ENABLED,
        0
    )
    Log.i("isAccessibilityEnabled", enabled.toString());
    if (enabled == 1) {
        val enabledServices: String = Settings.Secure.getString(
            activity.getContentResolver(),
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        return enabledServices != null && enabledServices.contains(serviceName)
    }
    return false
}

private fun isUsageAccessGranted(context: Context): Boolean {
    val appOpsManager = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = appOpsManager.checkOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS,
        android.os.Process.myUid(),
        context.packageName
    )
    return mode == AppOpsManager.MODE_ALLOWED
}

private fun checkAccessibilityEnabled(activity: ComponentActivity) {
    if (!isAccessibilityEnabled(activity)) {
        android.app.AlertDialog.Builder(activity)
            .setTitle("需要无障碍权限")
            .setMessage("该功能需要开启无障碍服务支持")
            .setPositiveButton("立即开启") { d: DialogInterface?, w: Int ->
                startActivity(activity, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), null)
            }
            .setNegativeButton("取消", null)
            .show()
    }
}

private fun checkUsageAccessPermission(activity: ComponentActivity) {
    if (!isUsageAccessGranted(activity)) {
        android.app.AlertDialog.Builder(activity)
            .setTitle("需要使用情况访问权限")
            .setMessage("应用需要访问使用情况统计数据来记录应用使用时间")
            .setPositiveButton("立即开启") { d: DialogInterface?, w: Int ->
                startActivity(activity, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS), null)
            }
            .setNegativeButton("取消", null)
            .show()
    }
}

// ViewModel
class MainViewModel(private val context: Context) : ViewModel() {
    enum class CalendarViewType { DAY, MONTH, YEAR }
    enum class StatisticsViewType { DAY, WEEK, MONTH, YEAR }
    enum class TimeRangeFilter { TODAY, WEEK, MONTH, ALL }

    // 日历状态
    var calendarViewType by mutableStateOf(CalendarViewType.MONTH)

    // 统计视图类型
    var statisticsViewType by mutableStateOf(StatisticsViewType.DAY)

    // 时间范围筛选状态
    var timeFilter by mutableStateOf(TimeRangeFilter.TODAY)

    // 更新检查相关状态
    var updateCheckMessage by mutableStateOf("")
    var showUpdateDialog by mutableStateOf(false)
    var latestVersionName by mutableStateOf<String?>(null)
    var latestReleaseUrl by mutableStateOf<String?>(null)

    fun checkForUpdates() {
        updateCheckMessage = "正在检查更新…"
        viewModelScope.launch {
            try {
                val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val url = URL("https://api.github.com/repos/dazo6/milkmilk/releases/latest")
                    val conn = (url.openConnection() as HttpsURLConnection).apply {
                        requestMethod = "GET"
                        setRequestProperty("Accept", "application/vnd.github+json")
                        setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                        setRequestProperty("User-Agent", "milkmilk-android-app")
                        connectTimeout = 8000
                        readTimeout = 8000
                    }
                    val code = conn.responseCode
                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    val body = stream.bufferedReader().use(BufferedReader::readText)
                    conn.disconnect()
                    if (code !in 200..299) throw RuntimeException("GitHub API 响应码：$code")
                    val json = JSONObject(body)
                    val tag = json.optString("tag_name")
                    val htmlUrl = json.optString("html_url")
                    Pair(tag, htmlUrl)
                }

                val latestTag = result.first
                val releaseUrl = result.second
                if (latestTag.isNullOrBlank() || releaseUrl.isNullOrBlank()) {
                    updateCheckMessage = "检查失败：未获取到版本信息"
                    return@launch
                }


                val current = BuildConfig.VERSION_NAME
                val cmp = compareVersions(normalizeVersion(current), normalizeVersion(latestTag))
                if (cmp < 0) {
                    latestVersionName = latestTag
                    latestReleaseUrl = releaseUrl
                    showUpdateDialog = true
                    updateCheckMessage = "发现新版本：$latestTag"
                } else {
                    updateCheckMessage = "当前已是最新版本（$current）"
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "检查更新失败", e)
                updateCheckMessage = "检查失败：${e.message ?: "未知错误"}"
            }
        }
    }

    fun goToLatestReleasePage() {
        val url = latestReleaseUrl ?: return
        try {
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            showUpdateDialog = false
        } catch (e: Exception) {
            Log.e("MainViewModel", "打开更新页面失败", e)
            updateCheckMessage = "打开更新页面失败：${e.message ?: "未知错误"}"
        }
    }

    private fun normalizeVersion(v: String): String {
        return v.trim().removePrefix("v").removePrefix("V")
    }

    private fun compareVersions(a: String, b: String): Int {
        fun parse(s: String): Triple<Int, Int, Int> {
            val parts = s.split("-", limit = 2)[0].split('.')
            val x = parts.getOrNull(0)?.toIntOrNull() ?: 0
            val y = parts.getOrNull(1)?.toIntOrNull() ?: 0
            val z = parts.getOrNull(2)?.toIntOrNull() ?: 0
            return Triple(x, y, z)
        }
        val pa = parse(a)
        val pb = parse(b)
        return when {
            pa.first != pb.first -> pa.first.compareTo(pb.first)
            pa.second != pb.second -> pa.second.compareTo(pb.second)
            else -> pa.third.compareTo(pb.third)
        }
    }
    private fun getTodayRange(): Pair<Date, Date> {
        val calendar = java.util.Calendar.getInstance()
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        val startDate = calendar.time

        calendar.set(java.util.Calendar.HOUR_OF_DAY, 23)
        calendar.set(java.util.Calendar.MINUTE, 59)
        calendar.set(java.util.Calendar.SECOND, 59)
        calendar.set(java.util.Calendar.MILLISECOND, 999)
        val endDate = calendar.time

        return Pair(startDate, endDate)
    }

    private fun getWeekRange(): Pair<Date, Date> {
        val startCal = java.util.Calendar.getInstance()
        startCal.set(java.util.Calendar.DAY_OF_WEEK, startCal.firstDayOfWeek)
        startCal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        startCal.set(java.util.Calendar.MINUTE, 0)
        startCal.set(java.util.Calendar.SECOND, 0)
        startCal.set(java.util.Calendar.MILLISECOND, 0)
        val startDate = startCal.time

        val endCal = java.util.Calendar.getInstance()
        endCal.set(java.util.Calendar.DAY_OF_WEEK, endCal.firstDayOfWeek)
        endCal.add(java.util.Calendar.DAY_OF_WEEK, 6)
        endCal.set(java.util.Calendar.HOUR_OF_DAY, 23)
        endCal.set(java.util.Calendar.MINUTE, 59)
        endCal.set(java.util.Calendar.SECOND, 59)
        endCal.set(java.util.Calendar.MILLISECOND, 999)
        val endDate = endCal.time

        return Pair(startDate, endDate)
    }

    private fun getMonthRange(): Pair<Date, Date> {
        val startCal = java.util.Calendar.getInstance()
        startCal.set(java.util.Calendar.DAY_OF_MONTH, 1)
        startCal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        startCal.set(java.util.Calendar.MINUTE, 0)
        startCal.set(java.util.Calendar.SECOND, 0)
        startCal.set(java.util.Calendar.MILLISECOND, 0)
        val startDate = startCal.time

        val endCal = java.util.Calendar.getInstance()
        endCal.set(
            java.util.Calendar.DAY_OF_MONTH,
            endCal.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
        )
        endCal.set(java.util.Calendar.HOUR_OF_DAY, 23)
        endCal.set(java.util.Calendar.MINUTE, 59)
        endCal.set(java.util.Calendar.SECOND, 59)
        endCal.set(java.util.Calendar.MILLISECOND, 999)
        val endDate = endCal.time

        return Pair(startDate, endDate)
    }

    fun updateTimeFilter(filter: TimeRangeFilter) {
        timeFilter = filter
        val range = when (filter) {
            TimeRangeFilter.TODAY -> getTodayRange()
            TimeRangeFilter.WEEK -> getWeekRange()
            TimeRangeFilter.MONTH -> getMonthRange()
            TimeRangeFilter.ALL -> Pair(Date(0), Date(Long.MAX_VALUE))
        }
        timeRangeFlow.value = range
    }

    private val timeRangeFlow = MutableStateFlow(getTodayRange())
    private val monitoredPackagesFlow = MutableStateFlow<List<String>>(emptyList())
    val pagedRecords: Flow<PagingData<AppUsageRecord>> =
        combine(timeRangeFlow, monitoredPackagesFlow) { range, packages ->
            Triple(range.first, range.second, packages)
        }.flatMapLatest { (start, end, packages) ->
            Pager(
                config = PagingConfig(pageSize = 20, enablePlaceholders = false),
                pagingSourceFactory = { repository.getPagedUsageRecords(start, end, packages) }
            ).flow
        }.cachedIn(viewModelScope)


    // 设置项
    var showAppDialog by mutableStateOf(false)
    var threshold1 by mutableIntStateOf(50)
    var threshold2 by mutableIntStateOf(100)
    var debugOverlayEnabled by mutableStateOf(false)

    // 监控的应用列表
    val monitoredApps = mutableStateListOf<AppInfo>()

    // 已安装的应用列表
    val installedApps = mutableStateListOf<AppInfo>().apply {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        addAll(apps.map {
            AppInfo(
                packageName = it.packageName,
                name = it.loadLabel(pm).toString()
            )
        }.sortedBy { it.name })
    }

    // 数据仓库
    private val repository = AppUsageRepository(context)

    // 行为统计的数据
    var dailyBehaviorStats by mutableStateOf<List<DailyBehaviorStats>>(emptyList())
    var selectedDayCount by mutableIntStateOf(0)
    var selectedDate by mutableStateOf<Date?>(null)
    var selectedWeek by mutableStateOf<Pair<Int, Int>?>(null) // year, weekOfYear
    var selectedMonth by mutableStateOf<Pair<Int, Int>?>(null) // year, month0Based
    var showDayDetailDialog by mutableStateOf(false)
    var showWeekDetailDialog by mutableStateOf(false)
    var showMonthDetailDialog by mutableStateOf(false)

    var selectedDayBehaviors by mutableStateOf<List<ContinuousBehavior>>(emptyList())
    var selectedRangeBehaviors by mutableStateOf<List<ContinuousBehavior>>(emptyList())

    // 删除相关状态
    var showDeleteConfirmDialog by mutableStateOf(false)
    var recordToDelete by mutableStateOf<AppUsageRecord?>(null)
    var showClearAllConfirmDialog by mutableStateOf(false)
    var importExportMessage by mutableStateOf("")

    // 获取今日会话记录（用于事件页）
    fun getTodayUsageRecords(): LiveData<List<AppUsageRecord>> {
        val calendar = java.util.Calendar.getInstance()
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        val startDate = calendar.time

        calendar.set(java.util.Calendar.HOUR_OF_DAY, 23)
        calendar.set(java.util.Calendar.MINUTE, 59)
        calendar.set(java.util.Calendar.SECOND, 59)
        val endDate = calendar.time

        return repository.getUsageRecordsByDateRange(startDate, endDate)
    }


    // 添加监控应用
    fun addMonitoredApp(app: AppInfo) {
        if (!monitoredApps.contains(app)) {
            monitoredApps.add(app)
            // 更新无障碍服务中的监控列表
            MyAccessibilityService.monitoredApps.add(app.packageName)
            saveMonitoredApps()
            // 触发分页筛选更新
            monitoredPackagesFlow.value = monitoredApps.map { it.packageName }
        }
    }

    // 移除监控应用
    fun removeMonitoredApp(app: AppInfo) {
        if (monitoredApps.contains(app)) {
            monitoredApps.remove(app)
            MyAccessibilityService.monitoredApps.remove(app.packageName)
            saveMonitoredApps()
            // 触发分页筛选更新
            monitoredPackagesFlow.value = monitoredApps.map { it.packageName }
        }
    }

    // 保存监控应用列表到SharedPreferences
    private fun saveMonitoredApps() {
        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        prefs.edit().putStringSet(
            "monitored_apps",
            monitoredApps.map { it.packageName }.toSet()
        ).apply()
    }

    // 加载监控应用列表
    init {
        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val savedApps = prefs.getStringSet("monitored_apps", setOf()) ?: setOf()

        // 恢复阈值配置（如果已保存）
        threshold1 = prefs.getInt("threshold1", threshold1)
        threshold2 = prefs.getInt("threshold2", threshold2)
        debugOverlayEnabled = prefs.getBoolean("debug_overlay_enabled", false)

        // 加载已保存的监控应用
        savedApps.forEach { packageName ->
            installedApps.find { it.packageName == packageName }?.let {
                monitoredApps.add(it)
            }
        }
        // 初始化筛选的包名集合
        monitoredPackagesFlow.value = monitoredApps.map { it.packageName }


        // 更新无障碍服务中的设置
        MyAccessibilityService.monitoredApps.clear()
        MyAccessibilityService.monitoredApps.addAll(savedApps)


        // 监听聚合更新事件：收到后刷新首页统计
        viewModelScope.launch {
            AggregationEvents.updates.collect {
                loadBehaviorStats()
            }
        }
    }

    fun saveDebugOverlayEnabled(context: Context) {
        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("debug_overlay_enabled", debugOverlayEnabled).apply()
    }

    // 保存阈值设置
    fun saveThresholds(context: Context) {
        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        prefs.edit()
            .putInt("threshold1", threshold1)
            .putInt("threshold2", threshold2)
            .apply()

    }

    // 加载行为统计数据
    fun loadBehaviorStats() {
        // 加载统计范围：从“上一年年初”到“当前日期”，确保月视图跨两年时上一年所有月份都有统计
         val now = java.util.Calendar.getInstance()
         val startCal = java.util.Calendar.getInstance()
         startCal.set(java.util.Calendar.YEAR, now.get(java.util.Calendar.YEAR) - 1)
         startCal.set(java.util.Calendar.MONTH, java.util.Calendar.JANUARY)
         startCal.set(java.util.Calendar.DAY_OF_MONTH, 1)
         startCal.set(java.util.Calendar.HOUR_OF_DAY, 0)
         startCal.set(java.util.Calendar.MINUTE, 0)
         startCal.set(java.util.Calendar.SECOND, 0)
         startCal.set(java.util.Calendar.MILLISECOND, 0)
         val startDate = startCal.time
         val endDate = now.time
 
         val monitoredPackages = monitoredApps.map { it.packageName }
 
         // 使用协程加载数据（IO线程），主线程仅回写状态
         viewModelScope.launch {
             try {
                 val minSeconds = threshold2.toLong()
                 val stats = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                     repository.getDailyBehaviorStatsFromAggregated(startDate, endDate, minSeconds)
                 }
                 dailyBehaviorStats = stats
             } catch (e: Exception) {
                 Log.e("MainViewModel", "加载行为统计数据失败", e)
             }
         }
    }

    // 新增：应用打开时的增量刷新（超过3分钟）并记录最近打开时间
    fun maybeIncrementalRefreshOnAppOpen() {
        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val lastOpenMillis = prefs.getLong("last_open_time", 0L)
        val nowMillis = java.util.Calendar.getInstance().timeInMillis
        val diffMillis = nowMillis - lastOpenMillis
        val threeMinutesMillis = 3 * 60 * 1000L
        if (lastOpenMillis == 0L || diffMillis >= threeMinutesMillis) {
            val lastOpenDate = if (lastOpenMillis == 0L) java.util.Date(nowMillis) else java.util.Date(lastOpenMillis)
            val monitoredPkgs = monitoredApps.map { it.packageName }
            viewModelScope.launch {
                try {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        repository.incrementalUpdateAround(lastOpenDate, monitoredPkgs)
                    }
                } catch (e: Exception) {
                    Log.e("MainViewModel", "增量刷新失败", e)
                } finally {
                    // 刷新首页统计数据
                    loadBehaviorStats()
                    // 更新最近打开时间
                    prefs.edit().putLong("last_open_time", nowMillis).apply()
                }
            }
        } else {
            // 未超过3分钟，仅记录最新打开时间
            prefs.edit().putLong("last_open_time", nowMillis).apply()
        }
    }

    // 新增：首页手动下拉触发的增量刷新
    fun manualIncrementalRefresh(onDone: (() -> Unit)? = null) {
        val monitoredPkgs = monitoredApps.map { it.packageName }
        val now = java.util.Date()
        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        viewModelScope.launch {
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    repository.incrementalUpdateAround(now, monitoredPkgs)
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "手动增量刷新失败", e)
            } finally {
                // 刷新统计视图
                loadBehaviorStats()
                // 记录最近打开时间
                prefs.edit().putLong("last_open_time", java.util.Calendar.getInstance().timeInMillis).apply()
                onDone?.invoke()
            }
        }
    }
    fun onDayClick(date: java.util.Date, count: Int) {
        selectedDate = date
        selectedDayCount = count
        showDayDetailDialog = true
        // 从聚合表读取当天行为详情
        viewModelScope.launch {
            try {
                val minSeconds = threshold2.toLong()
                val behaviors = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    repository.getAggregatedBehaviorsForDate(date, minSeconds)
                }
                selectedDayBehaviors = behaviors
                selectedDayCount = behaviors.size
            } catch (e: Exception) {
                Log.e("MainViewModel", "加载当天行为详情失败", e)
                selectedDayBehaviors = emptyList()
                selectedDayCount = count
            }
        }
    }

    fun onWeekClick(year: Int, weekOfYear: Int) {
        selectedWeek = Pair(year, weekOfYear)
        val cal = java.util.Calendar.getInstance()
        cal.clear()
        cal.set(java.util.Calendar.YEAR, year)
        cal.set(java.util.Calendar.WEEK_OF_YEAR, weekOfYear)
        cal.set(java.util.Calendar.DAY_OF_WEEK, java.util.Calendar.MONDAY)
        val start = cal.time
        cal.add(java.util.Calendar.DAY_OF_MONTH, 6)
        val end = cal.time
        // 从聚合表读取周范围行为详情
        viewModelScope.launch {
            try {
                val minSeconds = threshold2.toLong()
                val behaviors = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    repository.getAggregatedBehaviorsByRange(start, end, minSeconds)
                }
                selectedRangeBehaviors = behaviors
                showWeekDetailDialog = true
            } catch (e: Exception) {
                Log.e("MainViewModel", "加载周行为详情失败", e)
                selectedRangeBehaviors = emptyList()
                showWeekDetailDialog = true
            }
        }
    }

    fun onMonthClick(year: Int, month0Based: Int) {
        selectedMonth = Pair(year, month0Based)
        val cal = java.util.Calendar.getInstance()
        cal.clear()
        cal.set(java.util.Calendar.YEAR, year)
        cal.set(java.util.Calendar.MONTH, month0Based)
        cal.set(java.util.Calendar.DAY_OF_MONTH, 1)
        val start = cal.time
        cal.add(java.util.Calendar.MONTH, 1)
        cal.add(java.util.Calendar.DAY_OF_MONTH, -1)
        val end = cal.time
        // 从聚合表读取月范围行为详情
        viewModelScope.launch {
            try {
                val minSeconds = threshold2.toLong()
                val behaviors = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    repository.getAggregatedBehaviorsByRange(start, end, minSeconds)
                }
                selectedRangeBehaviors = behaviors
                showMonthDetailDialog = true
            } catch (e: Exception) {
                Log.e("MainViewModel", "加载月行为详情失败", e)
                selectedRangeBehaviors = emptyList()
                showMonthDetailDialog = true
            }
        }
    }

    // 导出会话数据到外部文件目录（sessions_export.json）
    suspend fun exportSessionsToFile(): String {
        val records =
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { repository.getAllRecordsDirect() }
        val jsonArray = org.json.JSONArray()
        records.forEach { r ->
            val obj = org.json.JSONObject()
            obj.put("packageName", r.packageName)
            obj.put("appName", r.appName)
            obj.put("startTime", r.startTime.time)
            obj.put("endTime", r.endTime.time)
            obj.put("durationSeconds", r.durationSeconds)
            obj.put("date", r.date.time)
            jsonArray.put(obj)
        }
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val file = java.io.File(dir, "sessions_export.json")
        file.writeText(jsonArray.toString())
        return file.absolutePath
    }
 
    // 导出会话数据到用户自选位置（SAF Uri）
    suspend fun exportSessionsToUri(uri: android.net.Uri): Boolean {
        return try {
            val records = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { repository.getAllRecordsDirect() }
            val jsonArray = org.json.JSONArray()
            records.forEach { r ->
                val obj = org.json.JSONObject()
                obj.put("packageName", r.packageName)
                obj.put("appName", r.appName)
                obj.put("startTime", r.startTime.time)
                obj.put("endTime", r.endTime.time)
                obj.put("durationSeconds", r.durationSeconds)
                obj.put("date", r.date.time)
                jsonArray.put(obj)
            }
            val output = context.contentResolver.openOutputStream(uri) ?: return false
            output.bufferedWriter().use { it.write(jsonArray.toString()) }
            true
        } catch (e: Exception) {
            false
        }
    }

     // 从外部文件目录导入会话数据（sessions_export.json），检测时间重叠
     suspend fun importSessionsFromFile(): Pair<Int, Int> {
         val dir = context.getExternalFilesDir(null) ?: context.filesDir
         val file = java.io.File(dir, "sessions_export.json")
         if (!file.exists()) return 0 to 0
         val text = file.readText()
         val arr = org.json.JSONArray(text)
         var success = 0
         var fail = 0
         for (i in 0 until arr.length()) {
             val o = arr.getJSONObject(i)
             val record = AppUsageRecord(
                 packageName = o.getString("packageName"),
                 appName = o.getString("appName"),
                 startTime = java.util.Date(o.getLong("startTime")),
                 endTime = java.util.Date(o.getLong("endTime")),
                 durationSeconds = o.getLong("durationSeconds"),
                 date = java.util.Date(o.getLong("date"))
             )
             val overlap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                 repository.hasOverlap(
                     record.startTime,
                     record.endTime
                 )
             }
             if (overlap) {
                 fail++
             } else {
                 try {
                     kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                         repository.insertUsageRecord(
                             record
                         )
                     }
                     success++
                 } catch (e: Exception) {
                     fail++
                 }
             }
         }
         return success to fail
     }

    // 通过文件选择器的Uri导入会话数据
    suspend fun importSessionsFromUri(uri: android.net.Uri): Pair<Int, Int> {
        val input = context.contentResolver.openInputStream(uri) ?: return 0 to 0
        val text = input.bufferedReader().use { it.readText() }
        val arr = org.json.JSONArray(text)
        var success = 0
        var fail = 0
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val record = AppUsageRecord(
                packageName = o.getString("packageName"),
                appName = o.getString("appName"),
                startTime = java.util.Date(o.getLong("startTime")),
                endTime = java.util.Date(o.getLong("endTime")),
                durationSeconds = o.getLong("durationSeconds"),
                date = java.util.Date(o.getLong("date"))
            )
            val overlap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                repository.hasOverlap(
                    record.startTime,
                    record.endTime
                )
            }
            if (overlap) {
                fail++
            } else {
                try {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        repository.insertUsageRecord(
                            record
                        )
                    }
                    success++
                } catch (e: Exception) {
                    fail++
                }
            }
        }
        // 导入完成后触发全量重算并批量落库
        val monitoredPackages = monitoredApps.map { it.packageName }
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                repository.recomputeAllAggregations(monitoredPackages)
            }
        } catch (e: Exception) {
            Log.e("MainViewModel", "全量重算失败", e)
        }
        return success to fail
    }

    // 长按删除记录
    fun onRecordLongPress(record: AppUsageRecord) {
        recordToDelete = record
        showDeleteConfirmDialog = true
    }

    // 确认删除单条记录
    suspend fun deleteRecord() {
        recordToDelete?.let { record ->
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    repository.deleteRecord(record.id)
                }
                // 删除完成后：触发局部重算（聚合取上下7天，仅写入后3天）
                val monitoredPkgs = monitoredApps.map { it.packageName }
                repository.incrementalUpdateAround(record.startTime, monitoredPkgs)
                // 立即刷新首页统计，避免等待事件总线
                loadBehaviorStats()
            } catch (e: Exception) {

            }
        }
        showDeleteConfirmDialog = false
        recordToDelete = null
    }

    // 显示清空全部数据确认对话框
    fun showClearAllDialog() {
        showClearAllConfirmDialog = true
    }

    // 确认清空全部数据
    suspend fun clearAllData() {
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                repository.deleteAllRecords()
            }
            importExportMessage = "已清空全部数据"
        } catch (e: Exception) {
            importExportMessage = "清空失败：${e.message}"
        }
        showClearAllConfirmDialog = false
    }

    // 取消删除操作
    fun cancelDelete() {
        showDeleteConfirmDialog = false
        showClearAllConfirmDialog = false
        recordToDelete = null
    }
}


// 数据类
data class Event(val title: String, val description: String)
data class AppInfo(val packageName: String, val name: String)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Greeting(name: String, modifier: Modifier = Modifier, viewModel: MainViewModel? = null) {

    val vm: MainViewModel = viewModel ?: androidx.lifecycle.viewmodel.compose.viewModel(
        factory = MainViewModelFactory(context = LocalContext.current.applicationContext)
    )

    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 3 })
    val coroutineScope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    // 应用从后台恢复时：如果距离上次打开超过3分钟，触发增量刷新
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                vm.maybeIncrementalRefreshOnAppOpen()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        // Tab导航栏
        TabRow(
            selectedTabIndex = pagerState.currentPage,
        ) {
            listOf("统计", "事件", "设置").forEachIndexed { index, title ->
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = { coroutineScope.launch { pagerState.animateScrollToPage(index) } },
                    text = { Text(title) }
                )
            }
        }

        // 页面内容
        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
             when (page) {
                 0 -> StatisticsTab(vm)
                 +1 -> EventListTab(vm)
                 2 -> SettingsTab(vm)
             }
         }
    }

}

// 事件列表（Legacy，已由新的分页版本替代）
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EventListTabLegacy(viewModel: MainViewModel) {
    // 今日全部记录
    val allRecords by viewModel.getTodayUsageRecords().observeAsState(emptyList())
    // 监控应用包名集合（实时计算避免 remember 导致的不同步问题）
    val monitoredPackages = viewModel.monitoredApps.map { app -> app.packageName }.toSet()
    // 若未选择监控应用则显示全部记录；否则仅保留监控应用记录
    val records = allRecords
        .filter { record -> monitoredPackages.isEmpty() || monitoredPackages.contains(record.packageName) }
        .sortedByDescending { record -> record.startTime }

    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val dateFormatter = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
    val coroutineScope = rememberCoroutineScope()

    // 删除确认对话框
    if (viewModel.showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelDelete() },
            title = { Text("确认删除") },
            text = { Text("确定要删除这条会话记录吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        coroutineScope.launch {
                            viewModel.deleteRecord()
                        }
                    }
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelDelete() }) {
                    Text("取消")
                }
            }
        )
    }

    if (records.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "暂无会话记录。请确保已开启无障碍与使用情况访问权限，并已选择监控的应用。",
                modifier = Modifier.padding(16.dp)
            )
        }
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(records.size) { index ->
                val r = records[index]
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                        .combinedClickable(
                            onLongClick = {
                                viewModel.onRecordLongPress(r)
                            }
                        ) {},
                    elevation = CardDefaults.cardElevation()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(r.appName.ifEmpty { r.packageName }, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("发生日期：${dateFormatter.format(r.startTime)}")
                        Text("开始时间：${timeFormatter.format(r.startTime)}")
                        Spacer(modifier = Modifier.height(4.dp))
                        // 会话时长以"x分x秒"显示（durationSeconds为秒）
                        Text("会话时长：${r.durationSeconds / 60}分${r.durationSeconds % 60}秒")
                    }
                }
            }
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "已经到最后了",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

/*    // 显示删除结果消息
    if (viewModel.deleteMessage.isNotEmpty()) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = viewModel.deleteMessage,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }*/
}

// Factory for ViewModel
class MainViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return MainViewModel(context) as T
    }
}

// 其他组件
@Composable
fun NumberInput(label: String, value: Int, onValueChange: (Int) -> Unit) {
    var text by remember { mutableStateOf(value.toString()) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$label:", modifier = Modifier.weight(1f))
        TextField(
            value = text,
            onValueChange = {
                text = it.filter { c -> c.isDigit() }
                onValueChange(text.toIntOrNull() ?: 0)
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(120.dp)
        )
    }
}

@Composable
fun SettingsTab(viewModel: MainViewModel) {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    // 获取当前 Activity 的生命周期
    val lifecycleOwner = LocalLifecycleOwner.current
    // 用于更新按钮
    var isForeground by remember { mutableStateOf(false) }
    // 导出/导入所需协程与消息
    val scope = rememberCoroutineScope()

    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    viewModel.importExportMessage = ""
                    val (success, fail) = viewModel.importSessionsFromUri(uri)
                    viewModel.importExportMessage =
                        "导入完成：成功${success}条，失败${fail}条（时间冲突或写入错误）"
                } catch (e: Exception) {
                    viewModel.importExportMessage = "导入失败：${e.message}"
                }
            }
        }
    }
    // 使用最新的 Lifecycle 事件处理 API
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    isForeground = !isAccessibilityEnabled(activity)
                    // 记录应用当前处于前台
                    val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
                    prefs.edit().putBoolean("app_foreground", true).apply()
                    // 这里触发你的状态更新逻辑
                    Log.d("Lifecycle", "应用进入前台")
                }

                Lifecycle.Event.ON_STOP -> {
                    isForeground = !isAccessibilityEnabled(activity)
                    // 记录应用当前退到后台
                    val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
                    prefs.edit().putBoolean("app_foreground", false).apply()
                    Log.d("Lifecycle", "应用退到后台")
                }

                else -> {}
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Column(modifier = Modifier
        .padding(16.dp)
        .verticalScroll(rememberScrollState())) {
        // 无障碍服务设置
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("基本设置", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("开启无障碍权限:", modifier = Modifier.weight(1f))
                    Button(
                        onClick = { checkAccessibilityEnabled(activity) },
                        enabled = isForeground
                    ) {
                        Text("点击开启")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "无障碍服务用于监控应用使用情况，必须开启才能正常工作",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 阈值设置
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("使用时间阈值设置", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(modifier = Modifier.height(16.dp))

                NumberInput(
                    label = "连续行为判定间隔(秒)",
                    value = viewModel.threshold1,
                    onValueChange = {
                        viewModel.threshold1 = it
                        viewModel.saveThresholds(context)
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
                NumberInput(
                    label = "行为时长判定阈值(秒)",
                    value = viewModel.threshold2,
                    onValueChange = {
                        viewModel.threshold2 = it
                        viewModel.saveThresholds(context)
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }


        // 会话数据管理（导出/导入）
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("事件管理", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(
                        12.dp
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
                    ) { uri ->
                        if (uri != null) {
                            scope.launch {
                                viewModel.importExportMessage = ""
                                val ok = viewModel.exportSessionsToUri(uri)
                                viewModel.importExportMessage = if (ok) "导出成功：已保存到选择的位置" else "导出失败：无法写入文件"
                            }
                        }
                    }
                    Button(
                        onClick = { exportLauncher.launch("sessions_export.json") },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("导出事件数据")
                    }
                    Button(
                        onClick = {
                            importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("导入事件数据")
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { viewModel.showClearAllDialog() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text("清空全部数据")
                }
                if (viewModel.importExportMessage.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(viewModel.importExportMessage)
                }
            }
        }

        // 监控应用设置
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("监控应用设置", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(modifier = Modifier.height(16.dp))

                // 已选择的监控应用列表
                Text("已选择的监控应用:", fontSize = 14.sp)
                Spacer(modifier = Modifier.height(8.dp))

                if (viewModel.monitoredApps.isEmpty()) {
                    Text(
                        "暂无监控应用，请点击下方按钮添加",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                    ) {
                        items(viewModel.monitoredApps.size) { index ->
                            val app = viewModel.monitoredApps[index]
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(app.name, modifier = Modifier.weight(1f))
                                IconButton(onClick = { viewModel.removeMonitoredApp(app) }) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "删除"
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = { viewModel.showAppDialog = true },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "添加应用"
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("添加监控应用")
                }
            }
        }

        // 版本与更新
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("版本与更新", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Text("当前版本：${BuildConfig.VERSION_NAME}")
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { viewModel.checkForUpdates() }) { Text("检查更新") }
                    Spacer(modifier = Modifier.width(12.dp))
                    if (viewModel.updateCheckMessage.isNotEmpty()) {
                        Text(viewModel.updateCheckMessage)
                    }
                }
            }
        }



        // 调试功能（仅 Debug 构建显示）
        if (BuildConfig.DEBUG) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("调试功能", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("开启调试悬浮窗:", modifier = Modifier.weight(1f))
                        androidx.compose.material3.Switch(
                            checked = viewModel.debugOverlayEnabled,
                            onCheckedChange = {
                                viewModel.debugOverlayEnabled = it
                                viewModel.saveDebugOverlayEnabled(context)
                            }
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "开启后，当本应用退到后台时，会在屏幕角落显示一个小窗，显示当前前台应用包名。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }


        // 更新提示对话框
        if (viewModel.showUpdateDialog && viewModel.latestVersionName != null) {
            AlertDialog(
                onDismissRequest = { viewModel.showUpdateDialog = false },
                title = { Text("发现新版本：${viewModel.latestVersionName}") },
                text = { Text("当前版本：${BuildConfig.VERSION_NAME}\n是否前往下载更新？") },
                confirmButton = {
                    TextButton(onClick = { viewModel.goToLatestReleasePage() }) {
                        Text("去更新")
                    }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = { viewModel.showUpdateDialog = false }) { Text("忽略此版本") }
                    }
                }
            )
        }

        // 应用选择对话框
        if (viewModel.showAppDialog) {
            AlertDialog(
                onDismissRequest = { viewModel.showAppDialog = false },
                title = { Text("选择应用") },
                text = {
                    LazyColumn {
                        items(viewModel.installedApps.size) { index ->
                            val app = viewModel.installedApps[index]
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.addMonitoredApp(app)
                                        viewModel.showAppDialog = false
                                    }
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(app.name)
                            }
                        }
                    }
                },
                confirmButton = { },
                dismissButton = {
                    TextButton(onClick = { viewModel.showAppDialog = false }) {
                        Text("取消")
                    }
                }
            )
        }

        // 清空全部数据确认对话框
        if (viewModel.showClearAllConfirmDialog) {
            AlertDialog(
                onDismissRequest = { viewModel.cancelDelete() },
                title = { Text("确认清空") },
                text = { Text("确定要清空全部会话数据吗？此操作不可恢复！") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            scope.launch {
                                viewModel.clearAllData()
                            }
                        }
                    ) {
                        Text("清空")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.cancelDelete() }) {
                        Text("取消")
                    }
                }
            )
        }

/*        // 显示删除结果消息
        if (viewModel.deleteMessage.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = viewModel.deleteMessage,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }*/
    }
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    MilkmilkTheme {
        Greeting("Android")
    }
}


// 新的分页版事件列表
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EventListTab(viewModel: MainViewModel) {
    val lazyItems = viewModel.pagedRecords.collectAsLazyPagingItems()
    val timeFormatter =
        remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val dateFormatter =
        remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
    val coroutineScope = rememberCoroutineScope()

    // 删除确认对话框
    if (viewModel.showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelDelete() },
            title = { Text("确认删除") },
            text = { Text("确定要删除这条会话记录吗？") },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        coroutineScope.launch { viewModel.deleteRecord() }
                    }
                ) { Text("删除") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { viewModel.cancelDelete() }) {
                    Text("取消")
                }
            }
        )
    }

    Column(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
        // 顶部时间筛选
        Row(
            modifier = androidx.compose.ui.Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            val selected = viewModel.timeFilter
            androidx.compose.material3.TextButton(onClick = {
                viewModel.updateTimeFilter(
                    MainViewModel.TimeRangeFilter.TODAY
                )
            }) {
                val color =
                    if (selected == MainViewModel.TimeRangeFilter.TODAY) MaterialTheme.colorScheme.primary else androidx.compose.material3.MaterialTheme.colorScheme.onSurface
                Text("今天", color = color)
            }
            Spacer(modifier = androidx.compose.ui.Modifier.width(8.dp))
            androidx.compose.material3.TextButton(onClick = {
                viewModel.updateTimeFilter(
                    MainViewModel.TimeRangeFilter.WEEK
                )
            }) {
                val color =
                    if (selected == MainViewModel.TimeRangeFilter.WEEK) MaterialTheme.colorScheme.primary else androidx.compose.material3.MaterialTheme.colorScheme.onSurface
                Text("本周", color = color)
            }
            Spacer(modifier = androidx.compose.ui.Modifier.width(8.dp))
            androidx.compose.material3.TextButton(onClick = {
                viewModel.updateTimeFilter(
                    MainViewModel.TimeRangeFilter.MONTH
                )
            }) {
                val color =
                    if (selected == MainViewModel.TimeRangeFilter.MONTH) MaterialTheme.colorScheme.primary else androidx.compose.material3.MaterialTheme.colorScheme.onSurface
                Text("本月", color = color)
            }
            Spacer(modifier = androidx.compose.ui.Modifier.width(8.dp))
            androidx.compose.material3.TextButton(onClick = {
                viewModel.updateTimeFilter(
                    MainViewModel.TimeRangeFilter.ALL
                )
            }) {
                val color =
                    if (selected == MainViewModel.TimeRangeFilter.ALL) MaterialTheme.colorScheme.primary else androidx.compose.material3.MaterialTheme.colorScheme.onSurface
                Text("全部", color = color)
            }
        }

        // 内容区
        when (val state = lazyItems.loadState.refresh) {
            is androidx.paging.LoadState.Loading -> {
                Column(
                    modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
                ) {
                    androidx.compose.material3.CircularProgressIndicator()
                    Spacer(modifier = androidx.compose.ui.Modifier.height(12.dp))
                    Text("正在加载…")
                }
            }

            is androidx.paging.LoadState.Error -> {
                Column(
                    modifier = androidx.compose.ui.Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                ) {
                    Text("加载失败：${state.error.message ?: "未知错误"}")
                    Spacer(modifier = androidx.compose.ui.Modifier.height(8.dp))
                    androidx.compose.material3.Button(onClick = { lazyItems.retry() }) {
                        Text(
                            "重试"
                        )
                    }
                }
            }

            else -> {
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = androidx.compose.ui.Modifier.fillMaxSize()
                ) {
                    items(lazyItems.itemCount) { index ->
                        val record = lazyItems[index]
                        if (record != null) {
                            androidx.compose.material3.Card(
                                modifier = androidx.compose.ui.Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                colors = androidx.compose.material3.CardDefaults.cardColors(
                                    containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Column(modifier = androidx.compose.ui.Modifier.padding(12.dp)) {
                                    Text(
                                        text = record.appName,
                                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                    )
                                    Spacer(modifier = androidx.compose.ui.Modifier.height(6.dp))
                                    Text(
                                        text = "发生日期：${dateFormatter.format(record.date)}",
                                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = androidx.compose.ui.Modifier.height(4.dp))
                                    Text(
                                        text = "开始时间：${timeFormatter.format(record.startTime)}",
                                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = androidx.compose.ui.Modifier.height(4.dp))
                                    val durSec = record.durationSeconds.toInt()
                                    Row(
                                        modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "会话时长：${durSec / 60}分${durSec % 60}秒",
                                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = androidx.compose.ui.Modifier.weight(1f)
                                        )
                                        androidx.compose.material3.TextButton(onClick = {
                                            viewModel.recordToDelete = record
                                            viewModel.showDeleteConfirmDialog = true
                                        }) { Text("删除") }
                                    }
                                }
                            }
                        }
                    }

                    // 到达底部提示
                    item {
                        if (lazyItems.loadState.append is androidx.paging.LoadState.NotLoading) {
                            androidx.compose.foundation.layout.Box(
                                modifier = androidx.compose.ui.Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = androidx.compose.ui.Alignment.Center
                            ) {
                                Text(
                                    text = "已经到最后了",
                                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else if (lazyItems.loadState.append is androidx.paging.LoadState.Loading) {
                            androidx.compose.foundation.layout.Box(
                                modifier = androidx.compose.ui.Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = androidx.compose.ui.Alignment.Center
                            ) {
                                androidx.compose.material3.CircularProgressIndicator()
                            }
                        } else if (lazyItems.loadState.append is androidx.paging.LoadState.Error) {
                            androidx.compose.foundation.layout.Box(
                                modifier = androidx.compose.ui.Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = androidx.compose.ui.Alignment.Center
                            ) {
                                androidx.compose.material3.TextButton(onClick = { lazyItems.retry() }) {
                                    Text("加载更多失败，重试")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
