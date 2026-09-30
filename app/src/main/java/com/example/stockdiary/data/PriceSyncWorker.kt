package com.example.stockdiary.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.database.StockDatabase
import com.example.stockdiary.utils.AppLogger
import com.example.stockdiary.utils.NotificationHelper
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException

/**
 * 自动价格同步 Worker
 * 在后台刷新所有可同步资产的价格，成功后刷新当日快照，并通过通知展示结果
 */
class PriceSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val prefs by lazy { PriceSyncPreferencesManager.getInstance(applicationContext) }

    override suspend fun doWork(): Result {
        val startTime = System.currentTimeMillis()

        return try {
            val repository = AssetRepository.getInstance(applicationContext)
            val assets = repository.getAllAssets().first()
                .filter { it.type == AssetType.STOCK || it.type == AssetType.FUND || it.type == AssetType.CRYPTO }

            if (assets.isEmpty()) {
                AppLogger.i("没有需要同步价格的资产，跳过", "PriceSyncWorker")
                return Result.success()
            }

            AppLogger.i("开始自动价格同步，资产数: ${assets.size}", "PriceSyncWorker")

            val result = AssetSyncManager.syncAllAssets(
                context = applicationContext,
                repository = repository,
                assets = assets,
                onProgress = { },
                forceRefresh = true,
                startTime = startTime,
                timeLimitMs = WORKER_TIME_LIMIT_MS
            )

            // 同步在循环内部已对时间上限进行检查并提前返回；此处作为安全网兜底
            if (result.truncatedByTimeLimit || isApproachingTimeLimit(startTime)) {
                AppLogger.w("价格同步接近后台任务时间上限，本次重试", "PriceSyncWorker")
                return handleFailure()
            }

            NotificationHelper.showPriceSyncNotification(
                applicationContext,
                result.successCount,
                result.failedCount
            )

            AppLogger.i(
                "自动价格同步完成，成功: ${result.successCount}, 失败: ${result.failedCount}",
                "PriceSyncWorker"
            )

            if (result.successCount > 0 || result.failedCount == 0) {
                prefs.setLastSyncTimeMillis(System.currentTimeMillis())
                prefs.setConsecutiveFailureCount(0)
                prefs.setLastErrorType(null)
                createOrReplaceTodaySnapshot(repository)
                notifyTodayDividends(repository, applicationContext)
                // 跑完一轮后按「此时是否交易时段」重排下一次间隔（15分钟 ↔ 4小时自适应），
                // 否则间隔永远是上次 App 启动那一刻算出的值，智能间隔形同虚设
                PriceSyncScheduler.scheduleSmartSync(applicationContext, runImmediately = false)
                Result.success()
            } else {
                handleFailure(result.dominantErrorType)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("自动价格同步异常: ${e.message}", e, "PriceSyncWorker")
            handleFailure(SyncStrategy.classifyError(e))
        }
    }

    private fun isApproachingTimeLimit(startTime: Long): Boolean {
        return System.currentTimeMillis() - startTime > WORKER_TIME_LIMIT_MS
    }

    private suspend fun createOrReplaceTodaySnapshot(repository: AssetRepository) {
        try {
            val today = LocalDate.now().format(dateFormatter)
            val snapshot = repository.replaceSnapshotForDate(today)
            if (snapshot != null) {
                AppLogger.i("价格同步后已刷新当日快照: ${snapshot.date}, 净资产: ¥${snapshot.netAssets}", "PriceSyncWorker")
            } else {
                AppLogger.w("价格同步后刷新当日快照失败", "PriceSyncWorker")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("刷新当日快照失败: ${e.message}", e, "PriceSyncWorker")
        }
    }

    private fun handleFailure(errorType: SyncErrorType? = null): Result {
        val effectiveErrorType = errorType ?: SyncErrorType.UNKNOWN
        val newCount = prefs.getConsecutiveFailureCount() + 1
        prefs.setConsecutiveFailureCount(newCount)
        prefs.setLastErrorType(effectiveErrorType.name)
        prefs.setLastErrorTimeMillis(System.currentTimeMillis())

        AppLogger.w(
            "自动价格同步失败，类型: ${effectiveErrorType.name}，连续失败: $newCount 次",
            "PriceSyncWorker"
        )

        if (!SyncStrategy.isRetryable(effectiveErrorType)) {
            AppLogger.w("数据类错误不再重试", "PriceSyncWorker")
            NotificationHelper.showPriceSyncFailureNotification(applicationContext, newCount)
            prefs.setConsecutiveFailureCount(0)
            // 终态失败也要续期下一轮，否则周期任务就此停摆
            PriceSyncScheduler.scheduleSmartSync(applicationContext, runImmediately = false)
            return Result.failure()
        }

        if (newCount >= MAX_CONSECUTIVE_FAILURES) {
            prefs.setConsecutiveFailureCount(0)
            NotificationHelper.showPriceSyncFailureNotification(applicationContext, newCount)
            AppLogger.w("自动价格同步连续失败 $newCount 次，已通知用户", "PriceSyncWorker")
        }

        return Result.retry()
    }

    /**
     * 检查今日持仓是否有分红到账，发送一次通知并记录已通知的分红，避免重复提醒。
     */
    private suspend fun notifyTodayDividends(repository: AssetRepository, context: Context) {
        try {
            val today = LocalDate.now().format(dateFormatter)
            val assets = repository.getAllAssets().first()
                .filter { it.type == AssetType.STOCK || it.type == AssetType.FUND }
            if (assets.isEmpty()) return

            val symbols = assets.mapNotNull { it.symbol }.distinct()
            if (symbols.isEmpty()) return

            val db = StockDatabase.getInstance(context)
            val todayDividends = db.dividendDao().getBySymbols(symbols)
                .filter { it.date == today }
            if (todayDividends.isEmpty()) return

            val assetMap = assets.associateBy { it.symbol }
            val prefs = context.getSharedPreferences("dividend_notifications", Context.MODE_PRIVATE)
            val editor = prefs.edit()

            var totalAmount = 0.0
            var newCount = 0
            for (dividend in todayDividends) {
                val asset = assetMap[dividend.symbol] ?: continue
                val key = "${dividend.symbol}_${dividend.date}"
                if (prefs.getBoolean(key, false)) continue

                val amount = dividend.amount / 10.0 * asset.balance
                totalAmount += amount
                newCount++
                editor.putBoolean(key, true)
            }
            editor.apply()

            if (newCount > 0) {
                val name = if (newCount == 1) {
                    assetMap[todayDividends.first().symbol]?.name ?: "持仓"
                } else {
                    "$newCount 只持仓"
                }
                NotificationHelper.showDividendNotification(context, name, totalAmount)
                AppLogger.i("今日分红到账通知: $name, 总额: ¥$totalAmount", "PriceSyncWorker")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("分红通知检查失败: ${e.message}", e, "PriceSyncWorker")
        }
    }

    companion object {
        const val WORK_NAME = "com.example.stockdiary.PriceSyncWorker"
        private const val MAX_CONSECUTIVE_FAILURES = 3
        // WorkManager 后台任务限制约 10 分钟，预留 1 分钟安全余量
        private const val WORKER_TIME_LIMIT_MS = 9 * 60 * 1000L
    }
}
