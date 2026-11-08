package com.example.stockdiary.data.price

import android.annotation.SuppressLint
import android.content.Context
import com.example.stockdiary.common.Result
import com.example.stockdiary.network.EastmoneySkillApi
import com.example.stockdiary.network.NetworkRetryHelper
import com.example.stockdiary.network.QuoteSuspendedException
import com.example.stockdiary.network.StalePriceCacheException
import com.example.stockdiary.network.SinaStockAPI
import com.example.stockdiary.network.SinaStockData
import com.example.stockdiary.network.TencentStockAPI
import com.example.stockdiary.network.model.StockData
import com.example.stockdiary.utils.AppLogger
import com.example.stockdiary.utils.FailureLogFormat
import com.example.stockdiary.utils.StockCodeManager
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException

/**
 * 单个数据源的进程内统计快照（供同步轮次末的结构化汇总打印）。
 *
 * 为什么需要：真机日志里要判断「新浪是不是全挂了」只能靠人肉 `grep -c` 数成功次数，
 * 而这种源级健康度应该是日志的一等公民。三个计数分别对应
 * [StockDataManager.getStockData] 里的三条出口：成功 / 源级失败 / 符号级失败。
 */
data class SourceStats(
    val source: String,
    val success: Int,
    val sourceLevelFailure: Int,
    val symbolLevelFailure: Int,
    /** 本轮该源累计耗时（含重试与网络等待），用于识别慢源；无调用为 0 */
    val durationMs: Long = 0L,
    /**
     * 最近一次源级失败原因（已截短供提示用）；只在该源发生源级失败时非空。
     * 符号级失败（代码不在此源支持范围）不是源故障，不写这里。
     */
    val lastFailureReason: String? = null,
    /** 本轮实际使用的主机/域名（基金轻量、legacy、币安镜像等）；无法归类时保持 null */
    val host: String? = null,
    /** 批量源的分片总数；非批量源或本轮未发批量请求时为 null（不用 0 占位） */
    val batchShards: Int? = null,
    /** 失败分片数（含切小重试后仍失败的分片）；非批量源为 null */
    val batchFailedShards: Int? = null,
    /**
     * 不表现为「源级失败计数」但需要用户知道的降级原因，例如：
     * 轻量基金源已熔断本轮全走 legacy、币安 451 粘死后未发请求、整片 400 切小重试。
     * null = 本轮无此类降级。
     */
    val degradedReason: String? = null,
    /** 本轮该源「跳过」的标的数（停牌、代码不在覆盖范围、熔断冷却等）；跳过≠失败 */
    val skipped: Int = 0,
    /** 源级失败的原因分布（归一后的桶 → 次数），供轮次汇总一行说清「为什么失败」 */
    val failureReasons: Map<String, Int> = emptyMap(),
    /** 跳过原因分布（归一后的桶 → 次数），供轮次汇总说明「跳过了哪些、为什么」 */
    val skipReasons: Map<String, Int> = emptyMap()
)

/** 单个数据源的计数器（按轮次隔离，见 [StockDataManager.sourceCountersByRound]） */
private class SourceCounters {
    val success = java.util.concurrent.atomic.AtomicInteger()
    val sourceFailure = java.util.concurrent.atomic.AtomicInteger()
    val symbolFailure = java.util.concurrent.atomic.AtomicInteger()
    val skipped = java.util.concurrent.atomic.AtomicInteger()
    val durationMs = java.util.concurrent.atomic.AtomicLong()
    val lastFailureReason = java.util.concurrent.atomic.AtomicReference<String?>(null)
    val failureReasons = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
    val skipReasons = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
}

/**
 * 股票数据管理器 v2
 * 支持多种数据源：妙想Skill、新浪财经、腾讯财经
 * 妙想Skill作为默认优先数据源
 */
