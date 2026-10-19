package com.example.stockdiary.data.sync

import android.content.Context
import android.content.SharedPreferences

/**
 * 自动价格同步偏好管理
 */
class PriceSyncPreferencesManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isAutoSyncEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun setAutoSyncEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getLastSyncTimeMillis(): Long = prefs.getLong(KEY_LAST_SYNC_TIME, 0L)

    fun setLastSyncTimeMillis(timeMillis: Long) {
        prefs.edit().putLong(KEY_LAST_SYNC_TIME, timeMillis).apply()
    }

    fun getConsecutiveFailureCount(): Int = prefs.getInt(KEY_CONSECUTIVE_FAILURES, 0)

    fun setConsecutiveFailureCount(count: Int) {
        prefs.edit().putInt(KEY_CONSECUTIVE_FAILURES, count).apply()
    }

    fun getLastErrorType(): String? = prefs.getString(KEY_LAST_ERROR_TYPE, null)

    fun setLastErrorType(errorType: String?) {
        prefs.edit().putString(KEY_LAST_ERROR_TYPE, errorType).apply()
    }

    /** 上次弹「同步完成」通知的时间（用于给成功通知降频，见 PriceSyncWorker） */
    fun getLastSuccessNotifyTimeMillis(): Long = prefs.getLong(KEY_LAST_SUCCESS_NOTIFY_TIME, 0L)

    fun setLastSuccessNotifyTimeMillis(timeMillis: Long) {
        prefs.edit().putLong(KEY_LAST_SUCCESS_NOTIFY_TIME, timeMillis).apply()
    }

    fun getLastErrorTimeMillis(): Long = prefs.getLong(KEY_LAST_ERROR_TIME, 0L)

    fun setLastErrorTimeMillis(timeMillis: Long) {
        prefs.edit().putLong(KEY_LAST_ERROR_TIME, timeMillis).apply()
    }

    companion object {
        private const val PREFS_NAME = "price_sync_prefs"
        private const val KEY_ENABLED = "auto_price_sync_enabled"
        private const val KEY_LAST_SYNC_TIME = "last_price_sync_time"
        private const val KEY_CONSECUTIVE_FAILURES = "consecutive_sync_failures"
        private const val KEY_LAST_ERROR_TYPE = "last_sync_error_type"
        private const val KEY_LAST_ERROR_TIME = "last_sync_error_time"
        private const val KEY_LAST_SUCCESS_NOTIFY_TIME = "last_success_notify_time"

        @Volatile
        private var instance: PriceSyncPreferencesManager? = null

        fun getInstance(context: Context): PriceSyncPreferencesManager {
            return instance ?: synchronized(this) {
                instance ?: PriceSyncPreferencesManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
