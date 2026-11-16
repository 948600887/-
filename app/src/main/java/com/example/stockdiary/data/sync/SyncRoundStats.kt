package com.example.stockdiary.data.sync

import com.example.stockdiary.data.contract.SyncErrorType
import com.example.stockdiary.data.price.PriceLineageRecorder
import com.example.stockdiary.data.price.SourceStats
import com.example.stockdiary.utils.AppLogger
import com.example.stockdiary.utils.FailureLogFormat
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 一轮同步的**统计账本**（原 [AssetSyncManager] 里散落的计数器，外提为独立组件）。
 *
 * 只做记账与产出，不驱动流程：谁成功、谁失败、谁跳过、谁被截断/取消，
 * 以及轮末汇总文本与 [SyncResult]。取价、并发、预算、取消语义全部仍由
 * [AssetSyncManager] 编排，本类不碰网络与协程调度。
 *
 * ## 为什么需要多个集合而不是一个计数
 *
 * - 成功 / 失败：给汇总与 [SyncResult]；
 * - `skipped` 原因分布：只说「跳过 127」等于没统计，必须拆成币安熔断 / 退市 / 停牌…；
 * - `succeeded` / `failed`：收尾一次性套用到卡片提示（[AssetSyncFailureStore]），
 *   避免逐标的写磁盘；
 * - `settled` / `budgetSkipped`：取消级联只统计「没走完、又不是我们主动跳过」的标的，
 *   否则「总数 106，成功+失败+跳过只有 50」的缺口会出现在轮次摘要里。
 *
 * 线程安全：所有字段都是并发容器 / 原子类型，多个并发标的协程同时记账。
 */