class StockDataManager(
    private val context: Context
) {
    /**
     * 每个数据源的三态熔断器（CLOSED/OPEN/HALF_OPEN），取代旧的「失败计数 + 30min delay 禁用」。
     * 详见 [SourceCircuitBreaker]：只有**源级**失败才计入；符号级确定性失败（代码不在此源
     * 支持范围）与停牌不计，避免单波抖动误禁整源、把流量全甩给单一源引发雪崩。
     */
    private val breakers = ConcurrentHashMap<String, SourceCircuitBreaker>()

    /**
     * 取（或建）该源的熔断器。用 [ConcurrentHashMap.computeIfAbsent] 而非 stdlib `getOrPut`：
     * getOrPut 非原子（get→defaultValue→putIfAbsent 且返回本地新建实例），冷图 + 并发时
     * 同一 key 的两个线程可能各持一个 breaker 实例（状态分裂，QA 已确定性实证）。
     * computeIfAbsent 的 mapping function 在桶锁内至多执行一次，同一 key 全局唯一实例。
     */
    private fun breakerFor(apiName: String): SourceCircuitBreaker =
        breakers.computeIfAbsent(apiName) { SourceCircuitBreaker() }

    /**
     * 每个数据源的成功 / 源级失败 / 符号级失败计数，**按 roundId 隔离**。
     *
     * 从前是进程级单份计数、每轮开头 clear()：手动轮与后台 Worker 并发时，
     * 后开的一轮会把先开那轮的计数清空，于是先结束那轮的汇总只统计到「清零之后」。
     * 现在每个 roundId 一份，互不影响；轮末快照后由 [discardSourceStats] 回收。
     * 非同步路径（前台单标的查价）统一落在 roundId=0 桶里。
     *
     * 与熔断器的 [SourceCircuitBreaker.failuresForTesting] 不同：那边是「连续失败计数、
     * 成功即清零」的**状态机输入**；这边是「本轮一共问成/问败多少」的**统计快照**，
     * 只在同步轮次末打汇总用，不参与任何判定。故意分开，避免为了统计去污染状态机。
     */
    private val sourceCountersByRound = ConcurrentHashMap<Int, ConcurrentHashMap<String, SourceCounters>>()

    private fun roundCounters(roundId: Int): ConcurrentHashMap<String, SourceCounters> =
        sourceCountersByRound.computeIfAbsent(roundId) { ConcurrentHashMap() }

    private fun countersFor(apiName: String, roundId: Int): SourceCounters =
        roundCounters(roundId).computeIfAbsent(apiName) { SourceCounters() }

    /** 记录一次成功取价（批量成功按标的数累加） */
    internal fun recordSourceSuccess(apiName: String, roundId: Int, count: Int = 1) {
        if (count > 0) countersFor(apiName, roundId).success.addAndGet(count)
    }

    /** 记录源级失败，同时累计归一化后的原因桶（供轮次汇总的原因分布） */
    internal fun recordSourceFailure(apiName: String, roundId: Int, reason: String?) {
        val c = countersFor(apiName, roundId)
        c.sourceFailure.incrementAndGet()
        c.lastFailureReason.set(reason?.take(60))
        c.failureReasons.bump(FailureLogFormat.reasonBucket(reason))
    }

    /** 记录一次「跳过」（停牌/代码不在覆盖范围/熔断冷却），不计入失败 */
    internal fun recordSourceSkip(apiName: String, roundId: Int, reason: String?) {
        val c = countersFor(apiName, roundId)
        c.skipped.incrementAndGet()
        c.skipReasons.bump(FailureLogFormat.reasonBucket(reason))
    }

    /** 累计源耗时（含网络等待与重试） */
    internal fun addSourceDuration(apiName: String, roundId: Int, ms: Long) {
        if (ms > 0) countersFor(apiName, roundId).durationMs.addAndGet(ms)
    }

    /** 清零某一轮的各源统计（保留兼容签名：roundId=0 用于非同步路径与既有单测） */
    fun resetSourceStats() {
        resetSourceStats(0)
    }

    /** 只清指定轮次，不碰其它并发轮次 */
    fun resetSourceStats(roundId: Int) {
        sourceCountersByRound.remove(roundId)
    }

    /** 轮末释放：快照之后调用，避免轮次字典无限增长 */
    fun discardSourceStats(roundId: Int) {
        sourceCountersByRound.remove(roundId)
    }

    /** 各源统计快照（按源名排序，保证汇总行顺序稳定，方便逐轮对比） */
    fun sourceStatsSnapshot(): List<SourceStats> = sourceStatsSnapshot(0)

    /** 指定轮次的各源统计快照：**只含本轮**，不受并发轮次影响 */
    fun sourceStatsSnapshot(roundId: Int): List<SourceStats> {
        val counters = sourceCountersByRound[roundId] ?: return emptyList()
        return counters.entries
            .sortedBy { it.key }
            .map { (name, c) ->
                SourceStats(
                    source = name,
                    success = c.success.get(),
                    sourceLevelFailure = c.sourceFailure.get(),
                    symbolLevelFailure = c.symbolFailure.get(),
                    durationMs = c.durationMs.get(),
                    lastFailureReason = c.lastFailureReason.get(),
                    skipped = c.skipped.get(),
                    failureReasons = c.failureReasons.toSortedCounts(),
                    skipReasons = c.skipReasons.toSortedCounts()
                )
            }
    }

    private fun ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>.toSortedCounts(): Map<String, Int> =
        entries.sortedBy { it.key }.associate { it.key to it.value.get() }

    /** 原因分布的计数：并发写安全，桶名由 [FailureLogFormat.reasonBucket] 归一 */
    private fun ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>.bump(bucket: String) {
        computeIfAbsent(bucket) { java.util.concurrent.atomic.AtomicInteger() }.incrementAndGet()
    }

    /**
     * 价格是否可用于"写缓存 + 认定成功"。
     *
     * 必须用 isFinite 而不是简单的 `> 0`：NaN 参与的任何比较都返回 false，
     * 所以 `price <= 0` 拦不住 NaN，NaN 会一路写进资产表。
     */
    private fun isValidPrice(price: Double): Boolean = price.isFinite() && price > 0.0
    
    /** 内存缓存上限：与 PriceCacheManager.MAX_MEMORY_CACHE_SIZE / SinaStockAPI.MAX_CACHE_SIZE 同量级（~200），避免长会话无限增长 */
    private val MAX_MEMORY_CACHE_ENTRIES = 200

    // 数据缓存（内存缓存）。统一实现见 [BoundedTtlCache]；这里**不设自己的 TTL**，
    // 新鲜度仍完全委托 [PriceCacheManager.isCacheValid]（动态交易时段口径），
    // 以免合并缓存实现时改掉取价新鲜度。
    private val cache = BoundedTtlCache<String, StockData>(MAX_MEMORY_CACHE_ENTRIES)

    // 数据源实例缓存
    private val dataSourceCache = ConcurrentHashMap<String, StockDataSource>()

    init {
        PriceCacheManager.init(context)
    }
    
    companion object {
        private const val TAG = "StockDataManager"
        private const val MAX_CONCURRENT_REQUESTS = 5

        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: StockDataManager? = null
        
        fun getInstance(context: Context): StockDataManager {
            return instance ?: synchronized(this) {
                instance ?: StockDataManager(context.applicationContext).also { instance = it }
            }
        }
    }
    
    private val requestSemaphore = Semaphore(MAX_CONCURRENT_REQUESTS)
    
    /**
     * 获取数据源实例
     */
    private fun getDataSource(apiName: String): StockDataSource {
        return dataSourceCache.getOrPut(apiName) {
            when (apiName) {
                ApiManager.API_SKILL -> SkillDataSource()
                ApiManager.API_SINA -> networkStockDataSource("新浪财经") { SinaStockAPI.getStockData(it) }
                ApiManager.API_TENCENT -> networkStockDataSource("腾讯财经") { TencentStockAPI.getStockData(it) }
                else -> SkillDataSource()
            }
        }
    }
    
    /**
     * 获取当前数据源列表（根据用户选择排序，并过滤掉不可用的候选）
     */
    private fun getCurrentDataSources(): List<Pair<String, StockDataSource>> {
        return candidateSourceNames().map { apiName ->
            apiName to getDataSource(apiName)
        }
    }

    /**
     * 候选数据源名（**全自动**顺序，不读任何用户偏好）。
     *
     * 数据源选择已于 2026-10-23 改为全自动（应用户要求去掉「我的 → 行情数据源」设置）：
     * 顺序固定为「妙想Skill(仅当配置了 API Key) > 新浪 > 腾讯」，某个源连续失败时由
     * 数据源级熔断器自动短期禁用、自动降级到下一个，不需要用户插手。
     *
     * 过滤规则：妙想未配置 API Key 时移出候选链——否则每次刷新都白试一遍、失败还计入源级
     * 禁用计数（[EastmoneySkillApi] 对空 Key 直接返回「未配置东方财富 Skill API Key」）。
     * 配置了 Key 就自动置首，同样不需要用户手动选。
     *
     * 抽出来便于单测（妙想 Key 的过滤 + 自动顺序）。
     */
    internal fun candidateSourceNames(): List<String> {
        val skillConfigured = EastmoneySkillApi.isApiKeyConfigured()
        return listOf(ApiManager.API_SKILL, ApiManager.API_SINA, ApiManager.API_TENCENT)
            .filter { it != ApiManager.API_SKILL || skillConfigured }
    }
    
    /**
     * 获取股票数据（带容错机制）
     *
     * @param forceRefresh 跳过内存/持久化缓存强制重抓
     * @param networkAvailable 本次同步开始时一次性算好的网络可用性；非 null 时直接采信，
     *        不再每个标的都去问 ConnectivityManager（避免 O(n) 冗余 binder 调用，见 P4）。
     *        为 null 时回落到 [isNetworkAvailable] 实时判断。
     */
    suspend fun getStockData(
        symbol: String,
        forceRefresh: Boolean = false,
        networkAvailable: Boolean? = null
    ): Result<StockData> {
        // 本轮同步编号：没有 RoundSourceFailureScope 上下文时为 0（前台单标的查价等）
        val roundId = RoundSourceFailureScope.currentRoundId()
        // 1. 检查是否需要强制刷新
        if (!forceRefresh) {
            // 2. 检查内存缓存
            val cached = cache.get(symbol)
            if (cached != null && PriceCacheManager.isCacheValid(symbol)) {
                AppLogger.d("使用内存缓存数据: $symbol", TAG)
                return Result.success(cached.value)
            }
            
            // 3. 检查 PriceCacheManager 持久化缓存。
            //
            // 复用它自己的 TTL 判定（交易时段 5min；16 点后 / 周末 MAX_VALUE = 收盘价复用，
            // 是刻意设计，本阶段不改它的口径）：只有仍在 TTL 内的缓存才能以 success
            // 身份返回，已过期的一律往下走，由 [StalePriceCacheException] 明确告诉调用方
            // 「只有旧价、不是新价，别重新落库」。
            // 过期分支直接跳过 getCachedPrice（不再依赖它内部「判 TTL 并顺手移除条目」的
            // 清理副作用，本路径也就不动缓存条目）：显式保证「不返回过期价」成为可读、
            // 可测的契约。旧价仍留在缓存与库里，等网络 / 行情源恢复后自然更新。
            val persistedCache = if (PriceCacheManager.isCacheValid(symbol)) {
                PriceCacheManager.getCachedPrice(symbol)
            } else {
                null
            }
            if (persistedCache != null) {
                val stockData = StockData(
                    code = persistedCache.code,
                    name = persistedCache.name,
                    price = persistedCache.price,
                    change = persistedCache.change,
                    changePercent = persistedCache.changePercent,
                    open = persistedCache.open,
                    high = persistedCache.high,
                    low = persistedCache.low,
                    volume = persistedCache.volume,
                    amount = persistedCache.amount,
                    yesterdayClose = persistedCache.prevClose,
                    timestamp = System.currentTimeMillis()
                )
                cache.put(symbol, stockData)
                AppLogger.d("使用持久化缓存数据: $symbol", TAG)
                return Result.success(stockData)
            }
        }
        
        // 4. 检查网络连接（优先用同步开始时一次性算好的结果，避免每个标的重复 binder 调用）
        val netAvailable = networkAvailable ?: isNetworkAvailable()
        if (!netAvailable) {
            val noNetLine = FailureLogFormat.failureLine(roundId, symbol, "网络", "网络连接不可用")
            AppLogger.w(noNetLine, TAG)
            val oldCache = cache.get(symbol)
            if (oldCache != null) {
                if (PriceCacheManager.isCacheValid(symbol)) {
                    // 仍在 TTL 内（盘后/周末复用收盘价是刻意设计）：保持原语义复用旧价，
                    // 不清空旧价、不写 0，也不改 PriceCacheManager 的 TTL 口径。
                    AppLogger.d("网络不可用，复用仍在 TTL 内的缓存价: $symbol", TAG)
                    return Result.success(oldCache.value)
                }
                // 缓存真的过期了：不能再以 success 身份把旧价送回 syncSingleAsset。
                // 那会走 priceWriter → AssetDao.updatePriceBySymbol，把该行 lastSyncTime
                // 刷成「刚刚」，让三天前的收盘价在卡片上伪装成刚抓的新价。
                // 旧价本身保留在内存缓存与库里不动（不清空、不写 0）；同步编排器把本异常
                // 归到「跳过」（不算失败），等网络/行情源恢复后自然更新。
                AppLogger.i(
                    FailureLogFormat.skipLine(
                        roundId, symbol, "网络", "网络不可用且缓存已过 TTL（不返回旧价，保留库里旧值）"
                    ),
                    TAG
                )
                return Result.failure(StalePriceCacheException(symbol))
            }
            return Result.failure(IOException("网络连接不可用"))
        }
        
        // 5. 按优先级尝试各个数据源
        var lastError: Exception? = null
        val currentDataSources = getCurrentDataSources()
        
        for ((apiName, dataSource) in currentDataSources) {
            // 三态熔断闸门：CLOSED 放行；OPEN 冷却期跳过；冷却结束只放行一个探测（其余跳过）。
            // 取代旧的「失败计数 >= maxFailures 就跳过错过的全部请求」。
            if (!breakerFor(apiName).tryAcquireProbe(System.currentTimeMillis())) {
                AppLogger.i(
                    FailureLogFormat.skipLine(
                        roundId, symbol, apiName, "熔断冷却中 / 半开探测已在进行"
                    ),
                    TAG
                )
                recordSourceSkip(apiName, roundId, "熔断冷却中 / 半开探测已在进行")
                continue
            }

            AppLogger.d("尝试使用数据源: $apiName", TAG)

            // 耗时按「源」累计：包含该源的重试与网络等待，跨标的累加后能看出慢源。
            // 放在 try/finally 里，取消/抛异常时也记账，避免某一轮时长凭空变短。
            val sourceStartedAt = System.currentTimeMillis()
            val result = try {
                NetworkRetryHelper.executeWithRetry {
                    dataSource.getStockData(symbol)
                }
            } finally {
                addSourceDuration(apiName, roundId, System.currentTimeMillis() - sourceStartedAt)
            }
            
            if (result.isSuccess) {
                val stockData = result.getOrNull()
                // 拿到了"结果"不等于拿到了好数据：解析失败时各源会把现价落成 0.0/NaN，
                // 若照单全收就会写进三级缓存、把持仓市值算成 0。这里统一挡一道，
                // 并视作该数据源失败（继续找下一个源），而不是把它当成成功。
                if (stockData != null && isValidPrice(stockData.price)) {
                    // 成功：更新所有缓存
                    cache.put(symbol, stockData)
                    trimCacheIfNeeded()
                    
                    // 同时更新PriceCacheManager持久化缓存
                    //
                    // 昨收缺失时**不能**直接落成 0.0：离线读回会拿 prevClose=0 去算涨跌，
                    // 涨跌额等于整价（看上去像涨了 100%）。先沿用磁盘缓存里已有的非零昨收
                    // （?: 是惰性的，只有 yesterdayClose 真的缺失才会去读一次缓存）。
                    val prevClose = stockData.yesterdayClose?.takeIf { it > 0.0 }
                        ?: PriceCacheManager.getCachedPrice(symbol)?.prevClose?.takeIf { it > 0.0 }
                        ?: 0.0
                    val sinaData = SinaStockData(
                        name = stockData.name,
                        code = stockData.code,
                        price = stockData.price,
                        change = stockData.price - prevClose,
                        changePercent = if (prevClose > 0)
                            (stockData.price - prevClose) / prevClose * 100 else 0.0,
                        open = stockData.open ?: 0.0,
                        // prevClose 必须显式传进来：漏传会让缓存里恒为 0，离线取价的涨跌全错
                        prevClose = prevClose,
                        high = stockData.high ?: 0.0,
                        low = stockData.low ?: 0.0,
                        volume = stockData.volume ?: 0L
                    )
                    val fetchedAtStock = System.currentTimeMillis()
                    PriceCacheManager.cachePrice(symbol, sinaData, SourceIds.normalizeLegacySource(apiName))
                    // G5 血缘：股价落 PriceCacheManager 时上报来源（归一化 sourceId + 落库时间戳）。
                    // 这里是**实时抓取成功**，origin 必须是 IN_MEMORY；FILE_CACHE 只用于
                    // “命中磁盘旧价”的分支。轮次 ID 从本轮上下文取，避免 UI 展示错轮次。
                    PriceLineageRecorder.report(
                        symbol,
                        PriceLineage(
                            code = symbol,
                            sourceId = SourceIds.normalizeLegacySource(apiName),
                            origin = LineageOrigin.IN_MEMORY,
                            asOf = fetchedAtStock,
                            fetchedAt = fetchedAtStock,
                            roundId = currentCoroutineContext()[RoundSourceFailureScope.Key]?.roundId ?: 0
                        )
                    )

                    breakerFor(apiName).onSuccess()
                    recordSourceSuccess(apiName, roundId)
                    AppLogger.d("使用${apiName}获取数据成功: ${stockData.name}", TAG)
                    return Result.success(stockData)                }
                if (stockData != null) {
                    AppLogger.w(
                        FailureLogFormat.failureLine(
                            roundId,
                            symbol,
                            apiName,
                            "价格无效（${stockData.price}），视为失败并尝试下一个数据源",
                            System.currentTimeMillis() - sourceStartedAt
                        ),
                        TAG
                    )
                    // 别让下面那句 Exception("Unknown error") 盖掉真实原因：
                    // 日志里只看到 Unknown error 会完全误导排查方向。
                    lastError = IllegalStateException("${apiName}返回无效价格: ${stockData.price}")
                }
            }

            val failure = result.exceptionOrNull() as? Exception
                ?: lastError
                ?: Exception("Unknown error")
            lastError = failure

            // 停牌不是数据源的故障：换源去问同一只停牌股还是没有报价，
            // 但累计失败次数会在几次后把整个数据源禁用，表现为"全市场行情同步停摆"。
            if (QuoteSuspendedException.isSuspended(failure)) {
                AppLogger.i(
                    FailureLogFormat.skipLine(
                        roundId, symbol, apiName, "无有效报价（停牌/退市整理期），不计入数据源失败",
                        System.currentTimeMillis() - sourceStartedAt
                    ),
                    TAG
                )
                recordSourceSkip(apiName, roundId, "停牌/退市整理期无有效报价")
                continue
            }

            // 符号级确定性失败（这个代码不在此源支持范围）：换源去问同一只代码才对，
            // 但这不是「源不健康」——把它计入会在几次后误禁整源、把流量全甩给单一源引发雪崩，
            // 正是本轮要修的问题。分类标准见 [isSymbolLevelFailure]。
            if (isSymbolLevelFailure(failure)) {
                countersFor(apiName, roundId).symbolFailure.incrementAndGet()
                recordSourceSkip(apiName, roundId, "代码不在此源支持范围")
                AppLogger.i(
                    FailureLogFormat.skipLine(
                        roundId, symbol, apiName, "代码不在此源支持范围，不计入数据源失败",
                        System.currentTimeMillis() - sourceStartedAt
                    ),
                    TAG
                )
                continue
            }

            // 源级失败（超时 / 连接失败 / 空返回 / 格式坏 / 无效价格）：喂给熔断器。
            //
            // 同一同步轮次内，若该源已由「批量预取整体 0 命中」记过账（见
            // [reportBatchTotalMiss]），逐标的回落就不再重复喂熔断，也不重复累计统计——
            // 对齐币安侧「整轮只记一次成败」。否则「1 次批量 + N 个回落标的」= 1+N 次，
            // 单波并发抖动就能凑满阈值误熔整源（正是 8 > MAX_CONCURRENT_SYNC=6 想防的场景）。
            // 上下文只在同步扇出时由 AssetSyncManager 注入；前台单标的查价等路径没有它，
            // 真实 IO 失败照常记账。停牌 / 符号级失败在上面的 continue 分支已分流，不走到这里。
            val roundScope = currentCoroutineContext()[RoundSourceFailureScope.Key]
            if (roundScope?.isAlreadyReported(apiName) == true) {
                AppLogger.i(
                    FailureLogFormat.skipLine(
                        roundId, symbol, apiName,
                        "本轮已由批量0命中记账，逐标的回落不重复喂熔断: ${failure.message}",
                        System.currentTimeMillis() - sourceStartedAt
                    ),
                    TAG
                )
                recordSourceSkip(apiName, roundId, "批量已记账，逐标的回落不重复计数")
            } else {
                breakerFor(apiName).onFailure(System.currentTimeMillis())
                recordSourceFailure(apiName, roundId, failure.message ?: failure.javaClass.simpleName)
                AppLogger.w(
                    FailureLogFormat.failureLine(
                        roundId, symbol, apiName, failure.message,
                        System.currentTimeMillis() - sourceStartedAt
                    ),
                    TAG
                )
            }
        }
        
        // 6. 所有数据源都失败
        AppLogger.e(
            "${FailureLogFormat.failureLine(roundId, symbol, "全部数据源", lastError?.message, null)}" +
                "（本轮此标的取价失败，需处理后才会自愈）",
            tag = TAG
        )
        return Result.failure(lastError ?: IOException("所有数据源都失败"))
    }
    
    /**
     * 批量获取股票数据 - 并行请求优化（带并发限制）
     */
    suspend fun getBatchStockData(symbols: List<String>): Result<Map<String, StockData>> {
        return coroutineScope {
            val deferredResults = symbols.map { symbol ->
                async {
                    requestSemaphore.withPermit {
                        getStockData(symbol).let { result ->
                            symbol to result
                        }
                    }
                }
            }
            
            val results = deferredResults.awaitAll()
                .mapNotNull { (symbol, result) ->
                    result.getOrNull()?.let { symbol to it }
                }
                .toMap()
            
            if (results.isNotEmpty()) {
                Result.success(results)
            } else {
                Result.failure(IOException("无法获取任何股票数据"))
            }
        }
    }
    
    /**
     * 通过新浪批量接口一次性取回一批 STOCK/场内基金的行情。
     *
     * 这是 P3（批量取价）的核心：新浪的 [SinaStockAPI.getRealtimeData] 原生支持批量，
     * 会把代码拼成 `/list=CODE1,CODE2,…` 并按 [com.example.stockdiary.common.RequestLimits.BATCH_SIZE]=20
     * 自动分块，配合 `batchPendingRequests` 去重。所以 N 个代码 ≈ ⌈N/20⌉ 次请求，
     * 取代原先「逐标的各打一次」的 N 次请求——请求量不增反降，不会引发请求风暴。
     *
     * 成功时把结果**双写**内存缓存与 PriceCacheManager 持久化缓存，
     * 保证后续按代码取价（含批量回填之外的兜底路径）直接命中、不再发请求。
     * 失败（网络/解析）时返回空 map，调用方（AssetSyncManager）会逐个标的走原有
     * 多源兜底，绝不因批量失败而放大请求量。
     */
    suspend fun getBatchStockDataThroughSina(
        symbols: List<String>,
        forceRefresh: Boolean = false
    ): Result<Map<String, StockData>> {
        if (symbols.isEmpty()) return Result.success(emptyMap())
        val lineageRoundId = currentCoroutineContext()[RoundSourceFailureScope.Key]?.roundId ?: 0
        // 批量路径也要计入该源耗时：只统计逐标的回退，会漏掉整轮最耗时的那次批量请求
        val batchStartedAt = System.currentTimeMillis()
        val sinaResult = try {
            SinaStockAPI.getRealtimeData(symbols, context = context, forceRefresh = forceRefresh)
        } finally {
            addSourceDuration(ApiManager.API_SINA, lineageRoundId, System.currentTimeMillis() - batchStartedAt)
        }
        return sinaResult.map { sinaMap ->
            // 批量成功的口径与逐标的路径一致：每个真正拿到行情的代码记一次成功。
            // 原来只加了 durationMs 不加 success，健康统计里新浪「耗时一大截、成功 0 次」，
            // 看起来像源在空转。
            recordSourceSuccess(ApiManager.API_SINA, lineageRoundId, sinaMap.size)
            val now = System.currentTimeMillis()
            sinaMap.mapValues { (code, sina) ->
                val unified = sina.toUnifiedStockData()
                cache.put(code, unified)
                PriceCacheManager.cachePrice(code, sina, SourceIds.SINA)
                // G5 血缘：批量回填是**本次实时**新浪请求的结果，origin=IN_MEMORY；
                // 轮次 ID 取本轮上下文，不能固定 0。
                PriceLineageRecorder.report(
                    code,
                    PriceLineage(
                        code = code,
                        sourceId = SourceIds.SINA,
                        origin = LineageOrigin.IN_MEMORY,
                        asOf = now,
                        fetchedAt = now,
                        roundId = lineageRoundId
                    )
                )
                unified
            }
        }
    }

    /** 供同步编排器在每轮开始时一次性读取网络可用性（P4：避免逐标的重复 binder 调用） */
    fun isNetworkAvailableNow(): Boolean = isNetworkAvailable()

    /**
     * 检查网络连接
     */
    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork
        val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        return networkCapabilities != null && 
               networkCapabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
    
    /**
     * 获取当前数据源状态（name → 是否健康可用）。
     *
     * 由各源的三态熔断器判定：非 CLOSED（OPEN 冷却中 / HALF_OPEN 探测中）即视为不可用。
     */
    fun getDataSourceStatus(): List<Pair<String, Boolean>> {
        return getCurrentDataSources().map { (name, _) ->
            name to !breakerFor(name).isUnavailable()
        }
    }

    /**
     * 批量预取「整体 0 命中」时，按**源级故障**计入一次失败。
     *
     * ## 为什么需要这个入口（真机诊断日志 2026-10-23 坐实）
     * 新浪 `hq.sinajs.cn` 曾对**全部** A 股返回 41 字节空报价：44 次请求 0 成功、
     * 批量预取 13 个代码「命中 0 个」、逐只问也照样空。但空报价走的是
     * 「未获取到股票数据，请检查股票代码」→ [isSymbolLevelFailure] 判成**符号级**
     * （代码不在此源支持范围）→ 不计源级失败 → 熔断器永远不开
     * → 每轮同步每个标的白打一次新浪再打腾讯（一轮 43 次白打），批量预取也完全废掉。
     * 这就是「饿死熔断器」的反面：源真的全挂，却因为逐只判成符号级而检测不到。
     *
     * ## 为什么必须用「批量整体 0 命中」而不是单只无命中
     * 单只无命中确实是符号级：REIT（508xxx/500xxx）、老三板（400/420）、部分场内基金
     * 本就不在新浪支持范围（日志里 33+9 次都是这类），**不能**计入源级，否则会误禁整源。
     * 但一批十几个代码一个都没回来、且批里存在新浪理应支持的普通 A 股时，就只能是源级问题。
     *
     * ## 为什么不在这里直接熔断
     * 这里只负责「让证据被看见」：喂一次失败，由熔断器按阈值
     * （[SourceCircuitBreaker.FAIL_THRESHOLD] = 8 次）与半开探测自行决定禁用与恢复。
     * 误伤有上限——即使连着喂，5 分钟后半开探测用真实请求试水，源恢复即自动闭路。
     *
     * ## 返回值为什么重要
     * 调用方（AssetSyncManager）据此把该源登记进本轮的 [RoundSourceFailureScope]，
     * 逐标的回落便不会重复喂熔断（整轮只记一次成败）。返回 false 表示这次没记账
     * （批内没有普通股票代码），此时不要登记，否则会白白抑制真实的逐标的 IO 失败。
     *
     * @param sourceName 数据源名（[ApiManager.API_SINA] 等）
     * @param probedCodes 本轮批量预取的代码（带市场前缀，如 sh600519）
     * @param roundId 本轮同步轮次 ID，只用于日志归属
     * @return 是否真的记了一次源级失败
     */
    fun reportBatchTotalMiss(
        sourceName: String,
        probedCodes: List<String>,
        roundId: Int
    ): Boolean {
        val hasPlainStock = probedCodes.any { code ->
            StockCodeManager.classifyCode(code) == StockCodeManager.CodeCategory.STOCK
        }
        if (!hasPlainStock) {
            AppLogger.d(
                "$sourceName 批量 0 命中，但批内没有普通股票代码（可能整批都是场内基金/REIT），不计源级失败",
                TAG
            )
            return false
        }
        AppLogger.w(
            FailureLogFormat.batchFailureLine(
                roundId,
                "批量${probedCodes.size}只",
                sourceName,
                "批量预取整体 0 命中（批内含普通股票代码），按源级故障计入一次失败"
            ),
            TAG
        )
        breakerFor(sourceName).onFailure(System.currentTimeMillis())
        recordSourceFailure(sourceName, roundId, "批量预取整体 0 命中")
        return true
    }

    /** 测试专用复位：清空各数据源熔断器状态，保证用例间隔离 */
    internal fun resetForTesting() {
        breakers.clear()
    }

    /**
     * 测试专用：往进程内内存缓存塞一条「旧价」。
     *
     * 无网络回落分支（getStockData 第 4 步）读的就是这张表，真实链路里它来自上一次
     * 成功抓价 / 持久化缓存回填。单测要钉死「TTL 已过就不再以 success 身份返回」，
     * 必须能确定性地造出这个前置状态，故开一个 internal 接缝（与 resetForTesting 同款）。
     */
    internal fun seedCacheForTesting(
        symbol: String,
        data: StockData,
        timestamp: Long = System.currentTimeMillis()
    ) {
        cache.put(symbol, data, timestamp)
    }

    /** 测试专用读数：指定数据源当前相位，供三态迁移断言 */
    internal fun sourcePhaseForTesting(apiName: String): SourcePhase =
        breakerFor(apiName).phaseForTesting()

    /** 测试专用读数：指定数据源当前连续源级失败计数（阈值以下的变化只有计数能看出来） */
    internal fun failuresForTesting(apiName: String): Int =
        breakerFor(apiName).failuresForTesting()
    
    /**
     * 清除缓存
     */
    fun clearCache() {
        cache.clear()
        PriceCacheManager.invalidateAll()
    }
    
    /**
     * 获取缓存统计
     */
    fun getCacheStats(): Map<String, Any> {
        val priceStats = PriceCacheManager.getCacheStats()
        return mapOf(
            "memoryCacheSize" to cache.size(),
            "persistedCacheTotal" to priceStats.totalCount,
            "persistedCacheValid" to priceStats.validCount,
            "persistedCacheExpired" to priceStats.expiredCount
        )
    }
    
    /**
     * 获取缓存大小
     */
    fun getCacheSize(): Int {
        return cache.size()
    }

    /**
     * 内存缓存超过上限时淘汰最旧条目。先清 TTL 已过（日期陈旧）的，还不够再按写入时间淘汰最旧的——
     * 刚写入的（本次同步窗口内）时间戳最新，不会被误删。
     */
    private fun trimCacheIfNeeded() {
        // 原行为：只在超上限时淘汰最旧，不按 TTL 预清（新鲜度完全由 PriceCacheManager 判定）
        cache.trim(Long.MIN_VALUE)
    }
}

