package com.example.stockdiary.network

import com.example.stockdiary.data.price.SourceStats
import com.example.stockdiary.utils.FailureLogFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 基金 / 加密链路的轮次级源统计计数器，产出与股票链路**同一个** [SourceStats] 模型。
 *
 * 为什么不各自另造一个统计结构：三条链路的快照最终都进 `SyncResult.sourceStats`，
 * 提示渲染只认一个模型。字段缺失一律 null，不用 0 冒充「没有发生」。
 *
 * ## 为什么按 roundId 隔离
 *
 * 从前这里是**进程级单份计数、每轮开头 reset()**：手动刷新轮与后台 Worker 轮并发时，
 * 后开的一轮会把先开那轮的计数清空，于是先结束那轮的轮次汇总只统计到「清零之后」，
 * 表现为「同一源的成功数忽大忽小、甚至整源消失」。现在每个 roundId 一组独立计数，
 * 各轮互不清零；轮末由调用方 [discard] 回收，避免轮次字典无限增长。
 * 非同步路径（前台单标的查价、导入回显）没有 roundId 上下文，统一落在 roundId=0 桶里。
 *
 * 线程安全：基金净值按 4/2 并发、加密按批并发写，计数必须原子；耗时用累加。
 * 计数器只做观测，任何写失败都不应影响取价主流程，故不加锁、不抛异常。
 * 原因分布用 ConcurrentHashMap + AtomicInteger，桶名由 [FailureLogFormat.reasonBucket] 归一。
 */
