package com.example.stockdiary.network

import com.example.stockdiary.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import kotlinx.coroutines.CancellationException

object BinanceAPI {
    private const val BASE_URL = "https://api.binance.com/api/v3"
    private const val PRICE_URL = "$BASE_URL/ticker/price"
    private const val KLINE_URL = "$BASE_URL/klines"
    
    private val client = NetworkClient.binanceClient
    
    suspend fun getPrice(symbol: String): Double? {
        return withContext(Dispatchers.IO) {
            try {
                val tradingPair = formatSymbol(symbol)
                val url = "$PRICE_URL?symbol=$tradingPair"
                AppLogger.apiUrl("GET", url, "BinanceAPI")
                
                val request = Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", "Mozilla/5.0")
                    .build()
                
                val response = client.newCall(request).execute()
                response.use {
                    if (!it.isSuccessful) {
                        AppLogger.e("API响应错误: ${it.code}", tag = "BinanceAPI")
                        return@withContext null
                    }

                    val body = it.body?.string() ?: return@withContext null
                    AppLogger.d("响应长度: ${body.length}", "BinanceAPI")

                    val json = JSONObject(body)
                    return@withContext json.getString("price").toDoubleOrNull()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("获取价格失败: ${e.message}", e, "BinanceAPI")
                null
            }
        }
    }
    
    suspend fun get24hrStats(symbol: String): Binance24hrStats? {
        return withContext(Dispatchers.IO) {
            try {
                val tradingPair = formatSymbol(symbol)
                val url = "$BASE_URL/ticker/24hr?symbol=$tradingPair"
                
                val request = Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", "Mozilla/5.0")
                    .build()
                
                val response = client.newCall(request).execute()
                response.use {
                    if (!it.isSuccessful) return@withContext null

                    val body = it.body?.string() ?: return@withContext null
                    return@withContext parse24hrStats(body)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("获取24小时统计失败: ${e.message}", e, "BinanceAPI")
                null
            }
        }
    }
    
    private fun parse24hrStats(json: String): Binance24hrStats? {
        return try {
            val obj = JSONObject(json)
            Binance24hrStats(
                symbol = obj.getString("symbol"),
                lastPrice = obj.getString("lastPrice").toDoubleOrNull() ?: 0.0,
                priceChange = obj.getString("priceChange").toDoubleOrNull() ?: 0.0,
                priceChangePercent = obj.getString("priceChangePercent").toDoubleOrNull() ?: 0.0,
                highPrice = obj.getString("highPrice").toDoubleOrNull() ?: 0.0,
                lowPrice = obj.getString("lowPrice").toDoubleOrNull() ?: 0.0,
                volume = obj.getString("volume").toDoubleOrNull() ?: 0.0
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
    
    internal fun formatSymbol(symbol: String): String {
        val cleanSymbol = symbol.uppercase().replace(" ", "").replace("-", "")

        return when {
            cleanSymbol.endsWith("USDT") -> cleanSymbol
            cleanSymbol.endsWith("BTC") -> cleanSymbol
            cleanSymbol.endsWith("ETH") -> cleanSymbol
            cleanSymbol.endsWith("CNY") -> {
                val base = cleanSymbol.removeSuffix("CNY")
                "${base}USDT"
            }
            cleanSymbol.endsWith("USD") -> {
                val base = cleanSymbol.removeSuffix("USD")
                "${base}USDT"
            }
            else -> "${cleanSymbol}USDT"
        }
    }

    /**
     * 把库里存的加密货币代码映射成币安的**交易对**；稳定币返回 null（无需查价，按 1 美元计）。
     *
     * ## 为什么不能直接用 [formatSymbol]
     *
     * [formatSymbol] 面向「用户输入的交易对/简称」，把 BTC/ETH 当**报价币**，于是
     * `formatSymbol("BTC")` 命中 `endsWith("BTC")` 分支、返回 "BTC" 本身。而账户接口
     * 给的是裸基础资产，直接套它会拼出 `?symbol=BTC` —— 币安没有这个交易对，返回 400。
     *
     * 行情同步路径（[com.example.stockdiary.network.UnifiedPriceService] 取币价）
     * 的输入是**库里存的 symbol**，两种形态都有：
     * - 币安导入存的：`BTCUSDT`（`baseAssetToSymbol` 转过）
     * - 手动添加的：用户原样输入的 `BTC`
     *
     * 所以这里必须同时认这两种形态，且**必须先判「自身就是报价币」再判「以报价币结尾」**——
     * 顺序反了 BTC 会被误判成"已是交易对"，正是上面那个坑。
     *
     * @return 交易对（如 BTCUSDT）；稳定币返回 null，调用方按 1.0 美元计
     */
    internal fun cryptoTradingPairFor(symbol: String): String? {
        val s = symbol.uppercase().trim()
        if (s.isEmpty()) return null
        // 稳定币锚定 1 美元，币安上没有 XXXUSDT 自我交易对，查价必然空
        if (s in BINANCE_STABLECOINS) return null
        // 自身就是报价币（BTC / ETH / USDT…）→ 是裸基础资产，补 USDT。
        // 这一条必须排在「以报价币结尾」之前，否则 BTC 会被当成已是交易对
        if (s in BINANCE_QUOTE_ASSETS) return "${s}USDT"
        // 已是交易对形态（ETHBTC / ADAUSDT / 1INCHUSDT…）→ 原样用
        val hasQuoteSuffix = BINANCE_QUOTE_ASSETS.any { s.length > it.length && s.endsWith(it) }
        return if (hasQuoteSuffix) s else "${s}USDT"
    }

    /**
     * 判断 symbol 是不是币安**理财/锁仓（Simple Earn / Locked Staking）产品**。
     *
     * 币安给这类持仓的代码是 `LD` + 底层资产名（`LDBNB` / `LDPOL` / `LDLTC` / `LDPAXG`…），
     * 账户接口把它和现货一起返回，于是它们会被当成普通持仓写进库里。
     * 但 `LD` 只是个持仓壳层标记，**不是可交易的现货行情代码**：币安没有 `LDLTCUSDT`
     * 这种交易对，拿它去问 `/ticker/24hr` 只会拿到 400，或者在这个 API 全天候
     * 连不上的网络里，白白挂到超时。
     *
     * 实测一轮同步里 37 条「获取加密货币价格失败」有 32 条是这类 `LD*` 持仓，
     * 其中 `LDLTCUSDT` 一次挂了 3478 秒，把 AssetSyncManager 的并发信号量整个堵死。
     *
     * 这些持仓是真实资产、要继续显示，只是**本来就不该为它发取价请求**——
     * 所以调用方收到的是 [NonTradableAssetException]，且**不计同步失败**。
     *
     * ## 为什么要排除 LDO
     *
     * Lido DAO 的现货代号恰好也是 `LD` 开头，它有真实的 `LDOUSDT` 交易对。
     * 纯前缀判断会把它误伤成「理财壳」而永远不取价。见 [BINANCE_LD_EXCEPTIONS]。
     *
     * ## 为什么这条知识放在这里
     *
     * 与 [BINANCE_STABLECOINS] 同一个理由：币安的领域知识应当只有一处定义。
     * 两边各写一份正是历史上出过 bug 的原因。
     *
     * @return true 表示这是理财/锁仓口径的持仓代码，没有现货行情可查
     */
    internal fun isEarnProduct(symbol: String): Boolean {
        val s = symbol.uppercase().trim()
        return s.startsWith("LD") && s.length > 2 && s !in BINANCE_LD_EXCEPTIONS
    }
    
    fun getSupportedSymbols(): List<String> {
        return listOf(
            "BTCUSDT", "ETHUSDT", "BNBUSDT",
            "ADAUSDT", "DOGEUSDT", "XRPUSDT",
            "DOTUSDT", "UNIUSDT", "LTCUSDT",
            "SOLUSDT", "MATICUSDT", "LINKUSDT"
        )
    }
}

data class Binance24hrStats(
    val symbol: String,
    val lastPrice: Double,
    val priceChange: Double,
    val priceChangePercent: Double,
    val highPrice: Double,
    val lowPrice: Double,
    val volume: Double
)

/**
 * 常见 USD 稳定币集合。
 *
 * 这些币锚定 1 美元，且币安上没有 `XXXUSDT` 自我交易对（USDT 本身就是报价币），
 * 查价必然查空。估算市值时按 1.0 美元计，省掉一次注定取不到价的请求。
 *
 * 放在这里（network 包顶层）而不是 data/importer：币安的领域知识应当只有一处定义，
 * 两边各写一份正是历史上出过 bug 的原因（见 [BinanceAPI.cryptoTradingPairFor]）。
 * 顶层声明（而非 object 成员）是为了让 data 层能 `import` 到它。
 */
internal val BINANCE_STABLECOINS: Set<String> = setOf(
    "USDT", "USDC", "BUSD", "TUSD", "DAI", "FDUSD",
    "USDP", "USDD", "GUSD", "USDS", "SUSD"
)

/**
 * 以 `LD` 开头、但**不是**理财/锁仓产品的例外名单。
 *
 * Lido DAO 的现货代号就是 `LDO`，币安有真实的 `LDOUSDT` 交易对。如果只看前缀，
 * 它会被 [BinanceAPI.isEarnProduct] 误判成理财壳而永远不取价——真现货被当成
 * 不可交易资产，比反过来严重得多。
 *
 * 这里列出裸代码和常见的带报价币后缀形态：库里存的 symbol 两种形态都有
 * （导入存 `LDOUSDT`，手动添加可能只存 `LDO`）。
 *
 * 与 [BINANCE_STABLECOINS] 同样放在 network 包顶层：币安的领域知识只有一处定义。
 */
internal val BINANCE_LD_EXCEPTIONS: Set<String> = setOf(
    "LDO", "LDOUSDT", "LDOBTC", "LDOETH"
)

/** 币安常见报价币（quote asset）。判断「symbol 是裸资产还是已是交易对」时用 */
private val BINANCE_QUOTE_ASSETS = setOf(
    "USDT", "USD", "USDC", "BUSD", "DAI", "FDUSD", "TUSD", "USDP",
    "BTC", "ETH", "BNB", "CNY"
)
