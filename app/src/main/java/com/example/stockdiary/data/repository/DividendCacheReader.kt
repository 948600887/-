package com.example.stockdiary.data.repository

import com.example.stockdiary.data.dividend.DividendRecord
import com.example.stockdiary.database.DividendDao
import com.example.stockdiary.utils.StockCodeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 「抓过，且确实没有分红」这个结论的有效期（比 24h TTL 长得多，见 [shouldReuseEmptyResult]） */
internal const val NEGATIVE_RESULT_TTL = 7L * 24 * 60 * 60 * 1000

/**
 * 冷启动允许使用的过期数据上限。
 *
 * 比 [NEGATIVE_RESULT_TTL] 长得多是刻意的：打开 App 不该为了刷新「预计收入」
 * 去敲几十个接口。超过这个天数才判定「连过期数据都没有」，让调用方去抓。
 */
internal const val STALE_CEILING_MS = 30L * 24 * 60 * 60 * 1000

/**
 * 分红缓存读取（内存 Map + Room + 「抓过」时间戳三层判定）。
 *
 * 从 [DividendRepository] 外提：这里回答「这份数据能不能直接复用」，
 * 不负责抓取、写库或编排。返回值可能是**空列表**（= 抓过且没有分红，有效答案）；
 * 只有 null 才代表调用方该去抓。
 */
