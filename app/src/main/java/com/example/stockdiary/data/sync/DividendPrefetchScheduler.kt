package com.example.stockdiary.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import com.example.stockdiary.utils.AppLogger
import java.util.concurrent.TimeUnit

/**
 * 分红补齐的周期调度（[DividendPrefetchWorker]）。
 *
 * ## 周期与预算
 *
 * - 周期 **30 分钟**：WorkManager 周期任务下限是 15 分钟，写更小会被静默夹取；
 *   30 分钟既能让「刷完就切后台」的用户在半小时内开始补齐，又不会频繁唤醒设备。
 * - 单轮预算 **4 分钟**（见 [DividendPrefetchWorker.RUN_BUDGET_MS]），远低于
 *   系统约 10 分钟的单次执行上限；一轮跑不完就下一轮按「最久没抓过」续跑。
 * - 加网络约束：离线时不空转，联网后由系统补跑。
 *
 * ## 为什么用 UPDATE 而不是 KEEP
 *
 * 周期/预算这类参数将来调整时，UPDATE 会在下次冷启动替换旧任务；KEEP 会让
 * 老安装永远停留在旧参数上。调度本身幂等，每次冷启动调用没有副作用。
 *
 * 不设 `setForeground`、不建通知渠道：补齐是静默后台行为，用户明确不要提醒。
 */
object DividendPrefetchScheduler {

    suspend fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<DividendPrefetchWorker>(30, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setInitialDelay(5, TimeUnit.MINUTES)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .addTag(DividendPrefetchWorker.WORK_NAME)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            DividendPrefetchWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
        AppLogger.i("分红补齐已调度：每 30 分钟一轮，单轮预算 4 分钟", "DividendPrefetchScheduler")
        // 冷启动时记录一次系统限制与排期，用于区分「系统压住」和「排期算错」
        WorkDiagnostics.logSystemState(context, "启动调度")
        WorkDiagnostics.logWorkInfo(context, DividendPrefetchWorker.WORK_NAME, "启动调度")
    }
}
