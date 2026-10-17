package com.example.stockdiary.network

import android.content.Context
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.data.price.StockDataManager
import com.example.stockdiary.network.cache.CacheManager
import com.example.stockdiary.network.model.AssetPrice
import com.example.stockdiary.network.model.CryptoData
import com.example.stockdiary.network.model.FundData
import com.example.stockdiary.utils.AppLogger
import kotlinx.coroutines.*
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException

/**
 * 行情统一入口（门面）。
 *
 * 调用方（资产同步 / 添加资产回显 / 币安导入估值）只面对本对象：
 * - 进程内 60s TTL 内存缓存 + in-flight 单飞去重（[pendingRequests]）；
 * - STOCK / 场内基金 → 委托 [StockDataManager]（多源容错 + 失败熔断 + 三级缓存）；
 * - FUND / CRYPTO → 本类内部实现（场外基金经 [FundAPI]，加密货币直连币安）。
 */
object UnifiedPriceService {
    private const val TAG = "UnifiedPriceService"
    
    private val client = NetworkClient.defaultClient

    /**
     * 股票/场内基金行情策略（多源容错 + 失败熔断 + 三级缓存）。
     *
     * 门面自己不再直连腾讯：STOCK 分支委托给它，本类退化为
     * 「60s TTL + 单飞」的统一入口。经 [init] 在应用启动时注入；
     * 未注入时 STOCK 分支返回失败而非崩溃（单测里整个对象会被 mock，走不到这里）。
     */
    @Volatile
    private var stockDataManager: StockDataManager? = null

    /** 注入股票行情策略。在 Application.onCreate 调用一次。 */
    fun init(context: Context) {
        stockDataManager = StockDataManager.getInstance(context)
    }

    /**
     * 行情价的进程内有效期。
     *
     * 缓存原本没有 TTL：同一个进程里第一次取到价格后就永远命中缓存，
     * 非 forceRefresh 的刷新（含后台定时同步）拿到的永远是启动那一刻的价。
     */
    private const val PRICE_CACHE_TTL_MS = 60_000L

    /** 缓存条目：价格 + 写入时间，用于判断过期 */
    private data class CacheEntry(val price: AssetPrice, val timestamp: Long)

    private val priceCache = ConcurrentHashMap<String, CacheEntry>()
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<Result<AssetPrice>>>()

    private fun isFresh(entry: CacheEntry): Boolean =
        System.currentTimeMillis() - entry.timestamp < PRICE_CACHE_TTL_MS

    suspend fun getPrice(code: String, type: AssetType, forceRefresh: Boolean = false): Result<AssetPrice> = withContext(Dispatchers.IO) {
        val cacheKey = "${type.name}_$code"

        if (!forceRefresh) {
            val cached = priceCache[cacheKey]
            if (cached != null && isFresh(cached)) {
                return@withContext Result.success(cached.price)
            }
        }

        // 如果已有相同请求正在进行，直接 await 其完成，避免自旋等待
        pendingRequests[cacheKey]?.let { return@withContext it.await() }

        val deferred = CompletableDeferred<Result<AssetPrice>>()
        val existing = pendingRequests.putIfAbsent(cacheKey, deferred)
        if (existing != null) {
            return@withContext existing.await()
        }

        try {
            val result: Result<AssetPrice> = when (type) {
                AssetType.STOCK -> getStockData(code, forceRefresh)
                AssetType.FUND -> getFundPrice(code, forceRefresh).map { AssetPrice.Fund(it) }
                AssetType.CRYPTO -> getCryptoPrice(code).map { AssetPrice.Crypto(it) }
                else -> Result.failure(Exception("不支持的资产类型: $type"))
            }

            result.onSuccess { price ->
                priceCache[cacheKey] = CacheEntry(price, System.currentTimeMillis())
            }

            deferred.complete(result)
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val failure = Result.failure<AssetPrice>(e)
            deferred.complete(failure)
            failure
        } finally {
            // 必须按「是不是自己」删除：无条件的 pendingRequests.remove(cacheKey)
            // 会在并发下误删别人刚放进去的 deferred（例如期间被 clearCache 清过一次），
            // 去重失效、同一标的重复打网络。
            pendingRequests.remove(cacheKey, deferred)
            // 走到这里只剩两条路：上面的 catch 抛了 CancellationException，
            // 或 try 里抛了 Error。deferred 是没有父 Job 的独立 CompletableDeferred，
            // 不会随本协程取消 —— 不显式收尾，其它作用域里 await() 它的协程会永久挂起。
            if (!deferred.isCompleted) {
                deferred.cancel()
            }
        }
    }
    
