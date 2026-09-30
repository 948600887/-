package com.example.stockdiary.data

import android.content.Context
import androidx.work.*
import com.example.stockdiary.utils.AppLogger
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * 自动价格同步调度器
 * 根据是否处于交易时段采用不同同步间隔：
 * - 交易时段（工作日 9:30-11:30、13:00-15:00）每 15 分钟同步一次
 * - 非交易时段每 4 小时同步一次
 */
object PriceSyncScheduler {

    private const val MIN_BACKOFF_MILLIS = 10_000L
    private const val MAX_BACKOFF_MILLIS = 5 * 60 * 60_000L

    fun scheduleDailySync(context: Context) {
        scheduleSmartSync(context)
    }

    /**
     * 根据当前是否处于交易时段，选择同步间隔并调度后台任务
     */
    fun scheduleSmartSync(context: Context) {
        val inTrading = SyncStrategy.isInTradingHours()
        val intervalMillis = SyncStrategy.getCurrentSyncIntervalMillis(inTrading)
        val intervalMinutes = (intervalMillis / 60_000).coerceAtLeast(15)

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresCharging(false)
            .setRequiresBatteryNotLow(true)
            .build()

        val jitter = Random.nextLong(0, MIN_BACKOFF_MILLIS)
        val backoffDelay = (MIN_BACKOFF_MILLIS + jitter).coerceAtMost(MAX_BACKOFF_MILLIS)

        val request = PeriodicWorkRequestBuilder<PriceSyncWorker>(intervalMinutes, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setInitialDelay(0, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                backoffDelay,
                TimeUnit.MILLISECONDS
            )
            .addTag(PriceSyncWorker.WORK_NAME)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PriceSyncWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )

        AppLogger.i(
            "智能价格同步已调度，当前${if (inTrading) "交易时段" else "非交易时段"}，" +
                "间隔: ${intervalMinutes}分钟",
            "PriceSyncScheduler"
        )
    }

    fun cancelDailySync(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PriceSyncWorker.WORK_NAME)
        AppLogger.i("自动价格同步已取消", "PriceSyncScheduler")
    }
}
