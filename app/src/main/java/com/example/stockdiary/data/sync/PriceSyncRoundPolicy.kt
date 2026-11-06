package com.example.stockdiary.data.sync

import com.example.stockdiary.utils.buildPriceSyncMessage

/**
 * 后台 [PriceSyncWorker] 一轮同步「怎么收尾」的纯判定（无 Android / WorkManager 依赖）。
 *
 * ## 修的就是这条被跳过的分支
 *
 * 旧判定 `if (result.successCount > 0 || result.failedCount == 0)` 对
 * 「0 成功 / 0 失败 / N 跳过」恒为真：一轮全是跳过（典型：130 个加密货币因币安熔断
 * 没更新、A 股又被偏好过滤）也会刷新全局 `last_price_sync_time`、清零连续失败、
 * 覆盖当日快照、并弹「成功同步 0 个资产」。用户看到时间戳是刚才，价格其实一个没动。
 *
 * 现在把收尾拆成「先分类、再按计划执行副作用」：分类与计划都是纯函数，
 * JVM 单测可直接断言全跳过时**不发生**哪些副作用，而不必起 WorkManager / Context。
 *
 * ## 刻意保留的语义
 *
 * - 「跳过不算失败」：全跳过返回 [PriceSyncRoundOutcome.NO_EFFECTIVE_UPDATE]，
 *   Worker 照旧 `Result.success()`，**不**走 `handleFailure`、**不**触发指数退避、
 *   **不**把跳过改判成 failure；只是改走「本轮无有效更新」分支，日志/通知如实说明。
 * - 「全 0 极端无害覆盖」：`success == 0 && failed == 0 && skipped == 0` 仍归
 *   [PriceSyncRoundOutcome.EFFECTIVE_UPDATE]（资产为空 / 全被过滤），
 *   既有 [SyncSnapshotPolicyTest] 的断言与后台原行为都不变。
 */
enum class PriceSyncRoundOutcome {
    /** 有有效更新（或全 0 极端）：刷新全局时间戳、清零连续失败、写当日快照 */
    EFFECTIVE_UPDATE,

    /** 全跳过：本轮没有任何标的价格被更新，只记日志/通知，不动偏好与快照 */
    NO_EFFECTIVE_UPDATE,

    /** 有失败且无成功：交回 Worker 的 handleFailure（本计划不做任何成功态副作用） */
    FAILURE
}

/**
 * 一轮收尾允许执行的副作用清单。
 *
 * 字段逐一对应 Worker 里原本内联的每一行赋值/调用；把它们收进 data class 后，
 * 「全跳过不刷新 last_price_sync_time / 不清零连续失败 / 不写快照」变成可直接断言的布尔值。
 */
data class PriceSyncRoundPlan(
    val outcome: PriceSyncRoundOutcome,
    /** 日志与通知共用的如实文案（含 skipped 总数） */
    val message: String,
    val notifyResult: Boolean,
    /**
     * 是否把本轮记为「上次**成功**通知时间」（纯成功通知降频窗口的起点）。
     *
     * 只有 EFFECTIVE_UPDATE 且确实发了通知才推进：全跳过 / 失败轮次虽照常发通知，
     * 但不是成功通知，写这个时间戳会重置降频窗口、推迟网络恢复后真实成功通知的时机。
     */
    val updateSuccessNotifyTimestamp: Boolean,
    val refreshLastSyncTime: Boolean,
    val clearConsecutiveFailures: Boolean,
    val clearLastErrorType: Boolean,
    val saveTodaySnapshot: Boolean,
    val notifyTodayDividends: Boolean,
    val reschedule: Boolean
)

/** 纯分类：只看 [SyncResult]，不碰时钟、偏好、数据库。 */
fun classifyPriceSyncRound(result: SyncResult): PriceSyncRoundOutcome = when {
    result.hasNoEffectiveUpdate -> PriceSyncRoundOutcome.NO_EFFECTIVE_UPDATE
    result.successCount > 0 || result.failedCount == 0 -> PriceSyncRoundOutcome.EFFECTIVE_UPDATE
    else -> PriceSyncRoundOutcome.FAILURE
}