    /**
     * 股票行情：委托 [StockDataManager]（多源容错策略实现）。
     *
     * 门面的 60s TTL 与单飞在 [getPrice] 里已经先生效，能走到这里意味着
     * 确实需要新鲜数据；[StockDataManager] 内部的三级缓存与熔断行为不变。
     * forceRefresh 一并透传——调用方显式要求刷新时，策略层的缓存也要让路。
     */
    private suspend fun getStockData(code: String, forceRefresh: Boolean): Result<AssetPrice> {
        val manager = stockDataManager
            ?: return Result.failure(IllegalStateException("行情门面未初始化（UnifiedPriceService.init 未调用）"))
        // StockDataManager 用 common.Result，门面对外是 kotlin.Result，这里做转换
        return when (val result = manager.getStockData(code, forceRefresh)) {
            is com.example.stockdiary.common.Result.Success -> Result.success(AssetPrice.Stock(result.data))
            is com.example.stockdiary.common.Result.Error -> Result.failure(result.exception)
            is com.example.stockdiary.common.Result.Loading -> Result.failure(IllegalStateException("行情结果尚未加载完成"))
        }
    }
    
    private suspend fun getFundPrice(code: String, forceRefresh: Boolean): Result<FundData> = withContext(Dispatchers.IO) {
        try {
            // 走带缓存的版本：原来直接调 getFundInfo 会绕过 FundAPI 的 5 分钟缓存，
            // 每次同步都重新打天天基金接口，既慢又容易被限流。
            val result = FundAPI.getFundInfoWithCache(code, forceRefresh)
            val fundInfo = result.getOrNull()
            if (fundInfo != null && fundInfo.name.isNotEmpty()) {
                val fundData = FundData(
                    code = fundInfo.code.ifEmpty { code },
                    name = fundInfo.name,
                    price = fundInfo.price,
                    change = fundInfo.changePercent?.let { fundInfo.price * it / 100.0 },
                    changePercent = fundInfo.changePercent,
                    netWorth = fundInfo.netWorth,
                    netWorthDate = fundInfo.date
                )
                Result.success(fundData)
            } else {
                Result.failure(Exception("未找到基金数据"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("获取基金价格失败: $code", e, TAG)
            Result.failure(e)
        }
    }
    
    private suspend fun getCryptoPrice(code: String): Result<CryptoData> = withContext(Dispatchers.IO) {
        val url = "https://api.binance.com/api/v3/ticker/24hr?symbol=${code.uppercase()}"
        
        try {
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0")
                .build()
            
            val startTime = System.currentTimeMillis()
            val response = client.newCall(request).execute()
            response.use {
                val duration = System.currentTimeMillis() - startTime

                AppLogger.api("GET $url", it.message, duration, TAG)

                if (!it.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP ${it.code}"))
                }

                val body = it.body?.string() ?: return@withContext Result.failure(Exception("空响应"))

                if (!body.contains("lastPrice")) {
                    return@withContext Result.failure(Exception("响应格式错误"))
                }

                val cryptoData = parseBinanceCrypto(code, body)

                return@withContext if (cryptoData != null) {
                    Result.success(cryptoData)
                } else {
                    Result.failure(Exception("解析加密货币数据失败"))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("获取加密货币价格失败: $code", e, TAG)
            Result.failure(e)
        }
    }
    
    private fun parseBinanceCrypto(code: String, response: String): CryptoData? {
        return try {
            val price = parseJsonField(response, "lastPrice")?.toDoubleOrNull()
                ?: return null
            
            val change = parseJsonField(response, "priceChange")?.toDoubleOrNull()
            val changePercent = parseJsonField(response, "priceChangePercent")?.toDoubleOrNull()
            val high = parseJsonField(response, "highPrice")?.toDoubleOrNull()
            val low = parseJsonField(response, "lowPrice")?.toDoubleOrNull()
            val volume = parseJsonField(response, "volume")?.let {
                it.toDoubleOrNull()?.toLong()
            }
            
            CryptoData(
                code = code,
                name = code,
                price = price,
                change = change,
                changePercent = changePercent,
                high = high,
                low = low,
                volume = volume,
                timestamp = System.currentTimeMillis()
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("解析加密货币数据失败", e, TAG)
            null
        }
    }
    
    private fun parseJsonField(json: String, key: String): String? {
        val pattern = "\"$key\"\\s*:\\s*\"?([^\",\\}]+)\"?".toRegex()
        return pattern.find(json)?.groupValues?.getOrNull(1)?.trim()
    }
    
    fun clearCache() {
        val sizeBefore = priceCache.size
        priceCache.clear()
        pendingRequests.clear()
        CacheManager.invalidateAll()
        // 基金净值走的是 FundAPI 自己的 5 分钟缓存（本类的 getFundPrice 委托给它）。
        // 只清这里的 60 秒缓存而不清 FundAPI 那份，「清完缓存还是拿到旧净值」。
        FundAPI.clearCache()
        AppLogger.i("价格缓存已清空 (${sizeBefore} 条)", TAG)
    }
}
