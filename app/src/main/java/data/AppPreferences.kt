package data

import android.content.Context
import android.content.SharedPreferences

/**
 * 录音场景模式
 */
enum class RecordingMode {
    INDOOR,     // 室内：灵敏度高，过滤短
    OUTDOOR,    // 户外：抗噪强，过滤长
    AUTO,       // 自动：自适应均衡
    MANUAL      // 手动：完全自定义
}

/**
 * 轻量级数据持久化
 */
class AppPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("smart_recorder_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_STORAGE_URI = "storage_uri"
        private const val KEY_CLEANUP_DAYS = "cleanup_days"
        private const val KEY_TRIGGER_OFFSET = "trigger_offset"
        private const val KEY_TRACKING_SPEED = "tracking_speed"
        private const val KEY_FALL_SPEED = "fall_speed"
        private const val KEY_RECORDING_MODE = "recording_mode"
        
        // 新增设置项
        private const val KEY_STOP_EVAL_DELAY_MS = "stop_eval_delay_ms"
        private const val KEY_MIN_SAVE_DURATION_MS = "min_save_duration_ms"
        private const val KEY_IS_ALERT_MODE_ENABLED = "is_alert_mode_enabled"
        private const val KEY_ALERT_TIME_THRESHOLD_MS = "alert_time_threshold_ms"
        private const val KEY_LAST_VALID_RECORD_TIME = "last_valid_record_time"
    }

    fun saveStorageUri(uriString: String) {
        prefs.edit().putString(KEY_STORAGE_URI, uriString).apply()
    }

    fun getStorageUri(): String? {
        return prefs.getString(KEY_STORAGE_URI, null)
    }

    fun saveCleanupDays(days: Int) {
        prefs.edit().putInt(KEY_CLEANUP_DAYS, days).apply()
    }

    fun getCleanupDays(): Int {
        return prefs.getInt(KEY_CLEANUP_DAYS, 0)
    }

    // --- 场景模式逻辑 ---

    fun saveRecordingMode(mode: RecordingMode) {
        prefs.edit().putString(KEY_RECORDING_MODE, mode.name).apply()
    }

    fun getRecordingMode(): RecordingMode {
        val name = prefs.getString(KEY_RECORDING_MODE, RecordingMode.AUTO.name)
        return try { RecordingMode.valueOf(name!!) } catch (e: Exception) { RecordingMode.AUTO }
    }

    // 获取当前模式下的触发偏移 (灵敏度)
    fun getTriggerOffset(): Int {
        return when (getRecordingMode()) {
            RecordingMode.INDOOR -> 5
            RecordingMode.OUTDOOR -> 12
            RecordingMode.AUTO -> 10 // 突发偏移量 10dB
            RecordingMode.MANUAL -> prefs.getInt(KEY_TRIGGER_OFFSET, 8)
        }
    }

    fun saveTriggerOffset(offset: Int) {
        prefs.edit().putInt(KEY_TRIGGER_OFFSET, offset).apply()
    }

    // 获取当前模式下的追踪变率 (1-10，5为标准)
    fun getTrackingSpeed(): Int {
        return when (getRecordingMode()) {
            RecordingMode.INDOOR -> 3
            RecordingMode.OUTDOOR -> 8
            RecordingMode.AUTO -> 5
            RecordingMode.MANUAL -> prefs.getInt(KEY_TRACKING_SPEED, 5)
        }
    }

    fun saveTrackingSpeed(speed: Int) {
        prefs.edit().putInt(KEY_TRACKING_SPEED, speed).apply()
    }

    // 获取当前模式下的下降变率 (1-10，5为标准)
    fun getFallSpeed(): Int {
        return when (getRecordingMode()) {
            RecordingMode.INDOOR -> 5
            RecordingMode.OUTDOOR -> 5
            RecordingMode.AUTO -> 5
            RecordingMode.MANUAL -> prefs.getInt(KEY_FALL_SPEED, 5)
        }
    }

    fun saveFallSpeed(speed: Int) {
        prefs.edit().putInt(KEY_FALL_SPEED, speed).apply()
    }

    // 获取当前模式下的停止判定延迟
    fun getStopEvalDelay(): Long {
        return when (getRecordingMode()) {
            RecordingMode.INDOOR -> 15000L
            RecordingMode.OUTDOOR -> 25000L
            RecordingMode.AUTO -> 20000L
            RecordingMode.MANUAL -> prefs.getLong(KEY_STOP_EVAL_DELAY_MS, 15000L)
        }
    }

    fun saveStopEvalDelay(ms: Long) {
        prefs.edit().putLong(KEY_STOP_EVAL_DELAY_MS, ms).apply()
    }

    // 获取当前模式下的最短保存时长
    fun getMinSaveDuration(): Long {
        return when (getRecordingMode()) {
            RecordingMode.INDOOR -> 10000L // 10s
            RecordingMode.OUTDOOR -> 25000L // 25s
            RecordingMode.AUTO -> 15000L   // 15s
            RecordingMode.MANUAL -> prefs.getLong(KEY_MIN_SAVE_DURATION_MS, 20000L)
        }
    }

    fun saveMinSaveDuration(ms: Long) {
        prefs.edit().putLong(KEY_MIN_SAVE_DURATION_MS, ms).apply()
    }

    // --- 警报模式 ---

    fun saveAlertModeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_IS_ALERT_MODE_ENABLED, enabled).apply()
    }

    fun isAlertModeEnabled(): Boolean {
        return prefs.getBoolean(KEY_IS_ALERT_MODE_ENABLED, false)
    }

    fun saveAlertTimeThreshold(ms: Long) {
        prefs.edit().putLong(KEY_ALERT_TIME_THRESHOLD_MS, ms).apply()
    }

    fun getAlertTimeThreshold(): Long {
        return prefs.getLong(KEY_ALERT_TIME_THRESHOLD_MS, 24 * 60 * 60 * 1000L) // 默认 24 小时
    }

    fun saveLastValidRecordTime(time: Long) {
        prefs.edit().putLong(KEY_LAST_VALID_RECORD_TIME, time).apply()
    }

    fun getLastValidRecordTime(): Long {
        return prefs.getLong(KEY_LAST_VALID_RECORD_TIME, System.currentTimeMillis())
    }
}
