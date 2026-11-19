package com.example.stockdiary.data.repository

import com.example.stockdiary.data.dividend.DividendRecord
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** 内存缓存 TTL：有分红记录的标的 24 小时必须重抓（到账日/金额会随新公告变） */
internal const val DIVIDEND_CACHE_TTL_MS = 24L * 60 * 60 * 1000

/** 分红缓存上限：与行情侧各缓存同量级（~200），避免长会话里无限增长 */
private const val MAX_CACHE_ENTRIES = 200

/**
 * 一条分红缓存。
 *
 * [timestamp] 是本条记录的 TTL 起点；[lastFullFetch] 是最近一次全量拉取时间，
 * 用来决定增量合并还是全量刷新。
 */
internal data class DividendCacheEntry(
    val data: List<DividendRecord>,
    val timestamp: Long,
    val lastFullFetch: Long = System.currentTimeMillis()
) {
    fun isValid(ttl: Long): Boolean = System.currentTimeMillis() - timestamp < ttl

    fun needsFullFetch(): Boolean =
        System.currentTimeMillis() - lastFullFetch > 30L * 24 * 60 * 60 * 1000
}

/**
 * 分红内存缓存（纯状态设施）。
 *
 * 只负责 Map 的读写、命中/未命中计数与容量裁剪，判定逻辑留在
 * [DividendCacheReader] 与 [DividendRepository]。从仓库类外提，是为了让
 * 「改缓存容量 / TTL 起点 / 淘汰规则」只需要看这一个文件。
 *
 * [put] 刻意**不自动裁剪**：原实现只在部分写入点调用 `trimCacheIfNeeded`，
 * 若统一成每次 put 都裁剪，会改变批量读路径的淘汰时机。需要裁剪的调用点
 * 显式调用 [trimIfNeeded]，行为与改造前逐处一致。
 */
internal class DividendMemoryCache {

    private val cache = ConcurrentHashMap<String, DividendCacheEntry>()
    private val hits = AtomicInteger(0)
    private val misses = AtomicInteger(0)

    fun get(symbol: String): DividendCacheEntry? = cache[symbol]

    fun put(symbol: String, entry: DividendCacheEntry) {
        cache[symbol] = entry
    }

    fun remove(symbol: String) {
        cache.remove(symbol)
    }

    fun clear() {
        cache.clear()
    }

    fun recordHit() {
        hits.incrementAndGet()
    }

    fun recordMiss() {
        misses.incrementAndGet()
    }

    /** 缓存超过上限时淘汰最旧条目（按写入时间戳）；最近写入的本次刷新数据不会被误删 */
    fun trimIfNeeded() {
        if (cache.size <= MAX_CACHE_ENTRIES) return
        val entries = cache.entries.sortedBy { it.value.timestamp }
        val removeCount = cache.size - MAX_CACHE_ENTRIES
        entries.take(removeCount).forEach { cache.remove(it.key) }
    }

    fun stats(): Map<String, Any> = mapOf(
        "size" to cache.size,
        "hits" to hits.get(),
        "misses" to misses.get()
    )
}
