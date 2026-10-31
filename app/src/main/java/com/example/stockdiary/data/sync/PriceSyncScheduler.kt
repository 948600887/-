package com.example.stockdiary.data.sync

import com.example.stockdiary.data.contract.SyncStrategy

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

    // WorkManager 的退避下限是 WorkRequest.MIN_BACKOFF_MILLIS（10 分钟），低于它的值会被
    // 静默夹取。原先写死 10 秒，实际一律按 10 分钟走，还让人误以为失败后很快能重试。
    private val MIN_BACKOFF_MILLIS = WorkRequest.MIN_BACKOFF_MILLIS
    private const val MAX_BACKOFF_MILLIS = 5 * 60 * 60_000L

    fun scheduleDailySync(context: Context) {
        scheduleSmartSync(context)
    }

    /**
     * 根据当前是否处于交易时段，选择同步间隔并调度后台任务。
     *
     * @param runImmediately true=立即先跑一次（App 冷启动/用户开启开关时）；
     *   false=间隔到期后再跑（Worker 跑完一轮后自我续期时必须用 false，
     *   否则 UPDATE 替换后 initialDelay=0 会立刻又跑一次，形成无限紧循环）。
     */
    fun scheduleSmartSync(context: Context, runImmediately: Boolean = true) {
        val inTrading = SyncStrategy.isInTradingHours()
        val intervalMillis = SyncStrategy.getCurrentSyncIntervalMillis(inTrading)
        // 下限复用 SyncStrategy 的单一真相源，避免在此处再硬编码「15」分钟
        val intervalMinutes = (intervalMillis / 60_000).coerceAtLeast(SyncStrategy.MIN_SYNC_INTERVAL_MINUTES.toLong())

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresCharging(false)
            .setRequiresBatteryNotLow(true)
            .build()

        // 加一点抖动，避免所有设备在整点同时重试把接口打挂
        val jitter = Random.nextLong(0, MIN_BACKOFF_MILLIS / 2)
        val backoffDelay = (MIN_BACKOFF_MILLIS + jitter).coerceAtMost(MAX_BACKOFF_MILLIS)

        val request = PeriodicWorkRequestBuilder<PriceSyncWorker>(intervalMinutes, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setInitialDelay(if (runImmediately) 0L else intervalMillis, TimeUnit.MILLISECONDS)
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
                "间隔: ${intervalMinutes}分钟${if (runImmediately) "，立即执行一次" else ""}",
            "PriceSyncScheduler"
        )
    }

    fun cancelDailySync(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PriceSyncWorker.WORK_NAME)
        AppLogger.i("自动价格同步已取消", "PriceSyncScheduler")
    }
}