/**
 * 生成本轮收尾计划。
 *
 * @param elapsedSinceLastSuccessNotifyMillis 距上次「同步完成」通知的时间，用于纯成功降频
 * @param successNotifyIntervalMillis 纯成功通知的最小间隔（Worker 的 SUCCESS_NOTIFY_INTERVAL）
 */
fun planPriceSyncRound(
    result: SyncResult,
    elapsedSinceLastSuccessNotifyMillis: Long,
    successNotifyIntervalMillis: Long
): PriceSyncRoundPlan {
    val outcome = classifyPriceSyncRound(result)
    val message = buildPriceSyncMessage(result.successCount, result.failedCount, result.skippedCount)

    // 有失败一定发（用户需要知道）；全跳过也必须发——这正是「数据陈旧透明化」要让用户
    // 看见的信号，不能被「成功通知降频」悄悄吞掉；纯成功才按间隔降频。
    val notifyResult = result.failedCount > 0 ||
        outcome == PriceSyncRoundOutcome.NO_EFFECTIVE_UPDATE ||
        elapsedSinceLastSuccessNotifyMillis >= successNotifyIntervalMillis

    return when (outcome) {
        PriceSyncRoundOutcome.EFFECTIVE_UPDATE -> {
            // 截断（时间预算跑满）：本轮只更新了部分标的。已写库的部分不该被当成
            // 「本轮全部成功」——清零连续失败 / 清错误类型 / 写全量当日快照都会把
            // 「部分新价 + 部分旧价」固化成当日净值、掩盖真实失败，故截断时一律关掉。
            // 但仍刷新全局 last_sync_time：本轮确实抓到了新价，时间戳只标记「最近一次有同步发生」，
            // 不等于「全部成功」；照常通知、照常检查分红到账、照常续期（Worker 仍 Result.success()）。
            val truncated = result.truncatedByTimeLimit
            PriceSyncRoundPlan(
                outcome = outcome,
                message = message,
                notifyResult = notifyResult,
                // 纯成功通知才推进降频窗口；截断（部分更新）不占「成功通知」降频窗口
                updateSuccessNotifyTimestamp = notifyResult && !truncated,
                refreshLastSyncTime = true,
                clearConsecutiveFailures = !truncated,
                clearLastErrorType = !truncated,
                saveTodaySnapshot = !truncated,
                notifyTodayDividends = true,
                reschedule = true
            )
        }

        PriceSyncRoundOutcome.NO_EFFECTIVE_UPDATE -> PriceSyncRoundPlan(
            outcome = outcome,
            message = message,
            notifyResult = true,
            // 全跳过照常如实通知，但不占「成功通知」降频窗口
            updateSuccessNotifyTimestamp = false,
            refreshLastSyncTime = false,
            clearConsecutiveFailures = false,
            clearLastErrorType = false,
            saveTodaySnapshot = false,
            // 分红到账提醒是独立提醒，不该被「本轮价格全跳过」连坐：
            // 它只读库里的分红表，与行情是否更新无关（同 assets.isEmpty() 分支的取舍）。
            notifyTodayDividends = true,
            reschedule = true
        )

        PriceSyncRoundOutcome.FAILURE -> PriceSyncRoundPlan(
            outcome = outcome,
            message = message,
            notifyResult = true,
            updateSuccessNotifyTimestamp = false,
            refreshLastSyncTime = false,
            clearConsecutiveFailures = false,
            clearLastErrorType = false,
            saveTodaySnapshot = false,
            // 与原失败分支一致：不检查分红、不在这里重排（handleFailure 内部自行续期）
            notifyTodayDividends = false,
            reschedule = false
        )
    }
}

/**
 * 一轮同步的日志文案（含截断标注）。Worker 与单测共用同一份拼接逻辑，口径不会漂移。
 *
 * 截断（时间预算跑满）时如实追加「（已到时间上限，部分未更新）」，让用户知道本轮只同步了部分标的，
 * 而不是误以为整轮都同步完成。
 */
fun buildRoundSyncLog(result: SyncResult, baseMessage: String): String {
    val suffix = if (result.truncatedByTimeLimit) "（已到时间上限，部分未更新）" else ""
    return "自动价格同步：$baseMessage$suffix"
}
