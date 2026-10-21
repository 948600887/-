package com.example.stockdiary.data.price

import android.annotation.SuppressLint
import android.content.Context
import com.example.stockdiary.common.Result
import com.example.stockdiary.network.EastmoneySkillApi
import com.example.stockdiary.network.NetworkRetryHelper
import com.example.stockdiary.network.QuoteSuspendedException
import com.example.stockdiary.network.SinaStockAPI
import com.example.stockdiary.network.SinaStockData
import com.example.stockdiary.network.TencentStockAPI
import com.example.stockdiary.network.model.StockData
import com.example.stockdiary.utils.AppLogger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException

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
     * 价格是否可用于"写缓存 + 认定成功"。
     *
     * 必须用 isFinite 而不是简单的 `> 0`：NaN 参与的任何比较都返回 false，
     * 所以 `price <= 0` 拦不住 NaN，NaN 会一路写进资产表。
     */
    private fun isValidPrice(price: Double): Boolean = price.isFinite() && price > 0.0
    
    // 数据缓存（内存缓存）
    private val cache = ConcurrentHashMap<String, CacheItem>()

    /** 内存缓存上限：与 PriceCacheManager.MAX_MEMORY_CACHE_SIZE / SinaStockAPI.MAX_CACHE_SIZE 同量级（~200），避免长会话无限增长 */
    private val MAX_MEMORY_CACHE_ENTRIES = 200
    
    // 数据源实例缓存
    private val dataSourceCache = ConcurrentHashMap<String, StockDataSource>()

    // 初始化PriceCacheManager
    init {
        PriceCacheManager.init(context)
    }
    
    data class CacheItem(
        val data: StockData,
        val timestamp: Long
    )
    
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
    private fun getCurrentDataSources(preferredApi: String? = null): List<Pair<String, StockDataSource>> {
        return candidateSourceNames(preferredApi).map { apiName ->
            apiName to getDataSource(apiName)
        }
    }

    /**
     * 候选数据源名（按用户偏好排序）。抽出来便于单测「妙想未配置 Key 的过滤」。
     *
     * 过滤规则：妙想未配置 API Key 时移出候选链——否则每次刷新都白试一遍、失败还计入源级
     * 禁用计数（[EastmoneySkillApi] 对空 Key 直接返回「未配置东方财富 Skill API Key」）。
     * **但用户显式选中妙想时不静默移除**：保留它，让失败沿正常路径暴露给用户（日志 + UI 有
     * 明确提示），不偷偷把用户换到别的源。默认偏好是新浪（见 [ApiManager.getPriceApi]），
     * 所以「选中妙想」可靠地等价于用户主动选过。
     */
    internal fun candidateSourceNames(preferredApi: String? = null): List<String> {
        val selectedApi = preferredApi ?: ApiManager.getPriceApi(context)
        val skillConfigured = EastmoneySkillApi.isApiKeyConfigured()

        // 优先级：妙想Skill > 新浪 > 腾讯
        val priorityList = listOf(
            ApiManager.API_SKILL,
            ApiManager.API_SINA,
            ApiManager.API_TENCENT
        )

        val filtered = priorityList.filter { api ->
            api != ApiManager.API_SKILL || skillConfigured || selectedApi == ApiManager.API_SKILL
        }
        if (!skillConfigured && selectedApi == ApiManager.API_SKILL) {
            AppLogger.w(
                "用户显式选择了妙想Skill但未配置 API Key，仍按用户选择尝试（会失败并提示，不静默换源）",
                TAG
            )
        }

        // 根据用户选择重新排序
        return filtered.sortedByDescending { it == selectedApi }
    }
    
    /**
     * 获取股票数据（带容错机制）
     *
     * @param forceRefresh 跳过内存/持久化缓存强制重抓
     * @param networkAvailable 本次同步开始时一次性算好的网络可用性；非 null 时直接采信，
     *        不再每个标的都去问 ConnectivityManager（避免 O(n) 冗余 binder 调用，见 P4）。
     *        为 null 时回落到 [isNetworkAvailable] 实时判断。
     * @param preferredApi 本次同步开始时一次性读好的用户偏好数据源；非 null 时直接采信，
     *        不再每个标的都读 SharedPreferences（避免 O(n) 冗余磁盘读）。为 null 时回落到
     *        [ApiManager.getPriceApi]。
     */
    suspend fun getStockData(
        symbol: String,
        forceRefresh: Boolean = false,
        networkAvailable: Boolean? = null,
        preferredApi: String? = null
    ): Result<StockData> {
        // 1. 检查是否需要强制刷新
        if (!forceRefresh) {
            // 2. 检查内存缓存
            val cached = cache[symbol]
            if (cached != null && PriceCacheManager.isCacheValid(symbol)) {
                AppLogger.d("使用内存缓存数据: $symbol", TAG)
                return Result.success(cached.data)
            }
            
            // 3. 检查PriceCacheManager持久化缓存
            val persistedCache = PriceCacheManager.getCachedPrice(symbol)
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
                // 更新内存缓存
                cache[symbol] = CacheItem(stockData, System.currentTimeMillis())
                AppLogger.d("使用持久化缓存数据: $symbol", TAG)
                return Result.success(stockData)
            }
        }
        
        // 4. 检查网络连接（优先用同步开始时一次性算好的结果，避免每个标的重复 binder 调用）
        val netAvailable = networkAvailable ?: isNetworkAvailable()
        if (!netAvailable) {
            AppLogger.w("网络连接不可用", TAG)
            // 返回旧缓存或失败
            val oldCache = cache[symbol]
            if (oldCache != null) {
                return Result.success(oldCache.data)
            }
            return Result.failure(IOException("网络连接不可用"))
        }
        
        // 5. 按优先级尝试各个数据源
        var lastError: Exception? = null
        val currentDataSources = getCurrentDataSources(preferredApi)
        
        for ((apiName, dataSource) in currentDataSources) {
            // 三态熔断闸门：CLOSED 放行；OPEN 冷却期跳过；冷却结束只放行一个探测（其余跳过）。
            // 取代旧的「失败计数 >= maxFailures 就跳过错过的全部请求」。
            if (!breakerFor(apiName).shouldAllow(System.currentTimeMillis())) {
                AppLogger.w("跳过数据源 $apiName（熔断冷却中 / 半开探测已在进行）", TAG)
                continue
            }

            AppLogger.d("尝试使用数据源: $apiName", TAG)
            
            val result = NetworkRetryHelper.executeWithRetry {
                dataSource.getStockData(symbol)
            }
            
            if (result.isSuccess) {
                val stockData = result.getOrNull()
                // 拿到了"结果"不等于拿到了好数据：解析失败时各源会把现价落成 0.0/NaN，
                // 若照单全收就会写进三级缓存、把持仓市值算成 0。这里统一挡一道，
                // 并视作该数据源失败（继续找下一个源），而不是把它当成成功。
                if (stockData != null && isValidPrice(stockData.price)) {
                    // 成功：更新所有缓存
                    cache[symbol] = CacheItem(stockData, System.currentTimeMillis())
                    trimCacheIfNeeded()
                    
                    // 同时更新PriceCacheManager持久化缓存
                    val prevClose = stockData.yesterdayClose ?: 0.0
                    val sinaData = SinaStockData(
                        name = stockData.name,
                        code = stockData.code,
                        price = stockData.price,
                        change = stockData.price - prevClose,
                        changePercent = if (prevClose > 0)
                            (stockData.price - prevClose) / prevClose * 100 else 0.0,
                        open = stockData.open ?: 0.0,
                        high = stockData.high ?: 0.0,
                        low = stockData.low ?: 0.0,
                        volume = stockData.volume ?: 0L
                    )
                    PriceCacheManager.cachePrice(symbol, sinaData, apiName)
                    
                    breakerFor(apiName).onSuccess()
                    AppLogger.d("使用${apiName}获取数据成功: ${stockData.name}", TAG)
                    return Result.success(stockData)                }
                if (stockData != null) {
                    AppLogger.w(
                        "${apiName}返回的价格无效（${stockData.price}），视为失败并尝试下一个数据源",
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
                AppLogger.w("$symbol 无有效报价（停牌/退市整理期），不计入数据源失败", TAG)
                continue
            }

            // 符号级确定性失败（这个代码不在此源支持范围）：换源去问同一只代码才对，
            // 但这不是「源不健康」——把它计入会在几次后误禁整源、把流量全甩给单一源引发雪崩，
            // 正是本轮要修的问题。分类标准见 [isSymbolLevelFailure]。
            if (isSymbolLevelFailure(failure)) {
                AppLogger.w("$symbol 在 $apiName 无数据（代码不在此源支持范围），不计入数据源失败", TAG)
                continue
            }

            // 源级失败（超时 / 连接失败 / 空返回 / 格式坏 / 无效价格）：喂给熔断器
            breakerFor(apiName).onFailure(System.currentTimeMillis())
            AppLogger.w("${apiName}获取数据失败: ${failure.message}", TAG)
        }
        
        // 6. 所有数据源都失败
        AppLogger.e("所有数据源都失败: ${lastError?.message}", tag = TAG)
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
        val sinaResult = SinaStockAPI.getRealtimeData(symbols, context = context, forceRefresh = forceRefresh)
        return sinaResult.map { sinaMap ->
            sinaMap.mapValues { (code, sina) ->
                val unified = sina.toUnifiedStockData()
                cache[code] = CacheItem(unified, System.currentTimeMillis())
                PriceCacheManager.cachePrice(code, sina, ApiManager.API_SINA)
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

    /** 测试专用复位：清空各数据源熔断器状态，保证用例间隔离 */
    internal fun resetForTesting() {
        breakers.clear()
    }

    /** 测试专用读数：指定数据源当前相位，供三态迁移断言 */
    internal fun sourcePhaseForTesting(apiName: String): SourcePhase =
        breakerFor(apiName).phaseForTesting()
    
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
            "memoryCacheSize" to cache.size,
            "persistedCacheTotal" to priceStats.totalCount,
            "persistedCacheValid" to priceStats.validCount,
            "persistedCacheExpired" to priceStats.expiredCount
        )
    }
    
    /**
     * 获取缓存大小
     */
    fun getCacheSize(): Int {
        return cache.size
    }

    /**
     * 内存缓存超过上限时淘汰最旧条目。先清 TTL 已过（日期陈旧）的，还不够再按写入时间淘汰最旧的——
     * 刚写入的（本次同步窗口内）时间戳最新，不会被误删。
     */
    private fun trimCacheIfNeeded() {
        if (cache.size <= MAX_MEMORY_CACHE_ENTRIES) return
        val entries = cache.entries.sortedBy { it.value.timestamp }
        val removeCount = cache.size - MAX_MEMORY_CACHE_ENTRIES
        entries.take(removeCount).forEach { cache.remove(it.key) }
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
