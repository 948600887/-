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
     * 这些持仓是真实资产、要继续显示。`LDXXXUSDT` 这种交易对币安确实没有，
     * **不能直接拿它发请求**；但 LD 与底层资产 1:1 可赎回，估值应走
     * [earnUnderlyingPair] 剥掉 LD 后用底层交易对查价——一律当成「没有行情」
     * 会让这些持仓的市值恒为 0，总资产少算整整一块。只有剥不掉底层的
     * （如稳定币壳 `LDUSDT`）才真正无行情可查，调用方按
     * [NonTradableAssetException] 处理，且**不计同步失败**。
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
     * @return true 表示这是理财/锁仓口径的持仓代码，没有 `LDXXXUSDT` 现货行情可查
     *   （估值请转 [earnUnderlyingPair] 走底层交易对）
     */
    internal fun isEarnProduct(symbol: String): Boolean {
        val s = symbol.uppercase().trim()
        return s.startsWith("LD") && s.length > 2 && s !in BINANCE_LD_EXCEPTIONS
    }

    /**
     * 理财/锁仓产品（LD 前缀）的**底层交易对**。
     *
     * LD 代币与底层资产 1:1 可赎回（LDBNB 就是 1 个 BNB、LDPOL 就是 1 个 POL），
     * 所以估值该用底层币的市价，而不是当成"没有行情"。
     * 这也是总资产不失真的前提：导入时 purchasePrice = 0，若 LD 一律跳过取价，
     * 这些持仓的 totalValue 恒为 0，总资产/净资产/快照的加密分块全部少算。
     *
     * @return 底层交易对（如 LDBNBUSDT → BNBUSDT）；剥掉 LD 后仍构不成交易对
     *   （稳定币、空串、非 LD 符号）返回 null，调用方按「无行情」处理
     */
    internal fun earnUnderlyingPair(symbol: String): String? {
        if (!isEarnProduct(symbol)) return null
        val underlying = symbol.trim().uppercase().removePrefix("LD")
        if (underlying.isEmpty()) return null
        return cryptoTradingPairFor(underlying)
    }

    /**
     * 把交易对归一化成「以 USDT 计价」，用于按美元估值。
     *
     * 已是 USDT 对（BTCUSDT）原样返回；裸基础资产（BTC）补 USDT；**非 USDT 报价的
     * 交易对（ADABTC / ETHBTC）剥掉报价币取基础资产再补 USDT（ADAUSDT / ETHUSDT）**——
     * App 始终按美元计价，非 USDT 对的 lastPrice 是「以报价币计」，直接当美元会把市值
     * 错算（见 P2：手动填 BTC 市场币对的用户，其价值被错当美元）。
     *
     * 只做**计价归一**，不负责「是否是可交易现货」（那是 [cryptoTradingPairFor] +
     * [isEarnProduct] 的职责）。输入已是与币安一致的交易对或裸资产，不会收到 LD 壳层代码。
     */
    internal fun toUsdtPair(pair: String): String {
        val s = pair.uppercase().trim()
        if (s.isEmpty()) return s
        if (s.endsWith("USDT")) return s
        val quote = BINANCE_QUOTE_ASSETS.firstOrNull { q -> s.length > q.length && s.endsWith(q) }
        return if (quote != null) "${s.removeSuffix(quote)}USDT" else "${s}USDT"
    }

    /**
     * 稳定币的**兜底**识别：白名单 [BINANCE_STABLECOINS] 之外，原始资产名以
     * `USD` / `EUR` 结尾的几乎都是锚定法币的稳定币（如 NEWUSD / USDG …）。
     *
     * ## 只做「按 1.0 美元估」的召回增强，不参与交易对判定
     *
     * 交易对判定仍以 [cryptoTradingPairFor] 为准——那里若把疑似稳定币也判成 null，
     * 会误伤行情同步的真实查价。所以这里只把结果喂给导入路径的估值/预填
     * （[com.example.stockdiary.data.importer.estimatedValueCny] 与
     *  [com.example.stockdiary.data.importer.referencePriceForImport]），
     * 让漏进白名单的新稳定币也能拿到 1.0 兜底，不再退化成裸码查价 → 查不到 → 市值 0。
     *
     * ## 误判代价很小
     *
     * 一个本该查价的币若被当成稳定币，最坏只是导入预填/预览市值按 1.0 美元计，
     * 而行情同步仍按真实交易对补价，不会永久错算；反过来（稳定币漏网→市值恒 0）
     * 危害更大，故优先召回。这正是 [BINANCE_STABLECOINS] 注释里写的「根治漂移方向」。
     *
     * @return true 表示该资产应按 1.0 美元估值（白名单命中 或 命名疑似稳定币）
     */
    internal fun isLikelyStablecoin(asset: String): Boolean {
        val s = asset.uppercase().trim()
        if (s in BINANCE_STABLECOINS) return true
        // 欧元稳定币命名不统一（EURT/EURC/EURS…），这里只做「USD/EUR 收尾」的粗召回，
        // 更精确的欧元币仍靠白名单补充；多收不致命（见上），少收也只退化到旧的查价路径。
        return s.endsWith("USD") || s.endsWith("EUR")
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
/**
 * 常见 USD 稳定币集合（锚定 1 美元，币安上没有 XXXUSDT 自我交易对，查价必然空，
 * 估值按 1.0 美元计）。
 *
 * ⚠️ 这是**手工维护**的名单，天然会随市场上新稳定币而漂移——PYUSD / USDE / USDG 等都曾漏过。
 * 漏网的稳定币不会拿到 1.0 兜底，而是退化成裸码去查价 → 查不到 → 市值显 0 / 价格待更新。
 * 名单只补「明确 USD 锚定」的主流币；欧元稳定币（EURT 等）**故意不收**，否则会被当 1 美元错算。
 * 若以后要根治漂移，应改为「查价失败且代码疑似稳定币时按 1.0 兜底」而非依赖这份名单。
 */
internal val BINANCE_STABLECOINS: Set<String> = setOf(
    "USDT", "USDC", "BUSD", "TUSD", "DAI", "FDUSD",
    "USDP", "USDD", "GUSD", "USDS", "SUSD",
    "PYUSD", "USDE", "USDB", "USDG", "USD1", "USDL", "FRAX"
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
