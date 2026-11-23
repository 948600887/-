package com.example.stockdiary.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.data.repository.AssetRepository
import com.example.stockdiary.data.repository.DividendRepository
import com.example.stockdiary.utils.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * 定期分红补齐（系统任务，不依赖 Activity 生命周期）。
 *
 * ## 为什么需要它
 *
 * 手刷后在后台协程里跑的分红预取只活到 Activity `onStop`（实测 6~9 秒），
 * 110 只标的往往只推进到 7 只——「刷新完就切后台」的用户永远补不完。
 * WorkManager 的周期任务由系统调度，App 不在前台也能推进；这里只负责
 * 「按同一套限速/退避规则继续抓」，不引入第二条抓取通道。
 *
 * ## 幂等与续跑
 *
 * - 抓过的标的由 [DividendRepository.markFetched] 写跨进程时间戳，本 Worker
 *   传 `forceRefresh = false`，当天已成功抓过的不会重复请求（即使进程重启、
 *   内存缓存为空）；
 * - 单轮只给 [RUN_BUDGET_MS] 预算，到期后未轮到的代码不写结论，下一周期由
 *   `prioritizeDividendSymbols` 按「最久没抓过」从队首继续，不会永远卡在同一批；
 * - 与前台预取共享 [DividendRepository.prefetchDividends] 的互斥闸：
 *   两边不会同时压天天基金，抢不到闸的一侧直接跳过本轮。
 *
 * ## 刻意不做的事
 *
 * 不设前台服务、不发通知、不加 `setForeground`——用户明确不要提醒类功能；
 * 失败也不做额外重试风暴，交给 WorkManager 的指数退避与下一个周期。
 */
class DividendPrefetchWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // 总开关（与 PriceSyncWorker:57-60 对齐）：「自动刷新价格与分红」关掉后，
        // 这个 Worker 仍每 30 分钟联网跑天天基金，用户完全无从制止——
        // 设置页的开关话术是「价格**与分红**」，分红补齐必须受同一个总开关约束。
        // 关掉时直接 success 且**不重排**（重排由调度器负责，见 PriceSyncScheduler）；
        // 返回 success 而不是 failure，避免 WorkManager 把它当失败去做指数退避重试。
        if (!PriceSyncPreferencesManager.getInstance(applicationContext).isAutoSyncEnabled()) {
            AppLogger.i("自动同步总开关已关闭，跳过本轮定期分红补齐", TAG)
            return Result.success()
        }

        return try {
            // 只在真正执行时记录，用来对照「启动调度」那条：若 WorkInfo 计划时间已过、
            // 系统却直到前台才调度，就能直接判定是系统侧压住而非排期错误。
            WorkDiagnostics.logSystemState(applicationContext, "Worker执行")
            WorkDiagnostics.logWorkInfo(applicationContext, WORK_NAME, "Worker执行")

            val assetRepository = AssetRepository.getInstance(applicationContext)
            val symbols = assetRepository.getAllAssets().first()
                .asSequence()
                .filter { it.type == AssetType.STOCK || it.type == AssetType.FUND }
                .mapNotNull { it.symbol }
                .distinct()
                .toList()

            if (symbols.isEmpty()) {
                AppLogger.i("没有股票/基金持仓，跳过定期分红补齐", TAG)
                return Result.success()
            }

            AppLogger.i(
                "定期分红补齐开始：${symbols.size} 个代码，单轮预算 ${RUN_BUDGET_MS / 1000}s",
                TAG
            )
            val startedAt = System.currentTimeMillis()
            DividendRepository.getInstance(applicationContext).prefetchDividends(
                symbols = symbols,
                forceRefresh = false,
                timeBudgetMs = RUN_BUDGET_MS
            )
            AppLogger.i(
                "定期分红补齐结束：耗时 ${(System.currentTimeMillis() - startedAt) / 1000}s" +
                    "（未轮到的代码下一周期按最久未抓继续）",
                TAG
            )
            Result.success()
        } catch (e: CancellationException) {
            // 系统回收 / 约束不满足时的正常取消，不当作失败重试
            throw e
        } catch (e: Exception) {
            AppLogger.e("定期分红补齐异常: ${e.message}", e, TAG)
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "com.example.stockdiary.DividendPrefetchWorker"

        /**
         * 单轮时间预算。
         *
         * WorkManager 单次后台执行上限约 10 分钟；这里只给 4 分钟：
         * - 110 只按 1 秒匀速闸门约 2 分钟，正常一轮足够跑完；
         * - 遇到 514 / 退避时提前收尾，留足余量给收尾与进程被回收，
         *   剩下的下一周期继续，而不是卡在 10 分钟边缘被系统硬杀。
         */
        const val RUN_BUDGET_MS = 4 * 60_000L

        private const val TAG = "DividendPrefetchWorker"
    }
}
