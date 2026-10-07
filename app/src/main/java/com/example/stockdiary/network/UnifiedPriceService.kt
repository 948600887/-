package com.example.stockdiary.network

import com.example.stockdiary.common.APIEndpoints
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.network.cache.CacheManager
import com.example.stockdiary.network.model.AssetPrice
import com.example.stockdiary.network.model.CryptoData
import com.example.stockdiary.network.model.FundData
import com.example.stockdiary.network.model.StockData
import com.example.stockdiary.utils.AppLogger
import kotlinx.coroutines.*
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException

object UnifiedPriceService {
    private const val TAG = "UnifiedPriceService"
    
    private val client = NetworkClient.defaultClient

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
                AssetType.STOCK -> getStockPrice(code).map { AssetPrice.Stock(it) }
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
            pendingRequests.remove(cacheKey)
        }
    }
    
    private suspend fun getStockPrice(code: String): Result<StockData> = withContext(Dispatchers.IO) {
        val url = "${APIEndpoints.Tencent.QT_API}$code"
        
        try {
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0")
                .addHeader("Referer", "https://gu.qq.com")
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

                if (body.length < 20) {
                    return@withContext Result.failure(Exception("响应数据过短"))
                }

                val stockData = parseTencentStock(code, body)

                return@withContext if (stockData != null) {
                    Result.success(stockData)
                } else {
                    Result.failure(Exception("解析股票数据失败"))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("获取股票价格失败: $code", e, TAG)
            Result.failure(e)
        }
    }
    
    private fun parseTencentStock(code: String, response: String): StockData? {
        return try {
            val rawData = response.split("=").getOrNull(1)
                ?.trim()
                ?.removePrefix("\"")
                ?.removeSuffix(";")
                ?: return null
            
            val data = rawData.split(",")
            
            if (data.size < 35) {
                AppLogger.w("股票数据格式异常，字段数不足: ${data.size}", TAG)
                return null
            }
            
            val name = data.getOrNull(1)?.takeIf { it.isNotBlank() } ?: ""
            val price = data.getOrNull(3)?.toDoubleOrNull() ?: 0.0
            val yesterdayClose = data.getOrNull(4)?.toDoubleOrNull()
            val open = data.getOrNull(5)?.toDoubleOrNull()
            val volume = data.getOrNull(6)?.toLongOrNull()
            val high = data.getOrNull(33)?.toDoubleOrNull()
            val low = data.getOrNull(34)?.toDoubleOrNull()
            
            val change = calculateChange(price, yesterdayClose)
            val changePercent = calculateChangePercent(change, yesterdayClose)
            
            StockData(
                code = code,
                name = name,
                price = price,
                change = change,
                changePercent = changePercent,
                yesterdayClose = yesterdayClose,
                open = open,
                volume = volume,
                high = high,
                low = low,
                timestamp = System.currentTimeMillis()
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("解析股票数据失败", e, TAG)
            null
        }
    }
    
    private fun calculateChange(price: Double, yesterdayClose: Double?): Double? {
        return yesterdayClose?.let { close ->
            if (close > 0) price - close else null
        }
    }
    
    private fun calculateChangePercent(change: Double?, yesterdayClose: Double?): Double? {
        if (change == null || yesterdayClose == null) return null
        if (yesterdayClose <= 0) return null
        
        return try {
            (change / yesterdayClose) * 100
        } catch (e: ArithmeticException) {
            null
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
    
    suspend fun getBatchPrices(
        codes: List<String>, 
        type: AssetType,
        concurrency: Int = 5
    ): Map<String, Result<AssetPrice>> = coroutineScope {
        codes.chunked(concurrency).flatMap { batch ->
            batch.map { code ->
                async {
                    code to getPrice(code, type)
                }
            }.awaitAll()
        }.toMap()
    }
    
    fun getCachedPrice(code: String, type: AssetType): AssetPrice? {
        return priceCache["${type.name}_$code"]?.price
    }
    
    fun getCacheSize(): Int = priceCache.size
    
    fun getCacheKeys(): Set<String> = priceCache.keys.toSet()
    
    fun clearCache() {
        val sizeBefore = priceCache.size
        priceCache.clear()
        pendingRequests.clear()
        CacheManager.invalidateAll()
        AppLogger.i("价格缓存已清空 (${sizeBefore} 条)", TAG)
    }
    
    fun removeFromCache(code: String, type: AssetType) {
        val cacheKey = "${type.name}_$code"
        priceCache.remove(cacheKey)
        CacheManager.invalidate(cacheKey)
        AppLogger.d("移除缓存: $cacheKey", TAG)
    }
    
    /** 缓存里是否有一份尚未过期的价格 */
    fun isCacheValid(code: String, type: AssetType): Boolean {
        val entry = priceCache["${type.name}_$code"] ?: return false
        return isFresh(entry)
    }
}
