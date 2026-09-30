package com.example.stockdiary.data

import android.content.Context
import androidx.work.*
import com.example.stockdiary.utils.AppLogger
import java.util.concurrent.TimeUnit
import kotlin.random.Random

object SnapshotScheduler {

    // 最小退避 10 秒，最大退避 5 小时，失败时指数增长并加抖动
    private const val MIN_BACKOFF_MILLIS = 10_000L
    private const val MAX_BACKOFF_MILLIS = 5 * 60 * 60_000L

    fun scheduleDailySnapshot(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiresCharging(false)
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .build()

        val jitter = Random.nextLong(0, MIN_BACKOFF_MILLIS)
        val backoffDelay = (MIN_BACKOFF_MILLIS + jitter).coerceAtMost(MAX_BACKOFF_MILLIS)

        val request = PeriodicWorkRequestBuilder<SnapshotWorker>(1, TimeUnit.DAYS)
            .setConstraints(constraints)
            .setInitialDelay(calculateInitialDelay(), TimeUnit.MILLISECONDS)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                backoffDelay,
                TimeUnit.MILLISECONDS
            )
            .addTag(SnapshotWorker.WORK_NAME)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            SnapshotWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )

        AppLogger.i("每日自动快照已调度，首次执行: ${calculateInitialDelay() / 60000} 分钟后", "SnapshotScheduler")
    }

    fun cancelDailySnapshot(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(SnapshotWorker.WORK_NAME)
        AppLogger.i("每日自动快照已取消", "SnapshotScheduler")
    }

    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<SnapshotWorker>()
            .build()
        
        WorkManager.getInstance(context).enqueue(request)
        AppLogger.i("立即执行快照", "SnapshotScheduler")
    }

    private fun calculateInitialDelay(): Long {
        val now = System.currentTimeMillis()
        val targetHour = 15
        val targetMinute = 10

        val todayTarget = java.time.LocalDateTime.now()
            .withHour(targetHour)
            .withMinute(targetMinute)
            .withSecond(0)
            .withNano(0)
            .atZone(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

        return if (now < todayTarget) {
            todayTarget - now
        } else {
            todayTarget + 24 * 60 * 60 * 1000 - now
        }
    }
}