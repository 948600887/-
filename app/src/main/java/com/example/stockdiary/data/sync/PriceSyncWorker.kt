package com.example.stockdiary.data.sync

import com.example.stockdiary.data.contract.SyncErrorType
import com.example.stockdiary.data.contract.SyncStrategy

import com.example.stockdiary.common.DateFormats
import com.example.stockdiary.data.repository.AssetRepository
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.stockdiary.data.dividend.DividendDateValidator
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.database.StockDatabase
import com.example.stockdiary.database.amountFor
import com.example.stockdiary.utils.AppLogger
import com.example.stockdiary.utils.NotificationHelper
import com.example.stockdiary.utils.ExchangeRateManager
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

    private val dateFormatter = DateFormats.ISO_DATE_FORMATTER
    private val prefs by lazy { PriceSyncPreferencesManager.getInstance(applicationContext) }

    /**
     * 重排下一轮——但只在总开关仍然打开时。
     *
     * 原先三个终态分支都无条件 `scheduleSmartSync` 重新 `enqueueUniquePeriodicWork`，
     * 于是「用户关开关时正好有一轮在途」的场景下：设置页的 `cancelUniqueWork` 只能取消
     * **待执行**的那一轮，拦不住这个在途轮次；它跑完自己又注册了一份全新的周期任务，
     * 表现为开关显示"已关闭"、后台同步却永久复活。这里补上总开关判断，链子到此为止。
     */
    private fun rescheduleIfEnabled() {
        if (prefs.isAutoSyncEnabled()) {
            PriceSyncScheduler.scheduleSmartSync(applicationContext, runImmediately = false)
        } else {
            AppLogger.i("自动同步总开关已关闭，不重排下一轮（避免后台同步自行复活）", "PriceSyncWorker")
        }
    }

    override suspend fun doWork(): Result {
        val startTime = System.currentTimeMillis()

        // 总开关：用户关掉「自动刷新价格与分红」后本 Worker 必须停手。
        // 原先只看分类型开关（盘中同步股票 / 自动同步加密），从不看总开关。
        if (!prefs.isAutoSyncEnabled()) {
            AppLogger.i("自动同步总开关已关闭，跳过本轮且不重排下一轮", "PriceSyncWorker")
            return Result.success()
        }

        return try {
            val repository = AssetRepository.getInstance(applicationContext)
            // 按用户偏好 + 交易时段分频：盘中默认不刷股票/基金、加密默认不自动刷（见 SyncStrategy.shouldAutoSyncType）。
            // 手动刷新不受此约束——用户显式点刷新，照旧全类型强制刷。
            val inTradingHours = SyncStrategy.isInTradingHours()
            val stockIntradaySync = prefs.isStockIntradaySyncEnabled()
            val cryptoAutoSync = prefs.isCryptoAutoSyncEnabled()
            val allAssets = repository.getAllAssets().first()
            val assets = allAssets.filter {
                (it.type == AssetType.STOCK || it.type == AssetType.FUND || it.type == AssetType.CRYPTO) &&
                    SyncStrategy.shouldAutoSyncType(it.type, inTradingHours, stockIntradaySync, cryptoAutoSync)
            }

            if (assets.isEmpty()) {
                // 本轮被偏好/时段全部过滤掉（典型：盘中 + 两个开关都关）：正常跳过，
                // 但仍重排下一轮，保证 15min↔4h 的自适应切换不失效。
                // ⚠️ 此处刻意**不构造** [planPriceSyncRound]（即 PriceSyncRoundPlan）：
                // 这是「全 0 极端分支」——资产列表为空，[AssetSyncManager.syncAllAssets]
                // 的 `assets.isEmpty() → return SyncResult(0,0)`（:328-330）与手动刷新路径
                // 早已覆盖，后台链路在此根本不可达（assets 为空走不到下面的 syncAllAssets）。
                // 没必要为一条不可达分支造一个全 0 的 plan 再消费，否则反而要为空结果
                // 特判「是否该通知 / 是否更新时间戳」，徒增噪声。
                AppLogger.i(
                    "按同步偏好与交易时段无可同步资产，跳过本轮" +
                        "（交易时段=$inTradingHours, 盘中同步股票=$stockIntradaySync, 自动同步加密=$cryptoAutoSync）",
                    "PriceSyncWorker"
                )
                // 分红到账提醒是独立提醒，不该被「盘中不刷股票」这类价格同步开关连坐：
                // 交易日白天到账的分红，原先要等到收盘后才通知。用同一份全量资产再判一次。
                notifyTodayDividends(applicationContext, allAssets)
                rescheduleIfEnabled()
                return Result.success()
            }

            AppLogger.i("开始自动价格同步，资产数: ${assets.size}", "PriceSyncWorker")

            // 统一入口：BACKGROUND_PERIODIC 由 PriceSyncCoordinator 推导出 forceRefresh=false
            // （分红走「24h TTL + 空结果 7 天」分层，股票仍按自身 TTL 自然刷新，不强制重抓）。
            val result = PriceSyncCoordinator.sync(
                context = applicationContext,
                repository = repository,
                request = PriceSyncRequest(
                    scenario = SyncScenario.BACKGROUND_PERIODIC,
                    assets = assets,
                    onProgress = { },
                    startTime = startTime,
                    timeLimitMs = WORKER_TIME_LIMIT_MS
                )
            )

            // 收尾先分类再按计划执行副作用（纯判定见 [PriceSyncRoundPolicy]）。
            // 旧判定 `successCount > 0 || failedCount == 0` 对「0 成功 / 0 失败 / N 跳过」
            // 恒为真，全跳过的一轮也会刷新全局时间戳 + 清零失败 + 写快照 + 报「成功同步 0 个资产」。
            val plan = planPriceSyncRound(
                result = result,
                elapsedSinceLastSuccessNotifyMillis =
                    System.currentTimeMillis() - prefs.getLastSuccessNotifyTimeMillis(),
                successNotifyIntervalMillis = SUCCESS_NOTIFY_INTERVAL
            )

            // 通知只是附加反馈：失败不能把"成功的同步"判成失败，更不能让它穿透到 doWork
            // 的 catch 里导致走到 handleFailure 后无法自我续期。所以这里一律兜住。
            // 纯成功降频；有失败、或全跳过（本轮一个价格都没更新）都一定如实通知。
            if (plan.notifyResult) {
                notifySafely {
                    NotificationHelper.showPriceSyncNotification(
                        applicationContext,
                        result.successCount,
                        result.failedCount,
                        result.skippedCount,
                        result.skippedByReason
                    )
                }
            }
            // 「上次成功通知时间」只由纯策略决定、Worker 只消费：全跳过 / 失败轮次虽然
            // 照常发通知，但不是成功通知，绝不占用纯成功降频窗口（否则会重置窗口、
            // 推迟网络恢复后那次真实成功通知）。
            if (plan.updateSuccessNotifyTimestamp) {
                prefs.setLastSuccessNotifyTimeMillis(System.currentTimeMillis())
            }

            // 日志带 skipped 总数；文案与通知共用同一个纯函数，口径不会漂移。
            // 前缀不再写「完成」：全跳过轮次拼成「完成：本轮无有效更新」自相矛盾；
            // 如实性统一由 plan.message 承担，这里只标明「一轮后台同步」。
            // 截断（时间预算跑满）时，在日志如实标注「部分未更新」，避免用户以为整轮都同步完了
            AppLogger.i(buildRoundSyncLog(result, plan.message), "PriceSyncWorker")

            if (plan.refreshLastSyncTime) prefs.setLastSyncTimeMillis(System.currentTimeMillis())
            if (plan.clearConsecutiveFailures) prefs.setConsecutiveFailureCount(0)
            if (plan.clearLastErrorType) prefs.setLastErrorType(null)
            // 走统一策略：截断（时间预算跑满）/ 全跳过的轮次不写快照——前者会把
            // 「部分新价 + 部分旧价」固化成当日净值，后者只是把旧价再固化一次。
            // 与手动刷新共用 [SyncSnapshotPolicy]，避免两处判定再次漂移。
            if (plan.saveTodaySnapshot) saveTodaySnapshotIfEligible(repository, result, "PriceSyncWorker")
            if (plan.outcome == PriceSyncRoundOutcome.NO_EFFECTIVE_UPDATE) {
                AppLogger.i(
                    "本轮无有效更新（${result.skippedCount} 个资产被跳过），" +
                        "不刷新全局时间戳 / 不清零连续失败 / 不写当日快照；跳过不算失败，照常续期",
                    "PriceSyncWorker"
                )
            }
            // 用全量资产判分红：assets 已被价格同步开关过滤，拿它判会漏掉当天的派息提醒；
            // 分红是独立提醒，全跳过的轮次也要照常判。
            if (plan.notifyTodayDividends) notifyTodayDividends(applicationContext, allAssets)
            // 跑完一轮后按「此时是否交易时段」重排下一次间隔（15分钟 ↔ 4小时自适应），
            // 否则间隔永远是上次 App 启动那一刻算出的值，智能间隔形同虚设。
            // 失败轮次由 handleFailure 内部自行续期，这里不重复排。
            if (plan.reschedule) rescheduleIfEnabled()

            return when (plan.outcome) {
                // 「跳过不算失败」：全跳过照旧 success，绝不改成 Result.failure，
                // 也不触发 handleFailure / 指数退避。
                PriceSyncRoundOutcome.EFFECTIVE_UPDATE,
                PriceSyncRoundOutcome.NO_EFFECTIVE_UPDATE -> Result.success()
                PriceSyncRoundOutcome.FAILURE -> handleFailure(result.dominantErrorType)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("自动价格同步异常: ${e.message}", e, "PriceSyncWorker")
            handleFailure(SyncStrategy.classifyError(e))
        }
    }

    /**
     * 发通知的兜底座。
     *
     * 通知失败（权限被拒、渠道异常、PendingIntent 序列化异常…）绝不能影响业务结果：
     * 一旦让它抛出去，[handleFailure] 之后的代码（尤其是 priceSyncScheduler 的自我续期）
     * 就不会执行，周期任务会彻底停摆且用户毫无察觉。
     */
    private fun notifySafely(block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w("发送通知失败（不影响同步结果）: ${e.message}", "PriceSyncWorker")
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
            // 第 1 次失败就通知，文案会写成「价格同步**多次**失败 / 已连续 **1** 次」，
            // 次数与文案双误导；而且下面把 count 清零、周期续期，非交易时段每 4 小时
            // 就会重推一次 —— 单个资产代码写错/行情源改版就变成连环骚扰。
            // 攒到第二次再提示：一次是意外，两次才说明这个源真的取不到
            if (newCount >= MIN_FAILURES_TO_NOTIFY) {
                notifySafely {
                    NotificationHelper.showPriceSyncFailureNotification(applicationContext, newCount)
                }
            }
            prefs.setConsecutiveFailureCount(0)
            // 终态失败也要续期下一轮，否则周期任务就此停摆
            rescheduleIfEnabled()
            return Result.failure()
        }

        if (newCount >= MAX_CONSECUTIVE_FAILURES) {
            prefs.setConsecutiveFailureCount(0)
            notifySafely {
                NotificationHelper.showPriceSyncFailureNotification(applicationContext, newCount)
            }
            AppLogger.w("自动价格同步连续失败 $newCount 次，已通知用户", "PriceSyncWorker")
        }

        return Result.retry()
    }

    /**
     * 检查今日持仓是否有分红到账，发送一次通知并记录已通知的分红，避免重复提醒。
     */
    private suspend fun notifyTodayDividends(
        context: Context,
        assets: List<Asset>
    ) {
        try {
            val today = LocalDate.now().format(dateFormatter)
            // 复用 doWork 已经加载过的资产列表，避免对全表再查一次
            // （后台同步每 15 分钟 / 4 小时跑一轮，重复全表查询纯属浪费）。
            val stockFundAssets = assets.filter { it.type == AssetType.STOCK || it.type == AssetType.FUND }
            if (stockFundAssets.isEmpty()) return

            val symbols = stockFundAssets.mapNotNull { it.symbol }.distinct()
            if (symbols.isEmpty()) return

            val db = StockDatabase.getInstance(context)
            // 到账日优先：除息日只是「有没有资格」的界点，钱真正到账是 payDate。
            // 数据源没给 payDate 时才退回除息日。
            // 与日历/进度条同一套日期校验：脏的 payDate/date 直接不参与通知，
            // 不要用原始字符串比较（可能带时间或日历上不存在的日期）。
            val todayDividends = db.dividendDao().getBySymbols(symbols)
                .filter { d ->
                    (DividendDateValidator.normalizeOrNull(d.payDate)
                        ?: DividendDateValidator.normalizeOrNull(d.date)) == today
                }
            if (todayDividends.isEmpty()) return

            // 多账户持有同一代码时，通知里的“今日到账总额”必须用合计持仓；
            // 原先 associateBy 只留最后一个账户，金额会被低估。
            val assetMap = stockFundAssets.groupBy { it.symbol }.mapValues { (_, group) ->
                if (group.size == 1) {
                    group.first()
                } else {
                    group.first().copy(balance = group.sumOf { it.balance })
                }
            }
            val prefs = context.getSharedPreferences("dividend_notifications", Context.MODE_PRIVATE)
            val editor = prefs.edit()

            var totalAmount = 0.0
            var newCount = 0
            for (dividend in todayDividends) {
                val asset = assetMap[dividend.symbol] ?: continue
                val key = "${dividend.symbol}_${dividend.payDate ?: dividend.date}"
                if (prefs.getBoolean(key, false)) continue

                // 折算成人民币：外币持仓（USD/HKD）的分红按标的币种换算后再累加，
                // 否则美元金额会按人民币数值计入今日到账总额，通知里的「¥总额」被低估。
                val amount = ExchangeRateManager.toCny(dividend.amountFor(asset.balance), asset.currency)
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

        /** 推「同步失败」通知的最低连续失败次数：1 次不发（见 [handleFailure] 的说明） */
        private const val MIN_FAILURES_TO_NOTIFY = 2

        /** 纯成功的「同步完成」通知最小间隔：交易时段每 15 分钟一轮，不必每轮都报平安 */
        private const val SUCCESS_NOTIFY_INTERVAL = 6L * 60 * 60 * 1000
        // WorkManager 后台任务限制约 10 分钟，预留 1 分钟安全余量
        private const val WORKER_TIME_LIMIT_MS = 9 * 60 * 1000L
    }
}
