package com.example.stockdiary.network

import com.example.stockdiary.data.price.SourceStats
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 基金 / 加密链路的轮次级源统计计数器，产出与股票链路**同一个** [SourceStats] 模型。
 *
 * 为什么不各自另造一个统计结构：三条链路的快照最终都进 `SyncResult.sourceStats`，
 * 提示渲染只认一个模型。字段缺失一律 null，不用 0 冒充「没有发生」。
 *
 * 线程安全：基金净值按 4/2 并发、加密按批并发写，计数必须原子；耗时用累加。
 * 计数器只做观测，任何写失败都不应影响取价主流程，故不加锁、不抛异常。
 */
internal class SourceStatsCounter(
    private val source: String,
    private val host: String? = null
) {
    private val success = AtomicInteger(0)
    private val sourceFailures = AtomicInteger(0)
    private val symbolFailures = AtomicInteger(0)
    private val durationMs = AtomicLong(0)
    private val lastFailureReason = AtomicReference<String?>(null)
    private val degradedReason = AtomicReference<String?>(null)

    /** 批量分片结果只在本轮结束时一次性写入；没有批量请求就保持 null */
    @Volatile
    private var batchShards: Int? = null

    @Volatile
    private var batchFailedShards: Int? = null

    fun reset() {
        success.set(0)
        sourceFailures.set(0)
        symbolFailures.set(0)
        durationMs.set(0)
        lastFailureReason.set(null)
        degradedReason.set(null)
        batchShards = null
        batchFailedShards = null
    }

    fun recordSuccess(count: Int = 1) {
        if (count > 0) success.addAndGet(count)
    }

    /** 源级失败：超时/连不上/非 2xx/响应结构坏等「这个源本身不行」的情况 */
    fun recordSourceFailure(reason: String, count: Int = 1) {
        if (count > 0) sourceFailures.addAndGet(count)
        reason.takeIf { it.isNotBlank() }?.let { lastFailureReason.set(it.take(60)) }
    }

    /** 符号级失败：源正常应答，但这个代码没有数据/不在覆盖范围 */
    fun recordSymbolFailure(count: Int = 1) {
        if (count > 0) symbolFailures.addAndGet(count)
    }

    fun addDuration(ms: Long) {
        if (ms > 0) durationMs.addAndGet(ms)
    }

    /** 只记录降级事实，不增加任何失败计数（例如「已熔断，本轮未请求」） */
    fun markDegraded(reason: String) {
        reason.takeIf { it.isNotBlank() }?.let { degradedReason.set(it.take(80)) }
    }

    /** 批量分片结果；total 为 0 时保持 null（这一轮压根没走批量） */
    fun setBatchShards(total: Int, failed: Int) {
        if (total > 0) {
            batchShards = total
            batchFailedShards = failed.coerceIn(0, total)
        }
    }

    /** 本轮是否有任何观测值；没有活动的源不应出现在快照里制造噪音 */
    fun hasActivity(): Boolean =
        success.get() > 0 ||
            sourceFailures.get() > 0 ||
            symbolFailures.get() > 0 ||
            durationMs.get() > 0 ||
            lastFailureReason.get() != null ||
            degradedReason.get() != null ||
            batchShards != null

    fun snapshot(): SourceStats = SourceStats(
        source = source,
        success = success.get(),
        sourceLevelFailure = sourceFailures.get(),
        symbolLevelFailure = symbolFailures.get(),
        durationMs = durationMs.get(),
        lastFailureReason = lastFailureReason.get(),
        host = host,
        batchShards = batchShards,
        batchFailedShards = batchFailedShards,
        degradedReason = degradedReason.get()
    )
}
