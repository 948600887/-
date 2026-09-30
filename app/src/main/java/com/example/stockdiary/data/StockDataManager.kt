package com.example.stockdiary.data

import android.annotation.SuppressLint
import android.content.Context
import com.example.stockdiary.common.Result
import com.example.stockdiary.network.NetworkRetryHelper
import com.example.stockdiary.network.SinaStockAPI
import com.example.stockdiary.network.TencentStockAPI
import com.example.stockdiary.utils.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * 股票数据管理器 v2
 * 支持多种数据源：妙想Skill、新浪财经、腾讯财经
 * 妙想Skill作为默认优先数据源
 */
class StockDataManager(
    private val context: Context
) {
    // 失败计数
    private val failureCount = ConcurrentHashMap<String, Int>()
    private val maxFailures = 3
    
    // 数据缓存（内存缓存）
    private val cache = ConcurrentHashMap<String, CacheItem>()
    
    // 数据源实例缓存
    private val dataSourceCache = ConcurrentHashMap<String, StockDataSource>()
    
    // Coroutine scope for failure reset jobs
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val failureResetJobs = ConcurrentHashMap<String, Job>()
    
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
                ApiManager.API_SINA -> GenericStockDataSource("新浪财经", { SinaStockAPI.getStockData(it) })
                ApiManager.API_TENCENT -> GenericStockDataSource("腾讯财经", { TencentStockAPI.getStockData(it) })
                else -> SkillDataSource()
            }
        }
    }
    
    /**
     * 获取当前数据源列表（根据用户选择排序）
     * 妙想Skill优先级最高
     */
    private fun getCurrentDataSources(): List<Pair<String, StockDataSource>> {
        val selectedApi = ApiManager.getPriceApi(context)
        
        // 优先级：妙想Skill > 新浪 > 腾讯
        val priorityList = listOf(
            ApiManager.API_SKILL,
            ApiManager.API_SINA, 
            ApiManager.API_TENCENT
        )
        
        // 根据用户选择重新排序
        val sortedList = priorityList.sortedByDescending { it == selectedApi }
        
        return sortedList.map { apiName ->
            apiName to getDataSource(apiName)
        }
    }
    
    /**
     * 获取股票数据（带容错机制）
     */
    suspend fun getStockData(symbol: String, forceRefresh: Boolean = false): Result<StockData> {
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
                    symbol = persistedCache.code,
                    name = persistedCache.name,
                    price = persistedCache.price,
                    open = persistedCache.open,
                    prevClose = persistedCache.prevClose,
                    high = persistedCache.high,
                    low = persistedCache.low,
                    volume = persistedCache.volume,
                    amount = persistedCache.amount,
                    timestamp = "${persistedCache.date} ${persistedCache.time}"
                )
                // 更新内存缓存
                cache[symbol] = CacheItem(stockData, System.currentTimeMillis())
                AppLogger.d("使用持久化缓存数据: $symbol", TAG)
                return Result.success(stockData)
            }
        }
        
        // 4. 检查网络连接
        if (!isNetworkAvailable()) {
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
        val currentDataSources = getCurrentDataSources()
        
        for ((apiName, dataSource) in currentDataSources) {
            // 检查失败计数
            val failCount = failureCount.getOrDefault(apiName, 0)
            if (failCount >= maxFailures) {
                AppLogger.w("跳过数据源 $apiName（失败次数过多）", TAG)
                continue
            }
            
            AppLogger.d("尝试使用数据源: $apiName", TAG)
            
            val result = NetworkRetryHelper.executeWithRetry {
                dataSource.getStockData(symbol)
            }
            
            if (result.isSuccess) {
                val stockData = result.getOrNull()
                if (stockData != null) {
                    // 成功：更新所有缓存
                    cache[symbol] = CacheItem(stockData, System.currentTimeMillis())
                    
                    // 同时更新PriceCacheManager持久化缓存
                    val sinaData = com.example.stockdiary.network.SinaStockData(
                        name = stockData.name,
                        code = stockData.symbol,
                        price = stockData.price,
                        change = stockData.price - stockData.prevClose,
                        changePercent = if (stockData.prevClose > 0) 
                            (stockData.price - stockData.prevClose) / stockData.prevClose * 100 else 0.0,
                        open = stockData.open,
                        high = stockData.high,
                        low = stockData.low,
                        volume = stockData.volume
                    )
                    PriceCacheManager.cachePrice(symbol, sinaData, apiName)
                    
                    resetFailureCount(apiName)
                    AppLogger.d("使用${apiName}获取数据成功: ${stockData.name}", TAG)
                    return Result.success(stockData)
                }
            }
            
            // 失败：记录错误
            lastError = result.exceptionOrNull() as? Exception ?: Exception("Unknown error")
            recordFailure(apiName)
            AppLogger.w("${apiName}获取数据失败: ${lastError.message}", TAG)
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
     * 记录失败
     */
    private fun recordFailure(sourceName: String) {
        val count = failureCount.getOrDefault(sourceName, 0) + 1
        failureCount[sourceName] = count
        
        if (count >= maxFailures) {
            AppLogger.w("数据源 $sourceName 已被临时禁用", TAG)
            
            // Cancel any existing reset job for this source
            failureResetJobs[sourceName]?.cancel()
            
            // Schedule reset using coroutine instead of Handler
            failureResetJobs[sourceName] = scope.launch {
                delay(30 * 60 * 1000L)
                failureCount.remove(sourceName)
                failureResetJobs.remove(sourceName)
                AppLogger.d("数据源 $sourceName 已重新启用", TAG)
            }
        }
    }
    
    /**
     * 重置失败计数
     */
    private fun resetFailureCount(sourceName: String) {
        failureCount.remove(sourceName)
    }
    
    /**
     * 获取当前数据源状态
     */
    fun getDataSourceStatus(): List<Pair<String, Boolean>> {
        return getCurrentDataSources().map { (name, _) ->
            name to (failureCount.getOrDefault(name, 0) < maxFailures)
        }
    }
    
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
}

class GenericStockDataSource(
    private val apiName: String,
    private val fetchStockData: suspend (String) -> com.example.stockdiary.common.Result<com.example.stockdiary.network.SinaStockData>
) : StockDataSource {
    override suspend fun getStockData(symbol: String): Result<StockData> {
        return try {
            val result = fetchStockData(symbol)
            if (result.isSuccess) {
                val data = result.getOrNull()
                if (data != null) {
                    Result.success(
                        StockData(
                            symbol = data.code,
                            name = data.name,
                            price = data.price,
                            open = data.open,
                            prevClose = data.prevClose,
                            high = data.high,
                            low = data.low,
                            volume = data.volume,
                            amount = data.amount,
                            timestamp = "${data.date} ${data.time}"
                        )
                    )
                } else {
                    Result.failure(IOException("未获取到股票数据"))
                }
            } else {
                Result.failure(result.exceptionOrNull() ?: IOException("未知错误"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun getStockDataBatch(symbols: List<String>): Result<List<StockData>> {
        val results = symbols.mapNotNull { symbol ->
            getStockData(symbol).getOrNull()
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
