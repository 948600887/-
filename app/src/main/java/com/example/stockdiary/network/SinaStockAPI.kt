package com.example.stockdiary.network

import android.content.Context
import com.example.stockdiary.utils.AppLogger
import com.example.stockdiary.common.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred
import okhttp3.*
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject
import com.example.stockdiary.common.APIEndpoints
import com.example.stockdiary.common.CacheConfig
import com.example.stockdiary.common.RequestLimits
import com.example.stockdiary.utils.PerformanceMonitor
import com.example.stockdiary.utils.StockCodeManager
import com.example.stockdiary.data.price.PriceCacheManager
import kotlinx.coroutines.CancellationException

object SinaStockAPI : StockAPI {
    private const val TAG = "SinaStockAPI"
    private const val BASE_URL_REALTIME = APIEndpoints.Sina.HQ_API + "/list="
    private const val BASE_URL_HISTORY = APIEndpoints.Sina.HISTORY_API

    private val stockDataCache = ConcurrentHashMap<String, Pair<Long, SinaStockData>>()
    private const val CACHE_DURATION = CacheConfig.PORTFOLIO_CACHE_MS
    private const val MAX_CACHE_SIZE = 200
    private const val MAX_RETRY_COUNT = RequestLimits.MAX_RETRY
    
    private val pendingRequests = ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<Result<SinaStockData>>>()
    private val batchPendingRequests = ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<Result<Map<String, SinaStockData>>>>()
    private val requestLock = Any()
    
    private val client = NetworkClient.sinaClient.newBuilder()
        .addInterceptor {
            val request = it.request().newBuilder()
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .addHeader("Referer", "https://finance.sina.com.cn")
                .addHeader("Accept-Encoding", "gzip, deflate")
                .build()
            it.proceed(request)
        }
        .build()

    /**
     * 测试覆写：注入 mock OkHttpClient，在单测里驱动**真实**的批处理 / sink 转换逻辑
     * （不碰网络、不让 [getRealtimeData] 被 mock 掉）。null = 用上面的真实 [client]。
     * 见 [resetHttpClientForTesting]。
     */
    internal var httpClientForTesting: OkHttpClient? = null

    private val activeClient: OkHttpClient get() = httpClientForTesting ?: client

    /** 测试专用复位：还原真实 OkHttpClient */
    internal fun resetHttpClientForTesting() {
        httpClientForTesting = null
    }
    
    fun initCache(context: Context) {
        PriceCacheManager.init(context)
    }
    
    override suspend fun getStockData(stockCode: String): Result<SinaStockData> {
        suspendedCodes.clear()
        val result = getRealtimeData(listOf(stockCode))
        return result.mapCatching { map ->
            map[stockCode] ?: if (stockCode in suspendedCodes) {
                // 明确告诉上层「这是停牌，不是数据源坏了」，不要去累计失败次数
                throw QuoteSuspendedException(stockCode)
            } else {
                throw IOException("无法获取股票数据: $stockCode")
            }
        }
    }

    /** 最近一次请求里被判定为无有效报价（停牌）的代码，供 [getStockData] 区分失败语义 */
    private val suspendedCodes = mutableSetOf<String>()
    