internal class SyncRoundStats(
    val roundId: Int,
    val trigger: String,
    val totalCount: Int
) {
    private val successCounter = AtomicInteger(0)
    private val failedCounter = AtomicInteger(0)
    private val completedCounter = AtomicInteger(0)
    private val skipReasons = ConcurrentHashMap<String, AtomicInteger>()
    private val errorTypes = ConcurrentLinkedQueue<SyncErrorType>()
    private val truncatedFlag = AtomicBoolean(false)
    private val succeededAssetIds = ConcurrentHashMap.newKeySet<Long>()
    private val failedAssetIds = ConcurrentHashMap.newKeySet<Long>()
    private val settledAssetIds = ConcurrentHashMap.newKeySet<Long>()
    private val budgetSkippedAssetIds = ConcurrentHashMap.newKeySet<Long>()

    val successCount: Int get() = successCounter.get()

    val failedCount: Int get() = failedCounter.get()

    val completedCount: Int get() = completedCounter.get()

    val truncated: Boolean get() = truncatedFlag.get()

    /** 本轮**确实**成功落到新价的资产（含退市股东财兜底成功），供卡片提示落盘 */
    val succeededIds: Set<Long> get() = succeededAssetIds

    /** 本轮计入失败数的资产（不含任何「跳过」），供卡片提示落盘 */
    val failedIds: Set<Long> get() = failedAssetIds

    fun recordSuccess() {
        successCounter.incrementAndGet()
    }

    /** 真失败：同时累计失败数与错误类型（供「该不该重试」的汇总判断） */
    fun recordFailure(type: SyncErrorType) {
        failedCounter.incrementAndGet()
        errorTypes.offer(type)
    }

    /** 走完一个标的；返回累计完成数，供进度回调判断是否收尾 */
    fun recordCompleted(): Int = completedCounter.incrementAndGet()

    fun countSkip(reason: String) {
        skipReasons.computeIfAbsent(reason) { AtomicInteger() }.incrementAndGet()
    }

    /** 批量补记某类跳过（取消级联一次可能有很多个） */
    fun addSkipCount(reason: String, count: Int) {
        if (count <= 0) return
        skipReasons.computeIfAbsent(reason) { AtomicInteger() }.addAndGet(count)
    }

    /** 标记本轮已被截断；返回 true 表示本次是**第一次**置位（调用方据此只打一条日志） */
    fun markTruncated(): Boolean = truncatedFlag.compareAndSet(false, true)

    /** 同步流程正常走完（含显式跳过）；被取消的标的走不到这里 */
    fun markSettled(assetId: Long) {
        settledAssetIds.add(assetId)
    }

    /** 因预算/取消而根本没开始的标的（已单独计入「超时截断」，收尾统计要排除） */
    fun markBudgetSkipped(assetId: Long) {
        budgetSkippedAssetIds.add(assetId)
    }

    fun markSucceeded(assetId: Long) {
        succeededAssetIds.add(assetId)
    }

    fun markFailed(assetId: Long) {
        failedAssetIds.add(assetId)
    }

    /**
     * 取消级联：没走完同步流程、又不在成功/失败/预算跳过集合里的标的最多算一次。
     *
     * 只看集合、不改状态；是否真的记进「取消级联」由调用方按 `truncated` 决定。
     */
    fun countCancelledCascade(assetIds: List<Long>): Int = assetIds.count { assetId ->
        assetId !in settledAssetIds &&
            assetId !in succeededAssetIds &&
            assetId !in failedAssetIds &&
            assetId !in budgetSkippedAssetIds
    }

    /** 取消收尾文本：此前被取消的轮次只有「开始」没有收尾，日志上像丢了一轮 */
    fun cancellationSummary(cause: CancellationException?): String {
        val reason = cause?.message?.takeIf { it.isNotBlank() }?.let { "（取消原因：$it）" } ?: ""
        return "价格同步取消收尾 [轮次#$roundId][$trigger]：" +
            "已完成 $completedCount/$totalCount，" +
            "成功 $successCount，失败 $failedCount$reason"
    }

    fun skipReasonsSnapshot(): Map<String, Int> = skipReasons.mapValues { it.value.get() }

    /** 组装返回给 UI / Worker 的同步结果（与日志汇总同一份数字） */
    fun toResult(elapsedMs: Long, sourceStats: List<SourceStats>): SyncResult {
        val errors = errorTypes.toList()
        return SyncResult(
            successCount = successCount,
            failedCount = failedCount,
            networkErrorCount = errors.count { it == SyncErrorType.NETWORK },
            serverErrorCount = errors.count { it == SyncErrorType.SERVER },
            dataErrorCount = errors.count { it == SyncErrorType.DATA },
            unknownErrorCount = errors.count { it == SyncErrorType.UNKNOWN },
            dominantErrorType = errors.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key,
            truncatedByTimeLimit = truncated,
            skippedByReason = skipReasonsSnapshot(),
            sourceStats = sourceStats,
            elapsedMs = elapsedMs
        )
    }

    /**
     * 打印一轮同步的结构化汇总（单行，用 ` | ` 分段，便于按行检索），并返回
     * 「汇总 + 血缘摘要」文本，供 [AssetSyncManager] 存进诊断报告头部的最近一轮快照。
     *
     * 设计要点：
     * - **跳过必须拆原因**：只说"跳过 127"等于没统计。币安熔断 / 退市 / 停牌 /
     *   不可交易 / 非行情类型各自多少，决定了"该不该管"。
     * - **数据源按三个出口计数**：成功 / 源级失败 / 符号级失败。这一行直接回答
     *   "某个源是不是全挂了"——真机日志里新浪 44 次空报价、0 成功，就是靠人肉
     *   grep -c 才看出来的，现在轮次自己会说。
     */
    fun logSummary(elapsedMs: Long, sourceStats: List<SourceStats>): String {
        val skippedReasons = skipReasonsSnapshot()
        val skipped = skippedReasons.values.sum()
        val skipText = if (skippedReasons.isEmpty()) {
            "无"
        } else {
            skippedReasons.entries.sortedByDescending { it.value }
                .joinToString(" · ") { "${it.key} ${it.value}" }
        }
        val sourceText = if (sourceStats.isEmpty()) {
            "无"
        } else {
            sourceStats.joinToString(" · ") { s ->
                val reason = s.lastFailureReason
                    ?.takeIf { it.isNotBlank() }
                    ?.let { "（${it.take(24)}）" }
                    ?: ""
                // 批量源（币安）额外带上分片结果；单源没有批量请求时保持不显示
                val batch = s.batchShards
                    ?.takeIf { it > 0 }
                    ?.let { total -> "/分片败${s.batchFailedShards ?: 0}/$total" }
                    ?: ""
                val degrade = s.degradedReason
                    ?.takeIf { it.isNotBlank() }
                    ?.let { "（降级：${it.take(30)}）" }
                    ?: ""
                "${s.source} 成${s.success}/源败${s.sourceLevelFailure}/" +
                    "符败${s.symbolLevelFailure}/耗时${s.durationMs}ms$batch$reason$degrade"
            }
        }
        val summary = "=== 同步轮次 #$roundId 汇总 [$trigger] === | " +
            "资产 $totalCount：成功 $successCount / 失败 $failedCount / 跳过 $skipped（$skipText） | " +
            "数据源：$sourceText | " +
            "超时截断=$truncated · 总耗时 ${elapsedMs / 1000.0}s"
        // 血缘摘要与轮次汇总一起落地：日志能回答「这个价来自哪个源、哪一轮、什么时间」，
        // 诊断导出头部也带上这段。只统计内存里的血缘记录，不影响任何取价/缓存/落库结果。
        val lineageSummary = PriceLineageRecorder.summary(roundId)
        AppLogger.i(summary, TAG)
        AppLogger.i(lineageSummary, TAG)
        // 本轮已汇总完，清掉进程内本轮血缘（只删 roundId 匹配的，不碰并发中的其它轮）
        PriceLineageRecorder.endRound(roundId)

        // 每源一行：逐标的行给细节，这一行回答「这个源这轮到底怎样」（跳过/失败的数量与原因分布）
        sourceStats.forEach { s ->
            if (s.success + s.sourceLevelFailure + s.skipped <= 0) return@forEach
            AppLogger.i(
                FailureLogFormat.sourceSummaryLine(
                    roundId = roundId,
                    source = s.source,
                    success = s.success,
                    failed = s.sourceLevelFailure,
                    skipped = s.skipped,
                    failureReasons = s.failureReasons,
                    skipReasons = s.skipReasons,
                    durationMs = s.durationMs
                ),
                TAG
            )
        }
        return "$summary\n$lineageSummary"
    }

    private companion object {
        /** 与编排器共用同一个日志 tag，保证排障检索口径不变 */
        const val TAG = "AssetSyncManager"
    }
}