internal class DividendCacheReader(
    private val cache: DividendMemoryCache,
    private val state: DividendFetchStateStore,
    private val dao: DividendDao
) {

    /**
     * 冷启动专用的缓存读取：**TTL 过期也照用**，只留 [STALE_CEILING_MS] 这个硬上限。
     *
     * 返回值可能是空列表（抓过且确实没有分红），只有 null 才代表该去抓。
     */
    suspend fun getCachedAllowingStale(symbol: String): List<DividendRecord>? {
        val normalized = StockCodeManager.normalizeSymbol(symbol)

        // 先按正常 TTL 走一遍（命中就直接返回，顺带把内存缓存填上）
        tryGetFromCache(normalized)?.let { return it }

        // TTL 过期了：只要还没超过硬上限，就承认这份数据。
        // 只认抓取时间戳（库里记录本身不带时间戳，没法判断它有多旧）
        val stamp = state.lastFetchedAt(normalized)
        val freshEnough = stamp != null && System.currentTimeMillis() - stamp < STALE_CEILING_MS
        if (!freshEnough) return null

        return dividendRecordsFromDb(dao.getBySymbol(normalized))
    }

    /**
     * 批量版 [getCachedAllowingStale]：一次读 N 个标的。
     *
     * 语义必须与逐标的版本逐分支一致：
     * - 有记录 + 24h 内抓过 → 用记录（并回填内存缓存）
     * - 有记录 + 24h 外但 30 天内 → 仍用旧记录（宽口径），默认不回填；[primeCache] 时回填
     * - 无记录 + 7 天内抓过 → 「没有分红」是结论，返回空列表
     * - 无记录 + 7 天外但 30 天内 → 同样是空列表
     * - 超过 30 天 → null（让调用方去抓）
     */
    suspend fun getCachedAllowingStaleBatch(
        symbols: List<String>,
        primeCache: Boolean = false
    ): Map<String, List<DividendRecord>?> = withContext(Dispatchers.IO) {
        if (symbols.isEmpty()) return@withContext emptyMap()
        val normalized = symbols.map { StockCodeManager.normalizeSymbol(it) }.distinct()
        val now = System.currentTimeMillis()

        // 抓取时间戳一次全读，取代逐标的 N 次同步 SP 读
        val stamps: Map<String, Long> = state.allLongEntries()

        val out = HashMap<String, List<DividendRecord>?>(normalized.size)
        val needDb = ArrayList<String>()
        for (symbol in normalized) {
            val cached = cache.get(symbol)
            if (cached != null && cached.isValid(DIVIDEND_CACHE_TTL_MS)) {
                cache.recordHit()
                out[symbol] = cached.data
            } else {
                needDb.add(symbol)
            }
        }
        if (needDb.isEmpty()) return@withContext out

        // 一次 IN 查询取代 N 次按 symbol 查（240 个远低于 SQLite 的 999 变量上限）
        val rowsBySymbol = dao.getBySymbols(needDb)
            .groupBy { StockCodeManager.normalizeSymbol(it.symbol) }

        for (symbol in needDb) {
            val dbRecords = rowsBySymbol[symbol].orEmpty()
            val stamp = stamps[symbol]
            val fetchedRecently = stamp != null && now - stamp < DIVIDEND_CACHE_TTL_MS
            val withinStaleCeiling = stamp != null && now - stamp < STALE_CEILING_MS

            if (dbRecords.isNotEmpty()) {
                if (fetchedRecently) {
                    val records = dividendRecordsFromDb(dbRecords)
                    // 与逐标的路径一致：数据库恢复的缓存不标记为全量拉取
                    cache.put(symbol, DividendCacheEntry(records, now, lastFullFetch = 0L))
                    out[symbol] = records
                } else if (withinStaleCeiling) {
                    // 宽口径：过期几天的记录照样用。默认不回填内存缓存（照抄逐标的版本）；
                    // 调用方显式要预热时才回填，见 [primeCache] 参数说明
                    if (primeCache) {
                        cache.put(
                            symbol,
                            DividendCacheEntry(
                                dividendRecordsFromDb(dbRecords),
                                now,
                                lastFullFetch = stamp ?: 0L
                            )
                        )
                    }
                    out[symbol] = dividendRecordsFromDb(dbRecords)
                } else {
                    out[symbol] = null
                }
                continue
            }

            // 库里一条都没有：先按 [shouldReuseEmptyResult] 判「抓过且没有分红」
            if (shouldReuseEmptyResult(
                    dbRecordCount = 0,
                    lastFetchedAt = stamp,
                    now = now,
                    ttl = NEGATIVE_RESULT_TTL
                )
            ) {
                cache.recordHit()
                // 负缓存沿用**原始抓取时间**，不写成 now：
                // CacheEntry.timestamp 是 TTL 起点，写成 now 等于每次读取都把
                // 「抓过且没分红」的结论重新续期，7 天负结果窗口会被无限滑动。
                val fetchedAt = stamp ?: now
                cache.put(symbol, DividendCacheEntry(emptyList(), fetchedAt, lastFullFetch = fetchedAt))
                out[symbol] = emptyList()
            } else if (withinStaleCeiling) {
                // 逐标的路径这里会再查一次 DB（空）后返回空列表；批量版 DB 已查过，直接给空
                out[symbol] = emptyList()
            } else {
                out[symbol] = null
            }
        }
        out
    }

    /**
     * 把库里有记录的标的预热进内存缓存，返回「仍然需要联网抓取」的 symbol 列表。
     *
     * 冷启动先用本方法做一次宽口径批量读（零网络、2 次 IO）把有库记录的标的
     * 预热进内存缓存，只让「连过期数据都没有」的标的去联网 —— 否则日历页会为
     * 每个标的都判 TTL 过期、全量重抓，`isLoading` 转圈到预算耗尽。
     *
     * 预热会把过期数据的 timestamp 记成 now，意味着接下来 24h 内这些标的不会因
     * 「有新公告」而重抓；这是有意的：冷启动先用旧数据把界面填上，权威刷新由
     * 同步链路（forceRefresh）与用户手动刷新负责。
     *
     * @param forceRefresh true = 不预热、直接返回全部 symbol 让调用方全量重抓
     * @return 仍需抓取的 symbol（**原始 symbol**，未规范化）
     */
    suspend fun prime(symbols: List<String>, forceRefresh: Boolean): List<String> {
        if (symbols.isEmpty()) return emptyList()
        if (forceRefresh) return symbols
        val cached = getCachedAllowingStaleBatch(symbols, primeCache = true)
        return symbols.filter { cached[StockCodeManager.normalizeSymbol(it)] == null }
            .distinct()
    }

    /**
     * 按「内存 24h → 库记录 + 抓取时间 → 空结果负缓存」顺序判定。
     *
     * 有分红记录的标的必须受 24h 短 TTL 约束（到账日/金额会随新公告变）：
     * 从前这里不看抓取时间直接返回 DB 记录，导致抓过一次后永远不再抓。
     * 空结果用长得多的 [NEGATIVE_RESULT_TTL]：一只从没分过红的标的不会明天突然分红。
     */
    suspend fun tryGetFromCache(normalizedSymbol: String): List<DividendRecord>? {
        val cached = cache.get(normalizedSymbol)
        if (cached != null && cached.isValid(DIVIDEND_CACHE_TTL_MS)) {
            cache.recordHit()
            return cached.data
        }

        val dbRecords = dao.getBySymbol(normalizedSymbol)
        if (dbRecords.isNotEmpty()) {
            val stamp = state.lastFetchedAt(normalizedSymbol)
            if (stamp == null || System.currentTimeMillis() - stamp >= DIVIDEND_CACHE_TTL_MS) {
                return null
            }
            val records = dividendRecordsFromDb(dbRecords)
            // 数据库恢复的缓存不标记为全量拉取
            cache.put(normalizedSymbol, DividendCacheEntry(records, System.currentTimeMillis(), lastFullFetch = 0L))
            return records
        }

        val stamp = state.lastFetchedAt(normalizedSymbol)
        if (shouldReuseEmptyResult(
                dbRecordCount = dbRecords.size,
                lastFetchedAt = stamp,
                now = System.currentTimeMillis(),
                ttl = NEGATIVE_RESULT_TTL
            )
        ) {
            cache.recordHit()
            cache.put(
                normalizedSymbol,
                DividendCacheEntry(
                    emptyList(),
                    System.currentTimeMillis(),
                    lastFullFetch = stamp ?: 0L
                )
            )
            return emptyList()
        }

        return null
    }
}
