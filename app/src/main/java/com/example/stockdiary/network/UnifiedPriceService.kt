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
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

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

    /**
     * 进程级「已知不可交易」code 黑名单（币安现货无交易对）。
     *
     * 逐只回落 [getCryptoPrice] 向币安确认某 code 底层无现货盘（400 无效符号 /
     * LD 理财无底层）后登记；后续 [prewarmCryptoPrices] 构建批量请求前剔除、
     * [getCryptoPrice] 入口直接判不可交易短路——避免这些 code 每轮污染批量整片
     * 导致整片 400、吃掉批量预取收益（真机诊断 20261026/1029 实锤：1MBBDOGE /
     * SHIB2 类底层无盘标的每轮都触发整片 400 → 切小重试 → 逐只白打网络）。
     *
     * 进程级、非持久化：重启即清。不持久化是刻意的——币安上新交易对时持久化黑名单
     * 会误杀需手动清缓存；进程内「首轮脏、后续轮干净」已足够（自动同步每轮都预热）。
     * 线程安全：getCryptoPrice 被同步扇出 6 并发调用，写入点并发，故用 ConcurrentHashMap 集合。
     */
    private val nonTradableCodes = ConcurrentHashMap.newKeySet<String>()

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

    /**
     * 加密货币批量行情的单片符号数上限。
     *
     * 币安批量端点 `GET /ticker/24hr?symbols=["A","B",...]` 一次可取上百个交易对，
     * 但 URL 不能无限长（过长会被中间节点拒 / HTTP 414）。取 60：真实组合 129 个加密标的
     * → 2~3 次请求；即便上千标的也是十几次，远比逐标的划算。
     */
    internal const val CRYPTO_BATCH_SYMBOLS = 60

    /**
     * 整片返回 400 时的**切小重试**子片大小。
     *
     * 400 = 非法符号，且币安对「含任一非法 symbol」的整批直接 400——于是用户组合里
     * 长期存在的填错 symbol（LDSHIB2 / LD1MBBDOGE…）会让它所在的整片（最多 60 个）
     * **每轮**都被打回逐标的，批量预取的收益被长期吃掉一块。
     *
     * 故整片 400 时按本常量切成子片重试**一层**：把「一个坏码拖累 60 个」降级成
     * 「只拖累它所在子片（最多 10 个）」。取 10 是请求数与连带损失的折中——
     * 再小（如 1）请求数会退回逐标的数量级，失去批量意义。
     *
     * ⚠️ 只切**一层**、不递归：子片再失败就直接回落逐标的（见 [prewarmCryptoPrices]）。
     */
    internal const val CRYPTO_BATCH_RETRY_SYMBOLS = 10

    /**
     * 批量预取加密货币行情（走币安批量端点，N 个标的 ≈ ⌈N/[CRYPTO_BATCH_SYMBOLS]⌉ 次请求）。
     *
     * ## 为什么要有它
     *
     * 真机诊断（20261026_230142）：每轮同步为每个加密标的各发一次 `?symbol=XXXUSDT`，
     * 单轮约 **129 次**币安请求、全轮约 230 次；而稳态单请求仅 152ms，786 条样本里慢请求
     * 全是网络等待、无一例解析慢——瓶颈是**请求数 × 并发度 6**，不是解析。批量端点把
     * 129 次压到 2~3 次，是这个头号耗时源最直接的提速点。
     *
     * ## 与 [prewarmStockPrices] 同构
     *
     * 刷新编排器在逐标的扇出**之前**调用一次：结果双写本门面 60s 缓存（[priceCache]），
     * 并作为 `batchResult` 回填，使随后每个标的的 [getPrice] 命中
     * **第 108 行的 batchResult 短路**（该短路在 `when(type)` 之前，对 CRYPTO 同样生效），
     * 完全不再逐标的打网络。
     *
     * ## 归一化必须与 [getCryptoPrice] 完全一致
     *
     * 稳定币 → 1.0；LD 理财/锁仓 → [BinanceAPI.earnUnderlyingPair] 剥底层（底层是稳定币壳
     * 如 LDUSDT → 1.0）；底层再走 [BinanceAPI.cryptoSymbolCandidates] + [BinanceAPI.toUsdtPair]。
     * 结果一律按**原始 code** 建 map 与填 `CryptoData.code`（如 LDBNB，绝不能写成底层
     * BNBUSDT）——理由见 [getCryptoPrice] 里那段注释。
     *
     * ## 失败语义（照抄 [prewarmStockPrices]）：绝不放大请求量
     *
     * 某片失败 / 非 2xx / 解析为空时，该片对应的 code **不进 map**，让它们回落逐标的
     * [getCryptoPrice] 原路径；整体仍返回成功（partial 也算成功）。
     *
     * **唯一例外是 HTTP 400**：币安对「含任一非法 symbol」的整批直接 400，而用户组合里
     * 填错的 symbol（LDSHIB2 / LD1MBBDOGE…）是长期存在的，会让它所在的整片（最多
     * [CRYPTO_BATCH_SYMBOLS] 个）**每轮**都被打回逐标的、长期吃掉批量收益。故整片 400 时
     * 按 [CRYPTO_BATCH_RETRY_SYMBOLS] 个一片**切小重试一层**，把「一个坏码拖累 60 个」
     * 降级成「只拖累它所在子片（最多 10 个）」。子片再失败就回落，**不递归**。
     * 5xx / 451 / 429 / 网络异常一律不重试——那是源或网络层面的问题，重试只会放大请求量。
     *
     * ## 熔断口径（照抄 [getCryptoPrice]）：一次预取只记一次
     *
     * 451/403 → [FailureType.REGION_BLOCKED]，网络异常 → [FailureType.TRANSIENT]；**确有分片取到
     * 行情**才 [BinanceHostGate.onSuccess] 复位计数。400/5xx/429/解析失败既不喂失败、也不清零
     * （与 [getCryptoPrice] 同口径）——否则一次机房 5xx 就把累计的瞬断计数洗白。
     * ⚠️ 一次预取**只调一次**熔断记账，绝不按片数、更不按片内标的数量重复记账——
     * 否则一次批量超时就把阈值（8）打满，整机误熔。
     *
     * @param forceRefresh false 时跳过门面 60s 缓存里仍新鲜的标的（它们本就会命中缓存，
     *   预取纯属浪费请求）；true 时全部回源。
     * @param client 取价用的客户端，默认全局 [NetworkClient.binanceClient]；仅单测注入假
     *   [okhttp3.Interceptor] 驱动不同 HTTP 语义（与 [getCryptoPrice] 同惯例）。
     * @param deadlineMs 本轮同步的时间预算截止点（epoch ms，默认 [Long.MAX_VALUE] 表示不限）。
     *   预取是**阻塞**在 OkHttp execute() 里的，协程取消打不断它；只有靠这个截止点
     *   在片与片之间主动收手，否则整片超时会把刷新的时间预算吃光（见调用方
     *   AssetSyncManager 的看门狗注释：看门狗在这段跑完之前还没启动）。
     * @return `code -> AssetPrice.Crypto` 映射（仅含成功取回的代码）。
     */
    internal suspend fun prewarmCryptoPrices(
        codes: List<String>,
        forceRefresh: Boolean,
        client: OkHttpClient = NetworkClient.binanceClient,
        deadlineMs: Long = Long.MAX_VALUE
    ): Result<Map<String, AssetPrice>> = withContext(Dispatchers.IO) {
        if (codes.isEmpty()) return@withContext Result.success(emptyMap())

        val resultMap = LinkedHashMap<String, AssetPrice>()
        /** 原 code -> 要查的交易对（多个 code 可能归一到同一交易对，如 BTC 与 BTCUSDT） */
        val codeToPair = LinkedHashMap<String, String>()

        for (rawCode in codes.distinct()) {
            val code = rawCode.trim()
            if (code.isEmpty()) continue
            val codeNorm = code.uppercase()

            // 已知在币安现货无交易对的 code（逐只回落时已向币安确认过）：直接剔除，不进
            // 批量请求——否则会污染整片、导致整片 400 吃掉批量预取收益（见 [nonTradableCodes]）。
            if (codeNorm in nonTradableCodes) {
                AppLogger.d("跳过已知不可交易标的 $code，不进批量行情请求", TAG)
                continue
            }

            // 稳定币锚定 1 美元：币安上没有自我交易对，查价必然空——直接按 1.0 计，不进批量请求
            // 稳定币识别统一走 BinanceAPI.isLikelyStablecoin（白名单 + 命名兜底），
            // 与导入路径口径一致——白名单外、命名疑似稳定币的币（如 NEWUSD/USDG）
            // 也能按 1.0 美元计，不再退化成裸码查价 → 查不到 → 市值 0。
            if (BinanceAPI.isLikelyStablecoin(codeNorm)) {
                resultMap[code] = AssetPrice.Crypto(stableCoinData(codeNorm))
                continue
            }

            val candidates: List<String> = if (BinanceAPI.isEarnProduct(code)) {
                val underlying = BinanceAPI.earnUnderlyingPair(code)
                if (underlying == null) {
                    // 底层仍是稳定币壳（如 LDUSDT）：没有现货交易对，但锚定 1 美元，
                    // 与现货稳定币同口径按 1.0 计（否则存量 LDUSDT 市值恒为 0）
                    val stripped = codeNorm.removePrefix("LD")
                    if (BinanceAPI.isLikelyStablecoin(stripped)) {
                        resultMap[code] = AssetPrice.Crypto(stableCoinData(codeNorm))
                    }
                    // 其余无底层的理财壳：不进 map，回落逐标的（那里会抛 NonTradableAssetException）
                    continue
                }
                BinanceAPI.cryptoSymbolCandidates(underlying)
            } else {
                BinanceAPI.cryptoSymbolCandidates(code)
            }

            // 批量端点一次查多个 symbols，无法像单只那样「首候选 400 再试兜底候选」
            // （一个非法符号会拖累整片）。所以只取主候选；取不到的标的本轮不进 map，
            // 回落逐标的 [getCryptoPrice]——那里会逐个试候选，正确性不丢。
            val pricedPair = candidates.firstOrNull()
                ?.let { BinanceAPI.toUsdtPair(it) }
                ?.takeIf { it.isNotEmpty() }
            if (pricedPair == null) continue

            // 非强制刷新时跳过门面 60s 缓存里仍新鲜的标的：它们本就会命中缓存，
            // 预取纯属浪费请求（这批不进 map，逐标的 getPrice 走缓存返回同一个价）。
            if (!forceRefresh) {
                val cached = priceCache["${AssetType.CRYPTO.name}_$code"]
                if (cached != null && isFresh(cached)) continue
            }
            codeToPair[code] = pricedPair
        }

        // 交易对去重：BTC 与 BTCUSDT 归一到同一个 BTCUSDT，只查一次
        val pairsToFetch = codeToPair.values.distinct()
        if (pairsToFetch.isEmpty()) {
            // 全是稳定币 / 不可交易 / 命中新鲜缓存：一次请求都不用发
            writeCryptoPricesToCache(resultMap)
            return@withContext Result.success(resultMap)
        }

        // 主机级熔断闸门：一次批量预取 = 一次主机探测（不按片、更不按标的重复记账）
        if (!BinanceHostGate.tryAcquireProbe(System.currentTimeMillis())) {
            AppLogger.d("币安主机熔断中，跳过加密货币批量预取（回落逐标的）", TAG)
            writeCryptoPricesToCache(resultMap)
            return@withContext Result.success(resultMap)
        }

        var sawRegionBlocked = false
        var sawTransient = false
        /** 本轮是否真有片取到行情：全是 5xx/400/解析空时**不能**当成功去复位熔断计数 */
        var sawQuotes = false

        /**
         * 记一次片级失败。**只置标记、不直接调熔断器**——整轮结束时统一记一次
         * （见下方 when），保证「一次预取只记一次成败」的语义不被重试打破。
         */
        fun noteFailure(type: FailureType?) {
            when (type) {
                FailureType.REGION_BLOCKED -> sawRegionBlocked = true
                FailureType.TRANSIENT -> sawTransient = true
                null -> Unit // 400/5xx/解析失败：与 getCryptoPrice 同口径，不喂熔断器
            }
        }

        /**
         * 把一片的报价回填到 [resultMap]，只处理 [scope]（本次请求的交易对）内的 code。
         * 查不到报价的交易对（币安没返回）不进 map，回落逐标的。
         */
        fun applyQuotes(quotes: Map<String, CryptoQuote>, scope: Set<String>) {
            for ((code, pair) in codeToPair) {
                val p = pair.uppercase()
                if (p !in scope) continue
                val quote = quotes[p] ?: continue
                resultMap[code] = AssetPrice.Crypto(quote.toCryptoData(code))
            }
        }

        val context = currentCoroutineContext()
        for (shard in pairsToFetch.chunked(CRYPTO_BATCH_SYMBOLS)) {
            // 取消 / 预算：预取阻塞在 execute() 里，取消打不断，只能在片与片之间主动收手。
            // 这两种情况下**不做熔断记账**（不是主机不可达的证据），直接带着已取得的价返回。
            if (!context.isActive || System.currentTimeMillis() >= deadlineMs) {
                if (context.isActive) {
                    AppLogger.w("加密货币批量预取已达时间预算，跳过剩余分片", TAG)
                }
                writeCryptoPricesToCache(resultMap)
                return@withContext Result.success(resultMap)
            }

            val fetched = fetchCryptoTickerShard(shard, client)
            if (fetched.quotes != null) {
                sawQuotes = true
                applyQuotes(fetched.quotes, shard.toHashSet())
                continue
            }

            // 整片失败。只有 **400（非法符号）** 值得切小重试——5xx/451/429/网络异常
            // 是源或网络层面的问题，重试只会放大请求量，一律不重试。
            if (fetched.httpCode == 400) {
                AppLogger.w(
                    "加密货币批量行情整片 400（${shard.size} 个交易对），" +
                        "按 $CRYPTO_BATCH_RETRY_SYMBOLS 个一片切小重试一次",
                    TAG
                )
                for (subShard in shard.chunked(CRYPTO_BATCH_RETRY_SYMBOLS)) {
                    // 切小重试同样受取消与预算约束：一个超时片再切 6 个子片，
                    // 不拦的话单轮可再烧掉 6 × callTimeout
                    if (!context.isActive || System.currentTimeMillis() >= deadlineMs) break
                    val subFetched = fetchCryptoTickerShard(subShard, client)
                    if (subFetched.quotes != null) {
                        sawQuotes = true
                        applyQuotes(subFetched.quotes, subShard.toHashSet())
                    } else {
                        // 子片仍失败：直接回落逐标的，**不再往下切**（只切一层，不递归）
                        noteFailure(subFetched.failureType)
                    }
                }
                continue
            }

            // 非 400 的整片失败：对应 code 不进 map，回落逐标的，不重试
            noteFailure(fetched.failureType)
        }

        // 协程已取消（切后台等）：这一轮记账不算数，不喂熔断器，直接返回已采集的部分
        if (!context.isActive) {
            writeCryptoPricesToCache(resultMap)
            return@withContext Result.success(resultMap)
        }

        // 整轮只记一次成败：地区封锁优先于瞬断（语义更重）。
        // ⚠️ 只有**真的取到行情**（sawQuotes）才复位计数：全片 5xx / 429 / 解析为空时
        // 一律不喂熔断器，但也不能当成功去清零——否则一次机房故障就把累计的瞬断计数洗白。
        val now = System.currentTimeMillis()
        when {
            sawRegionBlocked -> BinanceHostGate.onNetworkFailure(now, FailureType.REGION_BLOCKED)
            sawTransient -> BinanceHostGate.onNetworkFailure(now, FailureType.TRANSIENT)
            sawQuotes -> BinanceHostGate.onSuccess()
            else -> Unit
        }

        writeCryptoPricesToCache(resultMap)
        AppLogger.i("加密货币批量预取 ${codes.distinct().size} 个代码，命中 ${resultMap.size} 个", TAG)
        Result.success(resultMap)
    }

    /** 稳定币 / LD 稳定币壳的 1.0 美元锚定行情（与 [getCryptoPrice] 同口径） */
    private fun stableCoinData(codeNorm: String): CryptoData =
        CryptoData(code = codeNorm, name = codeNorm, price = 1.0, change = null, changePercent = null)

    /** 双写门面 60s 缓存：即便 batchResult 短路没兜住，逐标的 [getPrice] 也能命中这里 */
    private fun writeCryptoPricesToCache(priceMap: Map<String, AssetPrice>) {
        if (priceMap.isEmpty()) return
        priceMap.forEach { (code, price) ->
            priceCache["${AssetType.CRYPTO.name}_$code"] = CacheEntry(price, System.currentTimeMillis())
        }
        trimPriceCacheIfNeeded()
    }

    /** 单条批量行情的原始报价（解析中间态，最终按原始 code 组装成 [CryptoData]） */
    private data class CryptoQuote(
        val price: Double,
        val change: Double?,
        val changePercent: Double?,
        val high: Double?,
        val low: Double?,
        val volume: Long?
    ) {
        fun toCryptoData(code: String): CryptoData = CryptoData(
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
    }

    /**
     * 单片批量请求的结果。
     *
     * @param quotes 解析出的 `交易对 -> 报价`；null = 本片失败，对应 code 应回落逐标的
     * @param failureType 失败时要喂给 [BinanceHostGate] 的类型；null = 不喂
     *   （400 / 5xx / 解析为空，与 [getCryptoPrice] 同口径：只有地区封锁与网络异常才记账）
     * @param httpCode 失败时的 HTTP 状态码；null = 网络异常或解析为空。
     *   **只有 400 才值得切小重试**（见 [prewarmCryptoPrices]），故单独带出来。
     */
    private data class ShardFetch(
        val quotes: Map<String, CryptoQuote>?,
        val failureType: FailureType?,
        val httpCode: Int? = null
    )

    /**
     * 发一次批量行情请求（`?symbols=["A","B",...]`）并解析。
     *
     * 失败一律通过 [ShardFetch] 返回，**不抛异常**：调用方据此让该片回落逐标的，
     * 而不是让整个预取失败。[CancellationException] 例外——协程取消必须向上传播。
     */
    private fun fetchCryptoTickerShard(shard: List<String>, client: OkHttpClient): ShardFetch {
        val symbolsJson = shard.joinToString(",", prefix = "[", postfix = "]") { "\"${it.uppercase()}\"" }
        val url = "${APIEndpoints.Binance.MARKET_DATA_BASE_URL}/ticker/24hr?symbols=" +
            URLEncoder.encode(symbolsJson, "UTF-8")
        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", "Mozilla/5.0")
            .build()
        val startTime = System.currentTimeMillis()
        return try {
            client.newCall(request).execute().use { resp ->
                val duration = System.currentTimeMillis() - startTime
                AppLogger.api("GET /ticker/24hr (symbols=${shard.size})", resp.message, duration, TAG)
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    AppLogger.w(
                        "加密货币批量行情 HTTP ${resp.code}（${shard.size} 个交易对），本片回落逐标的",
                        TAG
                    )
                    // 451/403 = 地区封锁（主机可达但本用户用不了）；400/5xx 不喂熔断器
                    val type = if (resp.code == 451 || resp.code == 403) FailureType.REGION_BLOCKED else null
                    ShardFetch(null, type, resp.code)
                } else {
                    val quotes = parseBinanceTickerArray(body)
                    if (quotes.isEmpty()) {
                        AppLogger.w(
                            "加密货币批量行情解析为空（${shard.size} 个交易对），本片回落逐标的",
                            TAG
                        )
                        ShardFetch(null, null)
                    } else {
                        ShardFetch(quotes, null)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("加密货币批量行情请求失败（${shard.size} 个交易对）", e, TAG)
            ShardFetch(null, FailureType.TRANSIENT)
        }
    }

    /**
     * 解析币安批量行情的 JSON 数组（`[{symbol,lastPrice,...}, ...]`）。
     *
     * 与单只路径的 [parseBinanceCrypto]（正则抠字段）不同：批量响应是数组、字段值多为
     * 字符串数字（`"67000.00000000"`），用 org.json 逐对象解析更稳，也避免正则跨对象串味。
     */
    private fun parseBinanceTickerArray(body: String): Map<String, CryptoQuote> {
        val out = LinkedHashMap<String, CryptoQuote>()
        return try {
            val arr = JSONArray(body)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val symbol = obj.optString("symbol", "").uppercase()
                if (symbol.isEmpty()) continue
                val price = obj.optDouble("lastPrice", Double.NaN)
                if (price.isNaN()) continue
                out[symbol] = CryptoQuote(
                    price = price,
                    change = obj.optDoubleOrNull("priceChange"),
                    changePercent = obj.optDoubleOrNull("priceChangePercent"),
                    high = obj.optDoubleOrNull("highPrice"),
                    low = obj.optDoubleOrNull("lowPrice"),
                    volume = obj.optDoubleOrNull("volume")?.toLong()
                )
            }
            out
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("解析加密货币批量行情失败", e, TAG)
            emptyMap()
        }
    }

    /** 取可空 double：字段缺失 / 显式 null / 非数字时返回 null（与单只路径的 null 语义对齐） */
    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (has(key) && !isNull(key)) optDouble(key, Double.NaN).takeIf { !it.isNaN() } else null

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
        val codeNorm = code.uppercase().trim()
        // 是否币安理财/锁仓（LD 前缀）产品：这类持仓的底层交易对若在币安现货根本没有
        // 可交易对（如 1MBBDOGE / SHIB2 这类币安理财持仓但无 USDT 现货盘），应归为
        // 「不可交易」（NonTradableAssetException，不计同步失败），而非网络/符号错误。
        val isLdProduct = BinanceAPI.isEarnProduct(codeNorm)

        // 稳定币识别统一走 BinanceAPI.isLikelyStablecoin（白名单 + 命名兜底），
        // 与导入路径口径一致——白名单外、命名疑似稳定币的币（如 NEWUSD/USDG）
        // 也能按 1.0 美元计，不再退化成裸码查价 → 查不到 → 市值 0。
        if (BinanceAPI.isLikelyStablecoin(codeNorm)) {
            AppLogger.d("稳定币 $code 按 1.0 美元计，跳过查价", TAG)
            return@withContext Result.success(
                CryptoData(code = codeNorm, name = codeNorm, price = 1.0, change = null, changePercent = null)
            )
        }

        // 已知在币安现货无交易对的 code（本轮同步逐只回落时已向币安确认过 400 无效符号）：
        // 直接判不可交易、不再发网络请求，也不进批量预取（否则每轮整片 400 吃掉批量收益）。
        if (codeNorm in nonTradableCodes) {
            return@withContext Result.failure(
                NonTradableAssetException("$code 在币安现货无交易对（已知不可交易）")
            )
        }

        // 候选交易对：先按既有映射算出（多数情况正确），再补一个「裸码 + USDT」兜底，
        // 解决 WBETH 这类 ticker 以报价币名结尾、被误判成已是交易对导致永远 400 的问题
        // （详见 [BinanceAPI.cryptoSymbolCandidates]）。LD 理财/锁仓走底层 1:1 计价，
        // 但底层本身也可能是 WBETH 这种「以报价币结尾」的码——所以锁仓分支同样套用
        // cryptoSymbolCandidates（而非裸 listOf），让 WBETH 类底层也能落到 WBETHUSDT 兜底。
        val candidates: List<String> = if (BinanceAPI.isEarnProduct(code)) {
            val underlying = BinanceAPI.earnUnderlyingPair(code)
            if (underlying == null) {
                // 底层仍是稳定币壳（如 LDUSDT）：没有现货交易对，但锚定 1 美元，
                // 与现货稳定币同口径按 1.0 计，而不是当成「无行情」跳过——
                // 否则存量 LDUSDT 的 currentPrice 永远留着导入前的 0，市值恒为 0
                // （用户看到的「USDT 价格默认为 0」正是这个）。earnUnderlyingPair
                // 返回 null 当且仅当剥掉 LD 后底层是稳定币，所以这里判一次集合即可。
                val stripped = codeNorm.removePrefix("LD")
                if (BinanceAPI.isLikelyStablecoin(stripped)) {
                    AppLogger.d("理财/锁仓稳定币 $code 按 1.0 美元计", TAG)
                    return@withContext Result.success(
                        CryptoData(code = codeNorm, name = codeNorm, price = 1.0, change = null, changePercent = null)
                    )
                }
                AppLogger.d("理财/锁仓产品 $code 无底层交易对，跳过查价", TAG)
                nonTradableCodes.add(codeNorm)
                return@withContext Result.failure(
                    NonTradableAssetException("$code 是币安理财/锁仓产品，没有现货行情")
                )
            }
            AppLogger.d("理财/锁仓产品 $code 按底层 $underlying 1:1 计价", TAG)
            BinanceAPI.cryptoSymbolCandidates(underlying)
        } else {
            BinanceAPI.cryptoSymbolCandidates(code)
        }

        if (candidates.isEmpty()) {
            AppLogger.d("无可用交易对（无法归一化的符号）：$code，跳过查价", TAG)
            nonTradableCodes.add(codeNorm)
            return@withContext Result.failure(NonTradableAssetException("$code 没有可用的现货行情"))
        }

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

        // 顺序尝试候选交易对：正常币首个候选即命中（零额外请求）；WBETH 这类首候选 400 时
        // 才落到「裸码 + USDT」兜底。仅 400（非法符号）重试，451/403/429/5xx 与网络异常不重试。
        var lastFailure: Throwable? = null
        for (rawPair in candidates) {
            val pricedPair = BinanceAPI.toUsdtPair(rawPair)
            if (pricedPair == null) {
                // 兜底裸码本身是个稳定币（理论上走不到，上面已拦截），按 1.0 计
                AppLogger.d("稳定币 $rawPair 按 1.0 美元计", TAG)
                return@withContext Result.success(
                    CryptoData(code = codeNorm, name = codeNorm, price = 1.0, change = null, changePercent = null)
                )
            }

            // 公开行情走 data-api.binance.vision（不做地区封锁）；api.binance.com 在部分地区回 451，换 IP 无效。
            val url = "${APIEndpoints.Binance.MARKET_DATA_BASE_URL}/ticker/24hr?symbol=$pricedPair"

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

                    AppLogger.api("GET /ticker/24hr (symbol=$rawPair)", it.message, duration, TAG)

                    val body = it.body?.string() ?: return@withContext Result.failure(Exception("币安返回了空响应"))

                    if (!it.isSuccessful) {
                        // 451/403 = 地区封锁 / 访问被拒：主机可达但本用户用不了，等同「主机不可用」，
                        // 喂给熔断器使其 OPEN 短路后续请求，避免每个币都白打一次 451、还各计一次
                        // 同步失败（旧代码在 isSuccessful 检查前就调 onSuccess，把 451 探活当成功，
                        // 熔断一解除又被 451、循环空转——地区封锁用户每轮同步都被 451 刷屏）。
                        if (it.code == 451 || it.code == 403) {
                            // 地区封锁（451/403）喂 REGION_BLOCKED：据此熔断后即粘死，永久拒探测，
                            // 不再每轮空探一次 451（灭掉旧实现里「每 5 分钟空探、无限循环」的问题）。
                            BinanceHostGate.onNetworkFailure(System.currentTimeMillis(), FailureType.REGION_BLOCKED)
                            return@withContext Result.failure(BinanceAccountAPI.binanceError(body, it.code))
                        }
                        // 400 非法符号：候选还有下一个就试，否则判失败（主机其实可达，
                        // 按旧口径不喂熔断，价格本轮失败，详见上面 451 分支说明）。
                        if (it.code == 400 && rawPair != candidates.last()) {
                            AppLogger.d("币安 400 非法符号 $pricedPair，尝试下一个候选交易对", TAG)
                            lastFailure = BinanceAccountAPI.binanceError(body, it.code)
                            return@use
                        }
                        val err = BinanceAccountAPI.binanceError(body, it.code)
                        // LD 理财/锁仓产品的底层交易对若返回「非法符号」(-1121)，说明底层在币安
                        // 现货根本没有可交易对（如 1MBBDOGE / SHIB2 这类币安理财持仓但无 USDT
                        // 现货盘）：不是同步故障、也不是用户填错符号，而是「底层无行情」。
                        // 归为不可交易（NonTradableAssetException）——同步侧不计入失败数，
                        // 否则每轮同步都会把这两条真实持有的理财持仓当成"失败 2"骚扰用户。
                        if (isLdProduct && it.code == 400 && isInvalidSymbolError(err)) {
                            AppLogger.i(
                                "理财/锁仓产品 $code 的底层交易对($rawPair)在币安不存在（无效符号），按不可交易处理",
                                TAG
                            )
                            nonTradableCodes.add(codeNorm)
                            return@withContext Result.failure(
                                NonTradableAssetException("$code 的底层资产在币安现货无交易对")
                            )
                        }
                        return@withContext Result.failure(err)
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
                // 网络异常对所有候选都一样，不再试后续候选。
                // 「我们自己放弃」不算主机不可达的证据，绝不喂熔断器。
                // 典型是切后台（MainActivity onStop 取消同步）：协程取消了，但阻塞中的
                // execute() 不会被打断，仍要跑到 callTimeout 才以 InterruptedIOException 收尾。
                // 这些失败若计入，一次切后台就能凑满阈值、把整个主机熔断 5 分钟——
                // 真机日志实锤：20:06:59 切后台 → 20:07:05 六个 35s 超时 → 触发熔断。
                val gaveUp = !currentCoroutineContext().isActive || isCancellationArtifact(e)
                if (!gaveUp && (e is ConnectException || e is SocketTimeoutException || e is UnknownHostException || e is IOException)) {
                    // 偶发网络级失败（连不上 / 超时 / 域名解析失败 / IO 错误）喂 TRANSIENT：
                    // 按标准三态累计 / 冷却 / 半开探测自愈，不粘死（地区封锁才粘死，见 451 分支）。
                    BinanceHostGate.onNetworkFailure(System.currentTimeMillis(), FailureType.TRANSIENT)
                }
                AppLogger.e("获取加密货币价格失败: $code", e, TAG)
                // 连接类失败（连不上/超时/域名解析不了）换成一句人话：OkHttp 原始 message
                // 形如 "failed to connect to data-api.binance.vision/2001:… after 10000ms"，
                // 既看不懂又把设备 IP/端口抖给用户。币安国际站在部分网络/地区连不上是常态
                // （公开行情已改走 data-api.binance.vision 绕开地区封锁，这里兜的是镜像也不可达的极端情况）。
                val userFacing = if (e is ConnectException || e is SocketTimeoutException || e is UnknownHostException) {
                    Exception("连不上币安行情服务器，可能被网络或地区限制，请检查网络或代理后重试")
                } else {
                    e
                }
                return@withContext Result.failure(userFacing)
            }
        }

        // 所有候选都因 400 失败（如用户填错的符号 SHIB2USDT → SHIB2USDTUSDT）：真实失败，
        // 用户必须自己改对 symbol，App 无法猜出正确交易对。
        val finalError = lastFailure ?: Exception("所有候选交易对均查询失败: $code")
        // LD 理财/锁仓产品的底层交易对若全部「非法符号」(-1121)，底层在币安现货无交易对，
        // 归为不可交易（不计同步失败），与上面单候选分支同口径。
        if (isLdProduct && lastFailure != null && isInvalidSymbolError(finalError)) {
            AppLogger.i("理财/锁仓产品 $code 的底层交易对均无效（无现货盘），按不可交易处理", TAG)
            nonTradableCodes.add(codeNorm)
            Result.failure(NonTradableAssetException("$code 的底层资产在币安现货无交易对"))
        } else {
            Result.failure(finalError)
        }
    }
    
    private fun parseBinanceCrypto(code: String, response: String): CryptoData? {
        return try {
            val json = JSONObject(response)
            // 用 optString + toDoubleOrNull：字段缺失→null（与原正则解析语义一致），
            // 且能正确处理带引号/千分位的数字（原正则 [^",\}] 会把 "1,234.56" 截断成 "1"）。
            val price = json.optString("lastPrice", "").toDoubleOrNull() ?: return null
            val change = json.optString("priceChange", "").toDoubleOrNull()
            val changePercent = json.optString("priceChangePercent", "").toDoubleOrNull()
            val high = json.optString("highPrice", "").toDoubleOrNull()
            val low = json.optString("lowPrice", "").toDoubleOrNull()
            val volume = json.optString("volume", "").toDoubleOrNull()?.toLong()
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

    /**
     * 测试专用：只清本门面的 60 秒缓存，不碰 FundAPI / CacheManager（它们在 JVM 单测里需要 Context）。
     *
     * 预取会把结果双写进 [priceCache]，用例之间不清就会命中上一条用例的价，
     * 换了假 client 也拿不到「这次真的发了请求」的证据，出现假绿。
     */
    internal fun clearPriceCacheForTesting() {
        priceCache.clear()
        pendingRequests.clear()
    }

    /** 测试隔离用：清空 [nonTradableCodes]（object 单例跨用例共享，必须复位） */
    internal fun clearNonTradableForTesting() {
        nonTradableCodes.clear()
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

/**
 * 判断异常是不是「我们自己放弃导致的收尾产物」，而不是「主机不可达」的证据。
 *
 * 必须排除的两类（否则一次切后台就能凑满熔断阈值，把整个币安主机熔断 5 分钟）：
 * - [CancellationException]：协程被取消（调用方最上面已重抛，这里兜底）；
 * - OkHttp 的 `IOException("Canceled")`：[okhttp3.Call] 被 cancel 后以这个形态收尾。
 *
 * 刻意**不**排除带 timeout 语义的 [java.io.InterruptedIOException]（message="timeout"）：
 * 那是真的等到超时，本身是网络状况的证据；它是否该计入由调用方另用
 * `currentCoroutineContext().isActive` 判定（放弃之后的超时不算数）。
 *
 * 放文件级是为了能直接 JVM 单测（与 isRealDividendPage / isHostUnreachable 同惯例）。
 */
internal fun isCancellationArtifact(e: Throwable): Boolean {
    if (e is CancellationException) return true
    // OkHttp 取消 call 后抛 IOException("Canceled")
    if (e is IOException && e.message?.equals("canceled", ignoreCase = true) == true) return true
    return false
}

/**
 * 判断异常是不是币安「非法符号」（-1121 Invalid symbol）。
 *
 * [BinanceAccountAPI.binanceError] 对这类错误统一格式化为
 * "币安拒绝请求（-1121）：Invalid symbol."。LD 理财/锁仓产品的底层交易对
 * （如 1MBBDOGE → 1MBBDOGEUSDT）在币安现货根本没有可交易对时就会回这个错——
 * 它不是同步故障、也不是用户填错符号，而是「底层无行情」，应归为
 * [NonTradableAssetException]（不计同步失败），见 [getCryptoPrice]。
 */
internal fun isInvalidSymbolError(e: Throwable): Boolean {
    val msg = e.message ?: return false
    return msg.contains("-1121") || msg.contains("Invalid symbol", ignoreCase = true)
}
