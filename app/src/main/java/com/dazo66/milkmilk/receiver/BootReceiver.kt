package com.dazo66.milkmilk.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.dazo66.milkmilk.UsageRecoveryScheduler

/**
 * 启动接收器，用于在设备启动完成后自动启动应用监控服务
 */
class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "设备启动完成，安排使用记录补采")
            // Android 8+ 不允许可靠地从开机广播启动普通后台服务；前台服务的
            // 后台启动同样受限。先补采系统保存的 UsageEvents，实时服务在用户
            // 下次打开应用后再由明确的用户交互启动。
            UsageRecoveryScheduler.schedule(context)
        }
    }
}
