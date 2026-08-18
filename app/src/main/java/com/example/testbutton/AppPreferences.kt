package com.example.testbutton

import android.content.Context
import android.content.SharedPreferences

/**
 * 轻量级数据持久化
 */
class AppPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("smart_recorder_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_STORAGE_URI = "storage_uri"
        private const val KEY_CLEANUP_DAYS = "cleanup_days"
        private const val KEY_THRESHOLD = "threshold"
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

    fun saveThreshold(threshold: Int) {
        prefs.edit().putInt(KEY_THRESHOLD, threshold).apply()
    }

    fun getThreshold(): Int {
        return prefs.getInt(KEY_THRESHOLD, -45)
    }
}
