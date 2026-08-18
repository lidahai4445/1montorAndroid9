package utils

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 简易日志引擎 - 用于记录系统运行事件
 */
object AppLogger {
    private const val LOG_FILE_NAME = "app_events_log.txt"
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    /**
     * 写入一条日志
     */
    fun log(context: Context, message: String) {
        try {
            val time = timeFormat.format(Date())
            val logEntry = "[$time] $message\n"
            val logFile = File(context.filesDir, LOG_FILE_NAME)
            logFile.appendText(logEntry)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 读取所有日志 (返回列表)
     */
    fun readLogs(context: Context): List<String> {
        return try {
            val logFile = File(context.filesDir, LOG_FILE_NAME)
            if (logFile.exists()) {
                logFile.readLines().reversed() // 最新的在上面
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 清空日志
     */
    fun clearLogs(context: Context) {
        try {
            val logFile = File(context.filesDir, LOG_FILE_NAME)
            if (logFile.exists()) {
                logFile.delete()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
