package com.dazo66.milkmilk.utils

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日志记录工具类
 * 将日志写入文件，支持每日滚动
 */
object LogRecorder {
    private const val TAG = "LogRecorder"
    private const val LOG_DIR_NAME = "milkmilk"
    private const val LOG_FILE_NAME = "logs.log"

    fun record(context: Context, message: String) {
        try {
            val logFile = getLogFile(context)
            if (logFile == null) {
                Log.e(TAG, "无法获取日志文件路径，放弃写入")
                return
            }
            
            Log.d(TAG, "准备写入日志到: ${logFile.absolutePath}")

            // 检查是否需要滚动（如果是新的一天）
            checkAndRollLog(logFile)
            
            val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val timestamp = timeFormat.format(Date())
            val logEntry = "$timestamp $message\n"

            FileWriter(logFile, true).use { writer ->
                writer.append(logEntry)
            }
            Log.d(TAG, "日志写入成功")
        } catch (e: Exception) {
            Log.e(TAG, "记录日志失败", e)
        }
    }

    private fun getLogFile(context: Context): File? {
        // 路径: /sdcard/Android/data/com.dazo66.milkmilk/files/logs/logs.log
        try {
            val privateLogDir = context.getExternalFilesDir("logs")
            if (privateLogDir != null) {
                if (ensureDirectoryReady(privateLogDir)) {
                    return File(privateLogDir, LOG_FILE_NAME)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "尝试访问私有目录失败", e)
        }

        return null
    }

    private fun ensureDirectoryReady(dir: File): Boolean {
        if (dir.exists()) {
            return dir.isDirectory && dir.canWrite()
        }
        return dir.mkdirs()
    }

    private fun checkAndRollLog(logFile: File) {
        if (!logFile.exists()) return

        val lastModified = logFile.lastModified()
        val lastDate = Date(lastModified)
        val now = Date()

        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val lastDateStr = fmt.format(lastDate)
        val nowDateStr = fmt.format(now)

        if (lastDateStr != nowDateStr) {
            // 需要滚动，将旧文件重命名为 logs_yyyy-MM-dd.log
            val renameTo = File(logFile.parent, "logs_$lastDateStr.log")
            
            // 如果目标文件已存在，先删除
            if (renameTo.exists()) {
                renameTo.delete()
            }
            
            if (logFile.renameTo(renameTo)) {
                Log.i(TAG, "日志已滚动归档: ${renameTo.name}")
            } else {
                Log.e(TAG, "日志滚动失败")
            }
        }
    }
}
