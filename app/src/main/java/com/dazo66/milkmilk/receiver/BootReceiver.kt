package com.dazo66.milkmilk.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.dazo66.milkmilk.service.AppMonitorService

/**
 * 启动接收器，用于在设备启动完成后自动启动应用监控服务
 */
class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "设备启动完成，准备启动应用监控服务")

            // 以普通后台服务方式启动，避免在 BOOT_COMPLETED 触发前台服务限制
            val serviceIntent = Intent(context, AppMonitorService::class.java).apply {
                putExtra("start_mode", "boot")
            }
            context.startService(serviceIntent)
        }
    }
}
