package com.dazo66.milkmilk

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/** 厂商未提供统一的自启动设置 API；失败时始终回退到本应用详情页。 */
object AutoStartSettings {
    fun open(context: Context) {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val components = when {
            manufacturer.contains("xiaomi") -> listOf(
                "com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity"
            )
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> listOf(
                "com.huawei.systemmanager/com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            )
            manufacturer.contains("oppo") || manufacturer.contains("realme") -> listOf(
                "com.coloros.safecenter/com.coloros.safecenter.permission.startup.StartupAppListActivity",
                "com.oppo.safe/com.oppo.safe.permission.startup.StartupAppListActivity"
            )
            manufacturer.contains("vivo") -> listOf(
                "com.vivo.permissionmanager/com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
            )
            else -> emptyList()
        }

        for (flattenedComponent in components) {
            val intent = Intent().apply {
                component = ComponentName.unflattenFromString(flattenedComponent)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                // 部分厂商 ROM 会让 resolveActivity 返回非空，但目标 Activity 实际
                // 已被裁剪或禁止启动。必须捕获真正启动时的异常，再走通用设置页。
                try {
                    context.startActivity(intent)
                    return
                } catch (_: android.content.ActivityNotFoundException) {
                    // 尝试同一厂商的下一个入口，或最终回退。
                } catch (_: SecurityException) {
                    // 厂商限制了该设置页的外部启动，改为通用应用详情页。
                }
            }
        }

        try {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (_: Exception) {
            // 所有正常 Android 设备都支持此入口；此处防御性吞掉极端 ROM 的异常，
            // 以确保设置页按钮不会再导致应用退出。
        }
    }
}
