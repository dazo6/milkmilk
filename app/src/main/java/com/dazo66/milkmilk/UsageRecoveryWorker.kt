package com.dazo66.milkmilk

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * 从 UsageEvents 重建近期已结束的会话。
 *
 * WorkManager 并非精确定时器；因此每次都回看三天并通过数据库的时间重叠校验
 * 去重。系统只承诺 UsageEvents 保留"几天"，不能把此机制当作长期离线补偿。
 */
class UsageRecoveryWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        if (!isUsageAccessGranted(applicationContext)) return Result.success()

        val monitoredPackages = applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(MONITORED_APPS_KEY, emptySet())
            ?.toSet()
            .orEmpty()
        if (monitoredPackages.isEmpty()) return Result.success()

        return try {
            val now = System.currentTimeMillis()
            val events = (applicationContext.getSystemService(Context.USAGE_STATS_SERVICE)
                    as UsageStatsManager)
                .queryEvents(now - LOOKBACK_MILLIS, now)
            val recovered = reconstructSessions(events, monitoredPackages)
            val repository = AppUsageRepository(applicationContext)
            var inserted = 0

            for (session in recovered) {
                if (!repository.hasOverlap(Date(session.startTime), Date(session.endTime))) {
                    val appName = appName(session.packageName)
                    repository.insertUsageRecord(
                        AppUsageRecord(
                            packageName = session.packageName,
                            appName = appName,
                            startTime = Date(session.startTime),
                            endTime = Date(session.endTime),
                            durationSeconds = (session.endTime - session.startTime) / 1_000,
                            date = Date(session.endTime)
                        )
                    )
                    inserted++
                }
            }
            if (inserted > 0) {
                repository.recomputeAllAggregations(monitoredPackages.toList())
            }
            Log.i(TAG, "UsageEvents 补采完成：识别 ${recovered.size} 条，写入 $inserted 条")
            Result.success(workDataOf("inserted" to inserted))
        } catch (error: Exception) {
            Log.e(TAG, "UsageEvents 补采失败", error)
            Result.retry()
        }
    }

    private fun reconstructSessions(
        events: UsageEvents,
        monitoredPackages: Set<String>
    ): List<RecoveredSession> {
        val sessions = mutableListOf<RecoveredSession>()
        val event = UsageEvents.Event()
        var foregroundPackage: String? = null
        var sessionStart = 0L

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val packageName = event.packageName ?: continue
            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND,
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    if (packageName == foregroundPackage) continue
                    closeSessionIfNeeded(sessions, foregroundPackage, sessionStart, event.timeStamp)
                    foregroundPackage = packageName
                    sessionStart = event.timeStamp
                }

                UsageEvents.Event.MOVE_TO_BACKGROUND,
                UsageEvents.Event.ACTIVITY_PAUSED -> {
                    if (packageName == foregroundPackage) {
                        closeSessionIfNeeded(sessions, packageName, sessionStart, event.timeStamp)
                        foregroundPackage = null
                        sessionStart = 0L
                    }
                }
            }
        }
        return sessions.filter { it.packageName in monitoredPackages }
    }

    private fun closeSessionIfNeeded(
        sessions: MutableList<RecoveredSession>,
        packageName: String?,
        startTime: Long,
        endTime: Long
    ) {
        if (packageName != null && endTime - startTime >= MIN_SESSION_MILLIS) {
            sessions += RecoveredSession(packageName, startTime, endTime)
        }
    }

    private fun appName(packageName: String): String = try {
        applicationContext.packageManager.getApplicationLabel(
            applicationContext.packageManager.getApplicationInfo(packageName, 0)
        ).toString()
    } catch (_: Exception) {
        packageName
    }

    private data class RecoveredSession(
        val packageName: String,
        val startTime: Long,
        val endTime: Long
    )

    companion object {
        private const val TAG = "UsageRecoveryWorker"
        private const val PREFS_NAME = "app_settings"
        private const val MONITORED_APPS_KEY = "monitored_apps"
        private const val MIN_SESSION_MILLIS = 3_000L
        private val LOOKBACK_MILLIS = TimeUnit.DAYS.toMillis(3)
    }
}

object UsageRecoveryScheduler {
    private const val PERIODIC_WORK_NAME = "usage-events-recovery-periodic"
    private const val IMMEDIATE_WORK_NAME = "usage-events-recovery-now"

    fun schedule(context: Context) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        val periodic = PeriodicWorkRequestBuilder<UsageRecoveryWorker>(15, TimeUnit.MINUTES).build()
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            periodic
        )
        val immediate = androidx.work.OneTimeWorkRequestBuilder<UsageRecoveryWorker>().build()
        workManager.enqueueUniqueWork(IMMEDIATE_WORK_NAME, ExistingWorkPolicy.KEEP, immediate)
    }
}

private fun isUsageAccessGranted(context: Context): Boolean {
    val appOpsManager = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
    return appOpsManager.checkOpNoThrow(
        android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
        android.os.Process.myUid(),
        context.packageName
    ) == android.app.AppOpsManager.MODE_ALLOWED
}