/**
 * 新浪/腾讯数据源的薄适配：调用网络层 API，把返回的 [SinaStockData]
 * 映射为 data/price 栈内部统一使用的 [StockData]。
 *
 * [SinaStockData] 仍活在 network 层（SinaStockAPI/TencentStockAPI 的返回类型，
 * PriceCacheManager 持久化与 AddAssetDialog 名称回显都在用，迁移代价高故本期保留），
 * 模型统一只收敛到 data/price 栈内部这一侧。
 */
private fun networkStockDataSource(
    apiName: String,
    fetchStockData: suspend (String) -> Result<SinaStockData>
): StockDataSource = object : StockDataSource {
    override suspend fun getStockData(symbol: String): Result<StockData> {
        return try {
            fetchStockData(symbol).map { it.toUnifiedStockData() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getStockDataBatch(symbols: List<String>): Result<List<StockData>> {
        val results = symbols.mapNotNull { symbol ->
            try {
                fetchStockData(symbol).getOrNull()?.toUnifiedStockData()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
        return if (results.isNotEmpty()) {
            Result.success(results)
        } else {
            Result.failure(IOException("批量获取失败"))
        }
    }

    override fun getSourceName(): String = apiName

    override suspend fun isAvailable(): Boolean = true
}

/**
 * [SinaStockData] → 统一行情模型的字段映射。
 * code 保留源返回的值（与历史行为一致）；timestamp 取映射时刻。
 */
private fun SinaStockData.toUnifiedStockData(): StockData = StockData(
    code = code,
    name = name,
    price = price,
    change = change,
    changePercent = changePercent,
    high = high,
    low = low,
    open = open,
    volume = volume,
    amount = amount,
    yesterdayClose = prevClose,
    timestamp = System.currentTimeMillis()
)

/**
 * 是否是「符号级确定性失败」——即**这个代码不在此源支持范围**，而不是「源不健康」。
 *
 * 分类标准（分不清的一律按源级计，保守）：
 * - 消息可证明是「这个代码此源不支持」→ 符号级，**不计入**源级禁用计数；
 * - 「源理应有但没给」（超时 / 连接失败 / 空返回 / 格式坏 / 无效价格）→ 源级，要计。
 *
 * 现可判定的符号级失败：新浪 / 腾讯的「未获取到股票数据，请检查股票代码」——源正常应答了，
 * 只是没有这个代码的数据（代码不在其支持范围 / 代码无效）。换源去问同一只代码才对，
 * 重试或累计禁用这个源毫无意义。
 *
 * 停牌 / 退市整理期走 [QuoteSuspendedException]，由调用方在调本函数之前就分流，不走这里。
 */
internal fun isSymbolLevelFailure(failure: Exception): Boolean {
    val msg = failure.message ?: return false
    return msg.contains("未获取到股票数据，请检查股票代码")
}