internal class SourceStatsCounter(
    private val source: String,
    private val host: String? = null
) {
    /** 按轮次隔离的一组原子计数 */
    private class RoundCounters {
        val success = AtomicInteger(0)
        val sourceFailures = AtomicInteger(0)
        val symbolFailures = AtomicInteger(0)
        val skipped = AtomicInteger(0)
        val durationMs = AtomicLong(0)
        val lastFailureReason = AtomicReference<String?>(null)
        val degradedReason = AtomicReference<String?>(null)

        /** 批量分片结果只在本轮结束时一次性写入；没有批量请求就保持 null */
        @Volatile
        var batchShards: Int? = null

        @Volatile
        var batchFailedShards: Int? = null

        /** 源级失败的原因分布（归一后的桶 → 次数） */
        val failureReasons = ConcurrentHashMap<String, AtomicInteger>()

        /** 跳过原因分布（归一后的桶 → 次数） */
        val skipReasons = ConcurrentHashMap<String, AtomicInteger>()
    }

    private val rounds = ConcurrentHashMap<Int, RoundCounters>()

    private fun counters(roundId: Int): RoundCounters =
        rounds.computeIfAbsent(roundId) { RoundCounters() }

    // ==================== 旧无参签名：委托到 roundId=0 ====================
    //
    // 非同步路径与既有单测都用这组签名；保留它们可以避免为「轮次隔离」改遍所有调用点，
    // 同时把语义钉死在「roundId=0 那个桶」。新代码必须显式传 roundId。

    /** 清空 roundId=0 的统计（兼容旧签名） */
    fun reset() = reset(0)

    fun recordSuccess() = recordSuccess(0, 1)

    fun recordSourceFailure(reason: String, count: Int = 1) =
        recordSourceFailure(0, reason, count)

    fun recordSymbolFailure() = recordSymbolFailure(0, 1)

    fun addDuration(ms: Long) = addDuration(0, ms)

    fun markDegraded(reason: String) = markDegraded(0, reason)

    fun setBatchShards(total: Int, failed: Int) = setBatchShards(0, total, failed)

    fun hasActivity(): Boolean = hasActivity(0)

    fun snapshot(): SourceStats = snapshot(0)

    // ==================== 按 roundId 隔离的新签名 ====================

    /** 清零某一轮的统计：直接丢弃该轮桶，后续写入会重新建桶（并发轮次不受影响） */
    fun reset(roundId: Int) {
        rounds.remove(roundId)
    }

    /** 轮末释放该轮桶，避免轮次字典无限增长（快照之后调用） */
    fun discard(roundId: Int) {
        rounds.remove(roundId)
    }

    fun recordSuccess(roundId: Int, count: Int = 1) {
        if (count > 0) counters(roundId).success.addAndGet(count)
    }

    /** 源级失败：超时/连不上/非 2xx/响应结构坏等「这个源本身不行」的情况 */
    fun recordSourceFailure(roundId: Int, reason: String, count: Int = 1) {
        if (count <= 0) return
        val c = counters(roundId)
        c.sourceFailures.addAndGet(count)
        reason.takeIf { it.isNotBlank() }?.let { c.lastFailureReason.set(it.take(60)) }
        c.failureReasons.bump(FailureLogFormat.reasonBucket(reason), count)
    }

    /** 符号级失败：源正常应答，但这个代码没有数据/不在覆盖范围 */
    fun recordSymbolFailure(roundId: Int, count: Int = 1) {
        if (count > 0) counters(roundId).symbolFailures.addAndGet(count)
    }

    /**
     * 记录一次「跳过」：停牌、主机退避冷却、响应里没有该代码等。
     * 跳过 ≠ 失败 —— 只是没走这个源，不增加源级/符号级失败计数。
     */
    fun recordSkip(roundId: Int, reason: String?) {
        val c = counters(roundId)
        c.skipped.incrementAndGet()
        c.skipReasons.bump(FailureLogFormat.reasonBucket(reason))
    }

    fun addDuration(roundId: Int, ms: Long) {
        if (ms > 0) counters(roundId).durationMs.addAndGet(ms)
    }

    /** 只记录降级事实，不增加任何失败计数（例如「已熔断，本轮未请求」） */
    fun markDegraded(roundId: Int, reason: String) {
        reason.takeIf { it.isNotBlank() }?.let { counters(roundId).degradedReason.set(it.take(80)) }
    }

    /** 批量分片结果；total 为 0 时保持 null（这一轮压根没走批量） */
    fun setBatchShards(roundId: Int, total: Int, failed: Int) {
        if (total <= 0) return
        val c = counters(roundId)
        c.batchShards = total
        c.batchFailedShards = failed.coerceIn(0, total)
    }

    /** 本轮是否有任何观测值；没有活动的源不应出现在快照里制造噪音 */
    fun hasActivity(roundId: Int): Boolean {
        val c = rounds[roundId] ?: return false
        return c.success.get() > 0 ||
            c.sourceFailures.get() > 0 ||
            c.symbolFailures.get() > 0 ||
            c.skipped.get() > 0 ||
            c.durationMs.get() > 0 ||
            c.lastFailureReason.get() != null ||
            c.degradedReason.get() != null ||
            c.batchShards != null
    }

    fun snapshot(roundId: Int): SourceStats {
        val c = rounds[roundId]
        return SourceStats(
            source = source,
            success = c?.success?.get() ?: 0,
            sourceLevelFailure = c?.sourceFailures?.get() ?: 0,
            symbolLevelFailure = c?.symbolFailures?.get() ?: 0,
            durationMs = c?.durationMs?.get() ?: 0L,
            lastFailureReason = c?.lastFailureReason?.get(),
            host = host,
            batchShards = c?.batchShards,
            batchFailedShards = c?.batchFailedShards,
            degradedReason = c?.degradedReason?.get(),
            skipped = c?.skipped?.get() ?: 0,
            // 按 key 排序保证快照稳定，轮次汇总才能逐轮对比
            failureReasons = c?.failureReasons?.toSortedCounts() ?: emptyMap(),
            skipReasons = c?.skipReasons?.toSortedCounts() ?: emptyMap()
        )
    }

    private fun ConcurrentHashMap<String, AtomicInteger>.toSortedCounts(): Map<String, Int> =
        entries.sortedBy { it.key }.associate { it.key to it.value.get() }

    /** 原因分布的计数：并发写安全；桶名由 [FailureLogFormat.reasonBucket] 归一 */
    private fun ConcurrentHashMap<String, AtomicInteger>.bump(bucket: String, count: Int = 1) {
        computeIfAbsent(bucket) { AtomicInteger() }.addAndGet(count)
    }
}
