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
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import com.example.stockdiary.common.APIEndpoints
import com.example.stockdiary.common.CacheConfig
import com.example.stockdiary.common.RequestLimits
import com.example.stockdiary.utils.PerformanceMonitor
import com.example.stockdiary.utils.StockCodeManager
import com.example.stockdiary.data.price.PriceCacheManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

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

    /**
     * 批量行情的有界并发度：每轮同步里新浪批量接口原本是串行
     * （N/20 批 × (耗时 + 300ms 批间延迟)），改成有界并发后最多同时发出
     * [MAX_BATCH_CONCURRENCY] 批，既提速又不会因并发过高把新浪打爆或触发更严的限流。
     */
    private const val MAX_BATCH_CONCURRENCY = 4

    /** 批量行情并发信号量：限制同时进行的批请求数（见 [getRealtimeData] 与 [fetchBatch]） */
    private val batchSemaphore = Semaphore(MAX_BATCH_CONCURRENCY)
    
    private val client = createSinaHttpClient()

    /**
     * 构造新浪行情专用 OkHttpClient。
     *
     * 抽成 internal 供单测直接驱动**真实** client 配置做端到端 gzip 契约验证
     * （见 SinaStockGzipDecompressionTest）——测试里若另建一个裸 client，
     * 恰好会把要防的回归（有人给请求加回手动 Accept-Encoding）漏掉。
     */
    internal fun createSinaHttpClient(): OkHttpClient = NetworkClient.sinaClient.newBuilder()
        .addInterceptor {
            val request = it.request().newBuilder()
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .addHeader("Referer", "https://finance.sina.com.cn")
                // 注意：**绝不能**手动加 Accept-Encoding（如 gzip/deflate）！
                // OkHttp 的 BridgeInterceptor 规则：请求若已带 Accept-Encoding，它就认为
                // 「调用方要自己解压」而**不再自动解压**。新浪对稍大的响应会返回 gzip 压缩体
                // （魔数 1f 8b），App 拿到压缩字节直接按 GBK 解码成乱码 → 正则
                // `var hq_str_(\w+)="..."` 匹配不到任何代码 → 新浪行情全部解析失败、
                // 静默回退腾讯（2026-10-25 诊断日志 12:56 轮「新浪成0/符败13」即此）。
                // 删掉这个头后，OkHttp 自动加 `Accept-Encoding: gzip` 并自动解压，
                // 上游拿到明文，解析恢复正常。
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

    /**
     * 构造新浪实时行情 URL。
     *
     * 注意：**不能**追加 `&rn=timestamp` 之类的 query 参数做缓存 bust——
     * hq.sinajs.cn 是老接口，只认路径里的 `list=<代码>`，会把 `&rn=...` 整体当成
     * 股票代码的一部分，导致返回 `var hq_str_sh601728&rn=...="";`（41 字节空响应），
     * 于是上层把它当「该源不支持此代码」静默回退腾讯，新浪事实上彻底失效。
     *
     * 而 [NetworkClient.sinaClient] 派生自 baseClient，未配置任何 OkHttp 磁盘缓存，
     * 没有缓存可 bust，故直接用裸路径即可（详见 2026-10-25 全面逻辑审计）。
     */
    internal fun buildRealtimeUrl(codeString: String): String = "$BASE_URL_REALTIME$codeString"
    
    fun initCache(context: Context) {
        PriceCacheManager.init(context)
    }
    
    override suspend fun getStockData(stockCode: String): Result<SinaStockData> {
        // 停牌代码集合必须是**每次调用一份**，不能用进程级共享字段：
        // 价格同步是 3 并发扇出的（手动刷新与后台 Worker 还可能撞车），共享字段会被
        // 别的协程 clear()/addAll() 抹掉或串味——后果是「停牌」被误判成「源级失败」
        // 喂给熔断器，累积 5 次可能把整个新浪源误禁；并发 add/clear 在非线程安全的
        // LinkedHashSet 上还可能抛 ConcurrentModificationException。
        val suspendedCodes = ConcurrentHashMap.newKeySet<String>()
        val result = getRealtimeData(listOf(stockCode), suspendedOut = suspendedCodes)
        return result.mapCatching { map ->
            map[stockCode] ?: if (stockCode in suspendedCodes) {
                // 明确告诉上层「这是停牌，不是数据源坏了」，不要去累计失败次数
                throw QuoteSuspendedException(stockCode)
            } else {
                throw IOException("无法获取股票数据: $stockCode")
            }
        }
    }
    
    suspend fun getRealtimeData(
        stockCodes: List<String>,
        context: Context? = null,
        forceRefresh: Boolean = false,
        /**
         * 停牌代码的收集出口：解析中发现「响应里出现了、但报价无效」的代码会写进这里。
         *
         * 由调用方传入**自己这次调用**的集合——绝不能用进程级共享字段：并发调用会互相
         * clear()/addAll()，把停牌误判成源级失败喂熔断器（见 [getStockData]）。
         */
        suspendedOut: MutableSet<String>? = null
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
                // 并行化后用 AtomicReference 保证多个批并发失败时只记第一次、不竞态覆盖。
                val lastBatchException = AtomicReference<Exception?>(null)

                // 批次并行：用信号量把同时进行的批请求限制在 [MAX_BATCH_CONCURRENCY] 以内，
                // 删除原先批间串行的 300ms 延迟——限流已改由信号量兜底。每批内部仍保留
                // batchPendingRequests 的并发去重与 request 收尾（见 [fetchBatch]）。
                val batchResults = coroutineScope {
                    batches.map { batch ->
                        async {
                            batchSemaphore.withPermit { fetchBatch(batch, context, suspendedOut, lastBatchException) }
                        }
                    }.awaitAll()
                }

                batchResults.forEach { batchResult ->
                    allResults.putAll(batchResult)
                }
                
                if (allResults.isEmpty()) {
                    val batchError = lastBatchException.get()
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
    
    /**
     * 抓一批代码（≤[RequestLimits.BATCH_SIZE] 个）的实时行情。
     *
     * 从 [getRealtimeData] 的批循环里抽出来的独立单元，便于在 [getRealtimeData] 里用
     * 有界并发（[batchSemaphore]）并行跑多批。保持全部既有行为不变：
     * - [batchPendingRequests] 并发去重（同一批 key 在飞只复用一次）；
     * - 成功写内存缓存 + 磁盘缓存（context != null 时）；
     * - 失败按既有规则透传原始异常、用旧缓存做部分回填、并收尾 [CompletableDeferred]
     *   （否则并发 await 它的协程会永久挂起）。
     *
     * @param lastBatchException 跨批共享的「首个批级异常」记录点（AtomicReference，并发安全）。
     */
    private suspend fun fetchBatch(
        batch: List<String>,
        context: Context?,
        suspendedOut: MutableSet<String>?,
        lastBatchException: AtomicReference<Exception?>
    ): Map<String, SinaStockData> {
        // 放在最外层 try 之前：try 块内声明的局部变量对其自身 catch 不可见（兄弟作用域），
        // 而内/外两层 catch 的日志都要带出失败 URL，故声明在最外层 try 外面。
        var reqUrl: String = "(未构造URL)"
        // 抽成独立函数后必须显式 return（块体函数不会像原内联 lambda 那样隐式返回末表达式）。
        return try {
            val batchKey = batch.sorted().joinToString(",")

            val existingRequest = batchPendingRequests[batchKey]
            if (existingRequest != null) {
                // 复用别人的在飞请求：失败必须**照原样透出**并记进 lastBatchException。
                // 若像以前那样直接 getOrElse { emptyMap() } 把失败吞成空结果，这个批次的
                // 原始异常就丢了：下面 allResults.isEmpty() 分支因为拿不到批级异常，
                // 会退化成符号级文案「未获取到股票数据」→ 上层源级熔断器把真·源级故障
                // （超时 / 连接失败 / 403 限流）误判成「代码不支持」而**永不熔禁**——
                // 恰恰是本函数上方注释要修掉的「饿死」问题，而且在并发去重路径上
                // （手动刷新撞上后台 Worker）必然命中。
                val shared = existingRequest.await()
                shared.exceptionOrNull()?.let { err ->
                    if (lastBatchException.get() == null) {
                        lastBatchException.compareAndSet(null, err as? Exception ?: Exception(err))
                    }
                }
                return shared.getOrElse { emptyMap() }
            }

            val request = CompletableDeferred<Result<Map<String, SinaStockData>>>()
            batchPendingRequests[batchKey] = request

            try {
                val codeString = batch.joinToString(",")
                val url = buildRealtimeUrl(codeString)

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
                    suspendedOut?.addAll(suspended)

                    batchResult.forEach { (code, data) ->
                        cacheData(code, data)
                        if (context != null) {
                            PriceCacheManager.cachePrice(code, data, "sina")
                        }
                    }

                    request.complete(Result.success(batchResult))
                    return@use batchResult
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("批次请求失败 url=$reqUrl: ${e.message}", e, TAG)
                request.complete(Result.failure(e))
                // 并发下多个批可能同时失败：只记第一个（CAS 保证），避免后面的异常覆盖先到的根因。
                if (lastBatchException.get() == null) {
                    lastBatchException.compareAndSet(null, e)
                }
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
                // 取消路径（以及任何异常提前退出）必须把 deferred 收尾：
                // CompletableDeferred 没有父 Job，**不会**随本协程取消而完成。
                // 不显式 cancel，其它作用域里已经 await() 它的协程会永久挂起
                // （典型：AssetSyncManager 看门狗超预算 cancel 了本轮，
                //   而后台 Worker 的同步正 await 同一个批次键 → 卡到 WorkManager 超时）。
                // 与 UnifiedPriceService 里的同一道保险对齐。
                if (!request.isCompleted) {
                    request.cancel()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("批次请求异常 url=$reqUrl: ${e.message}", e, TAG)
            if (lastBatchException.get() == null) {
                lastBatchException.compareAndSet(null, e)
            }
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

    suspend fun getKLineData(stockCode: String, scale: Int = 240, ma: String = "5", dataLen: Int = 100): Result<List<KLineData>> {
        return withContext(Dispatchers.IO) {
            try {
                val formattedCode = formatStockCode(stockCode)
                val url = "$BASE_URL_HISTORY?symbol=$formattedCode&scale=$scale&ma=$ma&datalen=$dataLen"
                AppLogger.apiUrl("GET", url, TAG)
                
                val request = Request.Builder()
                    .url(url)
                    .build()
                
                val response = activeClient.newCall(request).execute()
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
                
                val response = activeClient.newCall(request).execute()
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