    suspend fun getRealtimeData(
        stockCodes: List<String>,
        context: Context? = null,
        forceRefresh: Boolean = false
    ): Result<Map<String, SinaStockData>> {
        return withContext(Dispatchers.IO) {
            try {
                PerformanceMonitor.start("network_request")
                
                if (stockCodes.isEmpty()) {
                    return@withContext Result.failure(IllegalArgumentException("股票代码列表不能为空"))
                }
                
                context?.let { PriceCacheManager.init(it) }
                
                val uniqueStockCodes = stockCodes.distinct()
                val cachedResults = mutableMapOf<String, SinaStockData>()
                val needRequest = mutableListOf<String>()
                
                if (!forceRefresh && context != null) {
                    for (code in uniqueStockCodes) {
                        val cached = PriceCacheManager.getCachedPrice(code)
                        if (cached != null) {
                            cachedResults[code] = cached
                            stockDataCache[code] = Pair(System.currentTimeMillis(), cached)
                        } else {
                            val oldCached = getCachedData(code)
                            if (oldCached != null) {
                                cachedResults[code] = oldCached
                            }
                            needRequest.add(code)
                        }
                    }
                    
                    if (needRequest.isEmpty() && cachedResults.isNotEmpty()) {
                        AppLogger.d("所有数据来自缓存，无需请求API", TAG)
                        return@withContext Result.success(cachedResults)
                    }
                } else {
                    needRequest.addAll(uniqueStockCodes)
                }
                
                val batchSize = RequestLimits.BATCH_SIZE
                val batches = needRequest.chunked(batchSize)
                val allResults = mutableMapOf<String, SinaStockData>()
                allResults.putAll(cachedResults)

                // 批间跟踪「首个批级异常」：批内 catch 历来把任何异常（超时/连接失败/403限流/空body）
                // 都吞成「部分缓存结果」，allResults 空时又被统一扔成符号级文案「未获取到股票数据」，
                // 导致上层源级熔断器把真·源级故障误判成「代码不支持」而永不熔禁（饿死）。
                // 这里把原始异常记下，空结果时优先透出它（见下方 allResults.isEmpty 分支）。
                // mapIndexed 是串行的，捕获改写局部 var 无线程竞争。
                var lastBatchException: Exception? = null

                val batchResults = batches.mapIndexed { index, batch ->
                    // 放在最外层 try 之前：try 块内声明的局部变量对其自身 catch 不可见（兄弟作用域），
                    // 而内/外两层 catch 的日志都要带出失败 URL，故声明在最外层 try 外面。
                    var reqUrl: String = "(未构造URL)"
                    try {
                        val batchKey = batch.sorted().joinToString(",")
                        
                        val existingRequest = batchPendingRequests[batchKey]
                        if (existingRequest != null) {
                            return@mapIndexed existingRequest.await().getOrElse { emptyMap() }
                        }
                        
                        val request = CompletableDeferred<Result<Map<String, SinaStockData>>>()
                        batchPendingRequests[batchKey] = request
                        
                        try {
                            val codeString = batch.joinToString(",")
                            val timestamp = System.currentTimeMillis()
                            val url = "${BASE_URL_REALTIME}${codeString}&rn=$timestamp"
                            
                            val httpRequest = Request.Builder()
                                .url(url)
                                .build()
                            
                            val response = activeClient.newCall(httpRequest).execute()
                            reqUrl = httpRequest.url.toString()
                            response.use {
                                if (!it.isSuccessful) {
                                    // 诊断增强：新浪偶发被限流/封禁时只给个 code 很难定位，
                                    // 这里把 URL 和响应体前 200 字一起记下来，方便区分
                                    // 「被墙/403 限流」与「真·源级故障」（上层源级熔断靠这个判断）。
                                    val bodyPeek = runCatching { it.body?.string()?.take(200) }.getOrNull().orEmpty()
                                    AppLogger.e("新浪行情 HTTP ${it.code} 非成功 | url=$reqUrl | 响应体前200=$bodyPeek", tag = TAG)
                                    if (it.code == 403) {
                                        throw IOException("API请求频率过高，请稍后再试")
                                    }
                                    throw IOException("API响应错误: ${it.code}")
                                }

                                val responseBody = it.body
                                if (responseBody == null) {
                                    throw IOException("API返回空数据")
                                }
                                val bytes = responseBody.use { body -> body.bytes() }
                                if (bytes.isEmpty()) {
                                    AppLogger.e("新浪行情返回空 body | url=$reqUrl", tag = TAG)
                                    throw IOException("API返回空数据")
                                }
                                val text = String(bytes, Charset.forName("GBK"))

                                val suspended = mutableSetOf<String>()
                                val batchResult = parseRealtimeData(text, batch, suspended)
                                if (suspended.isNotEmpty()) {
                                    suspendedCodes.addAll(suspended)
                                }

                                batchResult.forEach { (code, data) ->
                                    cacheData(code, data)
                                    if (context != null) {
                                        PriceCacheManager.cachePrice(code, data, "sina")
                                    }
                                }

                                // 批次间延迟，防止API限流（串行处理非首批次时生效）
                                if (index > 0) {
                                    kotlinx.coroutines.delay(RequestLimits.DELAY_MS)
                                }

                                request.complete(Result.success(batchResult))
                                return@use batchResult
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            AppLogger.e("批次请求失败 url=$reqUrl: ${e.message}", e, TAG)
                            request.complete(Result.failure(e))
                            if (lastBatchException == null) lastBatchException = e
                            val partialResult = mutableMapOf<String, SinaStockData>()
                            batch.forEach { code ->
                                getCachedData(code)?.let { partialResult[code] = it }
                                context?.let {
                                    PriceCacheManager.getCachedPrice(code)?.let { partialResult[code] = it }
                                }
                            }
                            partialResult
                        } finally {
                            batchPendingRequests.remove(batchKey)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLogger.e("批次请求异常 url=$reqUrl: ${e.message}", e, TAG)
                        if (lastBatchException == null) lastBatchException = e
                        val partialResult = mutableMapOf<String, SinaStockData>()
                        batch.forEach { code ->
                            getCachedData(code)?.let { partialResult[code] = it }
                            context?.let {
                                PriceCacheManager.getCachedPrice(code)?.let { partialResult[code] = it }
                            }
                        }
                        partialResult
                    }
                }
                
                batchResults.forEach { batchResult ->
                    allResults.putAll(batchResult)
                }
                
                if (allResults.isEmpty()) {
                    val batchError = lastBatchException
                    if (batchError != null) {
                        // 曾有批级异常（超时 / 连接失败 / 403 限流 / 空 body）：透出原始异常，
                        // 让上层源级熔断器与 NetworkRetryHelper 拿到真实原因，而不是被吞成
                        // 「未获取到股票数据」这种符号级文案——那会把真·源级故障误判成「代码不支持」，
                        // 导致新浪熔断器被饿死（每个标的仍先白烧一轮新浪才回落腾讯）。
                        AppLogger.e("所有批次失败，透出原始异常: ${batchError.message}", batchError, TAG)
                        return@withContext Result.failure(batchError)
                    }
                    // 所有批次都正常应答、但没有任何代码命中：这才是真·符号级失败
                    AppLogger.e("未获取到股票数据（所有批次正常应答但无代码命中）", tag = TAG)
                    return@withContext Result.failure(IOException("未获取到股票数据，请检查股票代码"))
                }
                
                AppLogger.d("API调用成功，返回 ${allResults.size} 条数据", TAG)
                PerformanceMonitor.end("network_request")
                Result.success(allResults)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PerformanceMonitor.end("network_request")
                AppLogger.e("异常: ${e.message}", e, TAG)
                Result.failure(e)
            }
        }
    }
    
    suspend fun getKLineData(stockCode: String, scale: Int = 240, ma: String = "5", dataLen: Int = 100): Result<List<KLineData>> {
        return withContext(Dispatchers.IO) {
            try {
                val formattedCode = formatStockCode(stockCode)
                val url = "$BASE_URL_HISTORY?symbol=$formattedCode&scale=$scale&ma=$ma&datalen=$dataLen"
                AppLogger.apiUrl("GET", url, TAG)
                
                val request = Request.Builder()
                    .url(url)
                    .build()
                
                val response = client.newCall(request).execute()
                response.use {
                    if (!it.isSuccessful) {
                        val errorMsg = "API响应错误: ${it.code}"
                        AppLogger.e(errorMsg, tag = TAG)
                        return@withContext Result.failure(IOException(errorMsg))
                    }

                    val responseBody = it.body
                    if (responseBody == null) {
                        AppLogger.e("API返回空数据", tag = TAG)
                        return@withContext Result.failure(IOException("API返回空数据"))
                    }
                    val text = responseBody.use { body -> body.string() }
                    AppLogger.d("K线API响应长度: ${text.length}", TAG)

                    val kLineData = parseKLineData(text)
                    if (kLineData.isEmpty()) {
                        return@withContext Result.failure(IOException("未获取到K线数据"))
                    }

                    return@withContext Result.success(kLineData)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("K线API异常: ${e.message}", e, TAG)
                Result.failure(e)
            }
        }
    }
    
    private fun parseKLineData(jsonText: String): List<KLineData> {
        val result = mutableListOf<KLineData>()
        try {
            val jsonArray = JSONArray(jsonText.trim())
            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)
                val day = item.optString("day", "")
                if (day.isNotEmpty()) {
                    result.add(
                        KLineData(
                            day = day,
                            open = item.optDouble("open", 0.0),
                            high = item.optDouble("high", 0.0),
                            low = item.optDouble("low", 0.0),
                            close = item.optDouble("close", 0.0),
                            volume = item.optLong("volume", 0L)
                        )
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("解析K线数据错误: ${e.message}", e, TAG)
        }
        return result
    }
    
    suspend fun searchStock(keyword: String): Result<List<StockSearchResult>> {
        return withContext(Dispatchers.IO) {
            try {
                val encodedKeyword = URLEncoder.encode(keyword, "UTF-8")
                val url = "https://stock.finance.sina.com.cn/stock/api/openapi.php/StockSearchService.search?keyword=$encodedKeyword"
                AppLogger.apiUrl("GET", url, TAG)
                
                val request = Request.Builder()
                    .url(url)
                    .build()
                
                val response = client.newCall(request).execute()
                response.use {
                    if (!it.isSuccessful) {
                        val errorMsg = "API响应错误: ${it.code}"
                        AppLogger.e(errorMsg, tag = TAG)
                        return@withContext Result.failure(IOException(errorMsg))
                    }

                    val responseBody = it.body
                    if (responseBody == null) {
                        AppLogger.e("API返回空数据", tag = TAG)
                        return@withContext Result.failure(IOException("API返回空数据"))
                    }
                    val text = responseBody.use { body -> body.string() }
                    AppLogger.d("搜索API响应: $text", TAG)

                    val searchResults = parseSearchResult(text)

                    return@withContext Result.success(searchResults)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("搜索API异常: ${e.message}", e, TAG)
                Result.failure(e)
            }
        }
    }
    
    private fun parseSearchResult(jsonText: String): List<StockSearchResult> {
        val result = mutableListOf<StockSearchResult>()
        try {
            AppLogger.d("解析搜索结果: $jsonText", TAG)

            val root = JSONObject(jsonText.trim())
            val dataArray = root.optJSONObject("result")?.optJSONArray("data")
                ?: root.optJSONArray("data")
                ?: return result

            for (i in 0 until dataArray.length()) {
                val item = dataArray.getJSONObject(i)
                val symbol = item.optString("symbol").ifBlank { item.optString("code") }
                val name = item.optString("name")
                val type = item.optString("type")

                if (symbol.isNotEmpty() && name.isNotEmpty()) {
                    val market = when {
                        symbol.startsWith("sh") || type.contains("沪") -> "sh"
                        symbol.startsWith("sz") || type.contains("深") -> "sz"
                        else -> "sh"
                    }
                    result.add(StockSearchResult(symbol, name, market))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("解析搜索结果错误: ${e.message}", e, TAG)
        }
        return result
    }
    
    /**
     * 解析新浪实时行情。
     *
     * @return 正常报价的映射。`suspended` 收集「响应里出现了、但报价无效」的代码
     *   （停牌 / 退市整理期 / 集合竞价前）。它们不能混在失败里——见 [QuoteSuspendedException]。
     */
    internal fun parseRealtimeData(
        text: String,
        @Suppress("UNUSED_PARAMETER") codes: List<String>,
        suspended: MutableSet<String> = mutableSetOf()
    ): Map<String, SinaStockData> {
        val result = mutableMapOf<String, SinaStockData>()
        
        try {
            val lines = text.split("\n").filter { it.isNotBlank() }
            
            for (line in lines) {
                val dataMatch = Regex("""var hq_str_(\w+)="([^"]+)"""").find(line)
                if (dataMatch != null) {
                    val code = dataMatch.groupValues[1]
                    val dataStr = dataMatch.groupValues[2]
                    val fields = dataStr.split(",")
                    
                    if (fields.size >= 32) {
                        val name = fields[0].removeSurrounding("\"")
                        val open = fields[1].toDoubleOrNull() ?: 0.0
                        val close = fields[2].toDoubleOrNull() ?: 0.0
                        val current = fields[3].toDoubleOrNull() ?: 0.0
                        val high = fields[4].toDoubleOrNull() ?: 0.0
                        val low = fields[5].toDoubleOrNull() ?: 0.0
                        val volume = fields[8].toLongOrNull() ?: 0L
                        // 新浪字段：9=成交额(元)，30=日期 yyyy-MM-dd，31=时间 HH:mm:ss
                        val amount = fields.getOrNull(9)?.toDoubleOrNull() ?: 0.0
                        val date = fields.getOrNull(30)?.trim() ?: ""
                        val timeStr = fields.getOrNull(31)?.trim() ?: ""

                        // 停牌/集合竞价前/退市整理期：新浪返回的"现价"是 0，但昨收正常。
                        // 直接把 0 当价格会静默污染三层缓存（内存、PriceCacheManager 磁盘、调用方），
                        // 表现为市值归零、涨跌幅 -100%，且下轮拉取前不会自愈。
                        // 处理：现价有效用现价；否则退到昨收并把涨跌记为 0（券商 App 的通行做法）。
                        val hasValidPrice = current > 0
                        val effectivePrice = if (hasValidPrice) current else close
                        val change = if (hasValidPrice && close > 0) current - close else 0.0
                        val changePercent = if (hasValidPrice && close > 0) (change / close) * 100 else 0.0

                        if (effectivePrice > 0) {
                            result[code] = SinaStockData(
                                name = name,
                                code = code,
                                price = effectivePrice,
                                change = change,
                                changePercent = changePercent,
                                open = open,
                                // 昨收必须带上：它会被写进磁盘缓存，断网时 App 完全靠那份缓存显示。
                                // 先前这里没传，恢复出来的 prevClose 恒为 0，
                                // 涨跌幅被重算成「price - 0」，界面上一片异常值。
                                prevClose = close,
                                high = high,
                                low = low,
                                volume = volume,
                                amount = amount,
                                date = date,
                                time = timeStr
                            )
                        } else {
                            // 现价和昨收都是 0：这只标的当前根本没有报价。
                            // 记为「停牌」而不是普通失败，否则会被计入数据源失败次数。
                            suspended.add(code)
                            AppLogger.w("无有效报价（疑似停牌）: $code（现价=$current, 昨收=$close）", TAG)
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("解析实时数据错误: ${e.message}", e, TAG)
        }
        
        return result
    }
    
    private fun cacheData(code: String, data: SinaStockData) {
        synchronized(requestLock) {
            if (stockDataCache.size >= MAX_CACHE_SIZE) {
                val oldestKey = stockDataCache.minByOrNull { it.value.first }?.key
                oldestKey?.let { stockDataCache.remove(it) }
            }
            stockDataCache[code] = Pair(System.currentTimeMillis(), data)
        }
    }
    
    private fun getCachedData(code: String): SinaStockData? {
        val cached = stockDataCache[code]
        return if (cached != null && System.currentTimeMillis() - cached.first < CACHE_DURATION) {
            cached.second
        } else {
            stockDataCache.remove(code)
            null
        }
    }
    
    fun invalidateCache(code: String? = null) {
        synchronized(requestLock) {
            if (code != null) {
                stockDataCache.remove(code)
            } else {
                stockDataCache.clear()
            }
        }
        code?.let { PriceCacheManager.invalidateCache(it) }
    }
    
    fun formatStockCode(code: String): String {
        return StockCodeManager.normalizeSymbol(code)
    }
    
    fun getCacheStats(): CacheStats {
        val cacheStats = PriceCacheManager.getCacheStats()
        val memoryStats = stockDataCache.map { 
            System.currentTimeMillis() - it.value.first < CACHE_DURATION 
        }.count { it }
        
        return CacheStats(
            memoryCount = stockDataCache.size,
            diskCount = cacheStats.totalCount,
            validCount = memoryStats + cacheStats.validCount,
            nextRefreshIn = 0L
        )
    }
    
    data class CacheStats(
        val memoryCount: Int,
        val diskCount: Int,
        val validCount: Int,
        val nextRefreshIn: Long
    )
}

data class KLineData(
    val day: String,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long
)

data class StockSearchResult(
    val symbol: String,
    val name: String,
    val market: String
)
