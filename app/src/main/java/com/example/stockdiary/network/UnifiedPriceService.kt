package com.example.stockdiary.network

import android.content.Context
import com.example.stockdiary.common.APIEndpoints
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.data.price.StockDataManager
import com.example.stockdiary.network.cache.CacheManager
import com.example.stockdiary.network.model.AssetPrice
import com.example.stockdiary.network.model.CryptoData
import com.example.stockdiary.network.model.FundData
import com.example.stockdiary.utils.AppLogger
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
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

    /** 进程内价格缓存上限：与 [com.example.stockdiary.data.price.PriceCacheManager] / [SinaStockAPI] 同量级（~200），避免长会话里无限增长 */
    private const val PRICE_CACHE_MAX_ENTRIES = 200

    /** 缓存条目：价格 + 写入时间，用于判断过期 */
    private data class CacheEntry(val price: AssetPrice, val timestamp: Long)

    private val priceCache = ConcurrentHashMap<String, CacheEntry>()
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<Result<AssetPrice>>>()

    private fun isFresh(entry: CacheEntry): Boolean =
        System.currentTimeMillis() - entry.timestamp < PRICE_CACHE_TTL_MS

    /**
     * 价格缓存超过上限时淘汰最旧条目，防止进程内无限增长。
     * 先清一遍已过 TTL 的条目（新鲜度优先），还不够再按写入时间淘汰最旧的——
     * 刷新窗口内刚写入的条目时间戳最新，不会被误删（不会在同步进行中丢热点键）。
     */
    private fun trimPriceCacheIfNeeded() {
        if (priceCache.size <= PRICE_CACHE_MAX_ENTRIES) return
        val expireThreshold = System.currentTimeMillis() - PRICE_CACHE_TTL_MS
        val expired = priceCache.filter { it.value.timestamp < expireThreshold }.keys
        expired.forEach { priceCache.remove(it) }
        if (priceCache.size > PRICE_CACHE_MAX_ENTRIES) {
            val sorted = priceCache.entries.sortedBy { it.value.timestamp }
            val removeCount = priceCache.size - PRICE_CACHE_MAX_ENTRIES
            sorted.take(removeCount).forEach { priceCache.remove(it.key) }
        }
    }

    suspend fun getPrice(
        code: String,
        type: AssetType,
        forceRefresh: Boolean = false,
        /**
         * 批量预取回填的现成价格。非 null 时直接返回它，**完全跳过**门面 60s 缓存与下游取价，
         * 用于刷新编排器把一批 STOCK/场内基金价格一次性批量取回后，逐标的不再重复发请求
         * （见 [AssetSyncManager]）。这是 P3 批量取价的关键短路点。
         */
        batchResult: AssetPrice? = null,
        /**
         * 本次同步开始时一次性算好的网络可用性。传入后 [StockDataManager]
         * 不再每个标的都去问 ConnectivityManager（O(n) 冗余系统调用，P4）。
         * 为 null 时回落到原行为（实时读取）。
         *
         * 数据源顺序是全自动的（妙想Skill(配置了Key才参与) > 新浪 > 腾讯，失败由数据源级
         * 熔断器自动降级），没有任何用户偏好参数——「我的 → 行情数据源」设置已去除。
         */
        networkAvailable: Boolean? = null
    ): Result<AssetPrice> = withContext(Dispatchers.IO) {
        val cacheKey = "${type.name}_$code"

        // 批量回填优先：刷新编排器已经把这只标的的价取回来了，直接返回，绝不重复打网络
        if (batchResult != null) return@withContext Result.success(batchResult)

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
                AssetType.STOCK -> getStockData(code, forceRefresh, networkAvailable)
                AssetType.FUND -> getFundPrice(code, forceRefresh).map { AssetPrice.Fund(it) }
                AssetType.CRYPTO -> getCryptoPrice(code).map { AssetPrice.Crypto(it) }
                else -> Result.failure(Exception("不支持的资产类型: $type"))
            }

            result.onSuccess { price ->
                priceCache[cacheKey] = CacheEntry(price, System.currentTimeMillis())
                trimPriceCacheIfNeeded()
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
    private suspend fun getStockData(
        code: String,
        forceRefresh: Boolean,
        networkAvailable: Boolean? = null
    ): Result<AssetPrice> {
        val manager = stockDataManager
            ?: return Result.failure(IllegalStateException("行情门面未初始化（UnifiedPriceService.init 未调用）"))
        // StockDataManager 用 common.Result，门面对外是 kotlin.Result，这里做转换
        return when (val result = manager.getStockData(code, forceRefresh, networkAvailable)) {
            is com.example.stockdiary.common.Result.Success -> Result.success(AssetPrice.Stock(result.data))
            is com.example.stockdiary.common.Result.Error -> Result.failure(result.exception)
            is com.example.stockdiary.common.Result.Loading -> Result.failure(IllegalStateException("行情结果尚未加载完成"))
        }
    }

    /**
     * 批量预取 STOCK/场内基金行情（走新浪批量接口，N 个代码 ≈ ⌈N/20⌉ 次请求）。
     *
     * 刷新编排器在逐标的扇出**之前**调用一次：结果会双写本门面的 60s 缓存
     * （[priceCache]）与下层 StockDataManager / PriceCacheManager，使得随后每个标的的
     * [getPrice] 命中 [batchResult] 短路或 60s 缓存，**完全不再逐标的打网络**。
     *
     * @return `kotlin.Result` 包着的 `code -> AssetPrice.Stock` 映射（仅含成功取回的代码）。
     *   网络不可用或代码为空时返回空 map；底层批量失败时也返回空 map——
     *   调用方据此让逐标的 [getPrice] 走原有多源兜底，绝不因批量失败而放大请求量。
     */
    suspend fun prewarmStockPrices(
        codes: List<String>,
        forceRefresh: Boolean,
        networkAvailable: Boolean
    ): kotlin.Result<Map<String, AssetPrice>> = withContext(Dispatchers.IO) {
        if (!networkAvailable || codes.isEmpty()) return@withContext Result.success(emptyMap())
        val manager = stockDataManager
            ?: return@withContext Result.failure(
                IllegalStateException("行情门面未初始化（UnifiedPriceService.init 未调用）")
            )
        when (val result = manager.getBatchStockDataThroughSina(codes, forceRefresh)) {
            is com.example.stockdiary.common.Result.Success -> {
                val priceMap = result.data.mapValues { (_, stockData) -> AssetPrice.Stock(stockData) }
                // 写门面 60s 缓存：即便 batchResult 短路没兜住，逐标的 getPrice 也能命中这里
                priceMap.forEach { (code, price) ->
                    priceCache["${AssetType.STOCK.name}_$code"] = CacheEntry(price, System.currentTimeMillis())
                }
                Result.success(priceMap)
            }
            is com.example.stockdiary.common.Result.Error -> Result.failure(result.exception)
            is com.example.stockdiary.common.Result.Loading ->
                Result.failure(IllegalStateException("行情结果尚未加载完成"))
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
                    netWorthDate = fundInfo.date,
                    millionCopiesIncome = fundInfo.millionCopiesIncome
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
    
    /**
     * 取加密货币最新价。
     *
     * ## 交易对映射（本项目踩过的坑，别再改回去）
     *
     * 库里存的 symbol 有两种形态：币安**导入**存的是 `BTCUSDT`（`baseAssetToSymbol` 转过），
     * **手动添加**的则是用户原样输入的 `BTC`。早期实现直接拿 `code.uppercase()` 拼 URL，
     * 于是手动添加的币永远拼出 `?symbol=BTC` —— 币安没有这个交易对，返回 400，
     * 症状是"手动加的加密货币永远取不到价，而导入的可以"。
     *
     * 现在统一走 [BinanceAPI.cryptoTradingPairFor]：它先判「自身就是报价币」再判
     * 「以报价币结尾」，两种形态都认，稳定币还能省掉一次注定为空的请求。
     *
     * ## 理财/锁仓（LD 前缀）按底层 1:1 计价
     *
     * `LD` 是 Simple Earn / Locked Staking 的持仓壳层标记，币安没有 `LDXXXUSDT`
     * 市场，直接拿它查价只会 400 或超时。但 LD 与底层资产 1:1 可赎回
     * （LDBNB 就是 1 个 BNB），所以剥掉 LD 用底层交易对查价（见
     * [BinanceAPI.earnUnderlyingPair]）——这些持仓导入时 purchasePrice = 0，
     * 不查价 totalValue 就恒为 0，总资产会少算整整一块。
     *
     * ## 错误提示
     *
     * 复用 [BinanceAccountAPI.binanceError]：币安按条款挡部分地区/IP（常返 451），
     * 早期这里只甩一句 `HTTP 451`，用户不知道该干嘛。同一套口径现在也告诉用户
     * "更换到允许地区的节点后重试"。
     */
    internal suspend fun getCryptoPrice(
        code: String,
        /**
         * 取价用的 OkHttp 客户端。**默认走全局 [NetworkClient.binanceClient]**；
         * 仅单测会传入带假 [okhttp3.Interceptor] 的客户端来驱动不同 HTTP 语义
         * （2xx / 451 地区封锁 / 超时），避免真出网。生产调用一律用默认值。
         */
        client: OkHttpClient = NetworkClient.binanceClient
    ): Result<CryptoData> = withContext(Dispatchers.IO) {
        // 理财/锁仓产品（LD 前缀）不是现货交易对，币安没有 LDXXXUSDT 这种市场。
        // 但 LD 代币与底层资产 1:1 可赎回，估值应剥掉 LD 用底层交易对查价——
        // 否则这些持仓的 currentPrice 永远为空，而导入时 purchasePrice = 0，
        // totalValue 恒为 0，总资产会少算整整一块（见 BinanceAPI.earnUnderlyingPair）。
        // 剥不掉底层的（稳定币壳 LDUSDT 等）才按「无行情」跳过，不计同步失败。
        var pair: String? = null
        if (BinanceAPI.isEarnProduct(code)) {
            pair = BinanceAPI.earnUnderlyingPair(code)
            if (pair == null) {
                // 底层仍是稳定币（如 LDUSDT）：没有现货交易对，但锚定 1 美元，
                // 与现货稳定币同口径按 1.0 计，而不是当成「无行情」跳过——
                // 否则存量 LDUSDT 的 currentPrice 永远留着导入前的 0，市值恒为 0
                // （用户看到的「USDT 价格默认为 0」正是这个）。earnUnderlyingPair
                // 返回 null 当且仅当剥掉 LD 后底层是稳定币，所以这里判一次集合即可。
                val underlying = code.trim().uppercase().removePrefix("LD")
                if (underlying in BINANCE_STABLECOINS) {
                    AppLogger.d("理财/锁仓稳定币 $code 按 1.0 美元计", TAG)
                    return@withContext Result.success(
                        CryptoData(code = code.uppercase().trim(), name = code.uppercase().trim(), price = 1.0, change = null, changePercent = null)
                    )
                }
                AppLogger.d("理财/锁仓产品 $code 无底层交易对，跳过查价", TAG)
                return@withContext Result.failure(
                    NonTradableAssetException("$code 是币安理财/锁仓产品，没有现货行情")
                )
            }
            AppLogger.d("理财/锁仓产品 $code 按底层 $pair 1:1 计价", TAG)
        } else {
            pair = BinanceAPI.cryptoTradingPairFor(code)
        }
        // 稳定币锚定 1 美元，币安上没有 XXXUSDT 自我交易对，查价必然空——直接按 1.0 计
        // （pair 为 null 即稳定币）。非 USDT 报价的交易对（ADABTC / ETHBTC）其 lastPrice
        // 是「以报价币计」，不能直接当美元，统一转成基础资产的 USDT 对按美元估值（见 P2）。
        val pricedPair = pair?.let { BinanceAPI.toUsdtPair(it) }
        if (pricedPair == null) {
            AppLogger.d("稳定币 $code 按 1.0 美元计，跳过查价", TAG)
            return@withContext Result.success(
                CryptoData(code = code.uppercase().trim(), name = code.uppercase().trim(), price = 1.0, change = null, changePercent = null)
            )
        }
        val url = "${APIEndpoints.Binance.BASE_URL}/ticker/24hr?symbol=$pricedPair"

        // 主机级熔断闸门：币安连续网络级失败达阈值后，冷却期内直接短路、不发请求。
        // 主机压根连不上时，逐个标的各烧最多 20s 连接超时纯属浪费，且会计入
        // 同步失败数触发误导性通知（见 BinanceHostGate）。tryAcquireProbe 带副作用：
        // 冷却结束那一刻，竞争到的这一个请求会成为半开探测（只此一个，其余仍拒）。
        if (!BinanceHostGate.tryAcquireProbe(System.currentTimeMillis())) {
            return@withContext Result.failure(
                BinanceHostUnavailableException(
                    "币安接口连续失败，已暂停访问 ${BinanceHostGate.COOLDOWN_MS / 1000} 秒"
                )
            )
        }

        try {
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0")
                .build()

            val startTime = System.currentTimeMillis()
            // 与币安其他客户端一致：同主机、同连接池（绝对 URL 下 BaseUrlInterceptor 直通）
            val response = client.newCall(request).execute()
            response.use {
                val duration = System.currentTimeMillis() - startTime

                AppLogger.api("GET /ticker/24hr (symbol=$pair)", it.message, duration, TAG)

                val body = it.body?.string() ?: return@withContext Result.failure(Exception("币安返回了空响应"))

                if (!it.isSuccessful) {
                    // 451/403 = 地区封锁 / 访问被拒：主机可达但本用户用不了，等同「主机不可用」，
                    // 喂给熔断器使其 OPEN 短路后续请求，避免每个币都白打一次 451、还各计一次
                    // 同步失败（旧代码在 isSuccessful 检查前就调 onSuccess，把 451 探活当成功，
                    // 熔断一解除又被 451、循环空转——地区封锁用户每轮同步都被 451 刷屏）。
                    // 其余 4xx/5xx（400 非法符号、429 限流、5xx 服务端错）主机其实可达，
                    // 仍按旧口径在下方 2xx 分支复位计数，只是价格本轮失败。
                    if (it.code == 451 || it.code == 403) {
                        BinanceHostGate.onNetworkFailure(System.currentTimeMillis())
                    }
                    // 带上币安的 code+msg，并按需补地区限制的中文可操作提示
                    return@withContext Result.failure(BinanceAccountAPI.binanceError(body, it.code))
                }

                if (!body.contains("lastPrice")) {
                    return@withContext Result.failure(Exception("响应格式错误"))
                }

                // 2xx：真正成功。复位熔断连续失败计数，并（若正处于半开探测）闭路。
                // ⚠️ 必须在 isSuccessful 检查之后才调用——否则 451 会被当成成功、熔断永远
                // 顶不上地区封锁，反而每轮被 451 刷屏（见上面的 451 分支说明）。
                BinanceHostGate.onSuccess()

                // 注意传的是原 code（如 LDBNB）而不是底层交易对：落库 key 必须是
                // 原 symbol，价格才是底层币 1:1 的市价——不能把资产名/代码改成 BNBUSDT
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
            // 网络级失败（连不上 / 超时 / 域名解析失败 / IO 错误）喂给主机级熔断器：
            // 主机不可达时每轮同步会为每个加密货币各烧最多 20s，全计失败还触发
            // 「同步多次失败」通知；连续达阈值后冷却期内直接短路（见 BinanceHostGate）。
            // CancellationException 已在上面重抛，不会被误计成失败。
            if (e is ConnectException || e is SocketTimeoutException || e is UnknownHostException || e is IOException) {
                BinanceHostGate.onNetworkFailure(System.currentTimeMillis())
            }
            AppLogger.e("获取加密货币价格失败: $code", e, TAG)
            // 连接类失败（连不上/超时/域名解析不了）换成一句人话：OkHttp 原始 message
            // 形如 "failed to connect to api.binance.com/2001:… after 10000ms"，
            // 既看不懂又把设备 IP/端口抖给用户。币安国际站在部分网络/地区连不上是常态。
            val userFacing = if (e is ConnectException || e is SocketTimeoutException || e is UnknownHostException) {
                Exception("连不上币安服务器，可能被网络或地区限制，请检查网络或代理后重试")
            } else {
                e
            }
            Result.failure(userFacing)
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
