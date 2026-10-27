package com.example.stockdiary.network

import com.example.stockdiary.network.model.AssetPrice
import java.io.IOException
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [UnifiedPriceService.prewarmCryptoPrices] 加密货币批量预取的契约回归。
 *
 * ## 为什么钉这些
 *
 * 真机诊断（20261026_230142）：每轮同步为每个加密标的各发一次 `?symbol=XXXUSDT`，
 * 单轮约 **129 次**币安请求；稳态单请求仅 152ms，慢请求全是网络等待——瓶颈是
 * **请求数 × 并发度 6**。批量端点把 129 次压到 2~3 次，是这个头号耗时源最直接的提速点。
 *
 * 钉死四件事：
 * 1. N 个标的**只发 1 次**批量请求，稳定币 / LD 稳定币壳按 1.0 且**不进**请求；
 * 2. 原始 code 必须保留（LDBNB 不能被写成底层 BNBUSDT），否则资产代码被改写；
 * 3. 交易对数超一片上限时**分片**发送，每片都走 `symbols` 参数；
 * 4. **一次预取只记一次熔断成败**——若按片内标的数量记账，一次批量超时就把阈值（8）打满，
 *    整机误熔（真机已实锤过「一波并发超时凑满阈值 → 跳过 130 个」的误熔）。
 * 5. 批量失败时对应 code **不进 map**，回落逐标的 [UnifiedPriceService.getCryptoPrice] 仍能取到价。
 *
 * 全部走假 OkHttp [okhttp3.Interceptor]（不出网），与 UnifiedPriceServiceTest 同套路。
 * [BinanceHostGate] 是进程内 object，`@Before`/`@After` 复位保证不污染别的测试类。
 */
class UnifiedPriceServiceCryptoPrewarmTest {

    private val requestCount = AtomicInteger(0)
    private val lastUrl = AtomicReference<String>("")

    @Before
    fun setUp() {
        BinanceHostGate.resetForTesting()
        requestCount.set(0)
        lastUrl.set("")
    }

    @After
    fun tearDown() {
        // 与 @Before 对称：熔断状态不能漏给后续测试类
        BinanceHostGate.resetForTesting()
    }

    // ---------- 1. 请求数：N 个标的 1 次请求 ----------

    @Test
    fun `N个标的只发1次批量请求_稳定币与LD稳定币壳按1_0且不进请求`() = runBlocking {
        val client = recordingClient { req ->
            respond(200, tickers("BTCUSDT" to "67000", "ETHUSDT" to "3000", "BNBUSDT" to "600"), req)
        }

        val map = UnifiedPriceService.prewarmCryptoPrices(
            listOf("BTC", "ETH", "USDT", "LDUSDT", "LDBNB"),
            forceRefresh = true,
            client = client
        ).getOrNull()!!

        assertEquals("5 个标的（3 个需查价 + 2 个锚定 1.0）应只发 1 次批量请求", 1, requestCount.get())
        assertEquals(5, map.size)
        assertEquals(67000.0, priceOf(map, "BTC"), 1e-9)
        assertEquals(3000.0, priceOf(map, "ETH"), 1e-9)
        // LDBNB 按底层 BNB 1:1 计价
        assertEquals(600.0, priceOf(map, "LDBNB"), 1e-9)
        // 稳定币 + LD 稳定币壳锚定 1 美元
        assertEquals(1.0, priceOf(map, "USDT"), 1e-9)
        assertEquals(1.0, priceOf(map, "LDUSDT"), 1e-9)
        // 原始 code 必须保留：绝不能把资产代码改成底层交易对
        assertEquals("LDBNB", codeOf(map, "LDBNB"))
        assertEquals("BTC", codeOf(map, "BTC"))

        val decoded = URLDecoder.decode(lastUrl.get(), "UTF-8")
        assertTrue("批量请求必须带 symbols 参数: $decoded", decoded.contains("symbols="))
        assertTrue(
            "稳定币与 LD 稳定币壳不得进请求（只应有 3 个交易对）: $decoded",
            decoded.contains("""["BTCUSDT","ETHUSDT","BNBUSDT"]""")
        )
    }

    // ---------- 2. 分片 ----------

    @Test
    fun `超过单片上限的交易对分成多次请求_每片都走symbols参数`() = runBlocking {
        // 70 个交易对 > 单片上限 60 → 2 片（60 + 10）
        val codes = (1..70).map { "TOK$it" }
        val client = recordingClient { req -> respond(200, "[]", req) }

        UnifiedPriceService.prewarmCryptoPrices(codes, forceRefresh = true, client = client)

        assertEquals("70 个交易对应按单片上限分成 2 次请求", 2, requestCount.get())
        assertTrue(
            "每片都必须用 symbols 批量参数: ${URLDecoder.decode(lastUrl.get(), "UTF-8")}",
            URLDecoder.decode(lastUrl.get(), "UTF-8").contains("symbols=")
        )
    }

    // ---------- 3. 熔断记账：一次预取只记一次 ----------

    @Test
    fun `批量451只记一次失败_不按标的数量记账`() = runBlocking {
        // 关键回归：10 个标的若按标的记账会累计 10 次 ≥ 阈值 8 → 一次批量 451 就误熔整机；
        // 只记一次则仍是 CLOSED、计数 1（与单只 getCryptoPrice 的 451 口径完全一致）。
        val codes = (1..10).map { "TOK$it" }
        val client = recordingClient { req ->
            respond(451, """{"code":0,"msg":"Service unavailable from a restricted location."}""", req)
        }

        val map = UnifiedPriceService.prewarmCryptoPrices(codes, forceRefresh = true, client = client)
            .getOrNull()!!

        assertTrue("批量 451 时这些 code 应回落逐标的，不进 map", map.isEmpty())
        assertEquals(1, requestCount.get())
        assertEquals("10 个标的的一次批量 451 只能记 1 次失败", Phase.CLOSED, BinanceHostGate.phaseForTesting())
        assertEquals(1, BinanceHostGate.consecutiveFailuresForTesting())
    }

    @Test
    fun `连续批量451达阈值后粘死_冷却过期也不再探测`() = runBlocking {
        // 451 是地区封锁：达阈值熔断后打粘性标记，此后**永久**拒探测，
        // 灭掉旧实现「每 5 分钟空探一次、无限循环」的问题。
        val codes = (1..3).map { "TOK$it" }
        val client = recordingClient { req ->
            respond(451, """{"code":0,"msg":"Service unavailable from a restricted location."}""", req)
        }

        repeat(BinanceHostGate.FAIL_THRESHOLD) {
            UnifiedPriceService.prewarmCryptoPrices(codes, forceRefresh = true, client = client)
        }

        assertEquals(Phase.OPEN, BinanceHostGate.phaseForTesting())
        assertTrue("451 地区封锁达阈值熔断后必须打粘性标记", BinanceHostGate.regionBlockedForTesting())
        assertEquals("一轮批量只记一次，累计 8 轮才熔断一次", 1, BinanceHostGate.blockCountForTesting())
        val longAfter = System.currentTimeMillis() + BinanceHostGate.COOLDOWN_MS * 100
        assertFalse(
            "地区封锁粘死后，冷却过期也不该再对币安发探测请求",
            BinanceHostGate.tryAcquireProbe(longAfter)
        )
    }

    @Test
    fun `批量网络异常只记一次TRANSIENT_不按标的数量打满阈值`() = runBlocking {
        // 关键回归：10 个标的若按标的记账会累计 10 次 ≥ 阈值 8 → 一次批量超时就误熔整机；
        // 只记一次则仍是 CLOSED、计数 1（这正是「一次批量 = 一次主机探测」的钉死点）。
        val codes = (1..10).map { "TOK$it" }
        val client = recordingClient { _ -> throw IOException("模拟连接失败") }

        val map = UnifiedPriceService.prewarmCryptoPrices(codes, forceRefresh = true, client = client)
            .getOrNull()!!

        assertTrue("批量网络异常时这些 code 应回落逐标的，不进 map", map.isEmpty())
        assertEquals(1, requestCount.get())
        assertEquals("10 个标的的一次批量超时不能打满阈值 8", Phase.CLOSED, BinanceHostGate.phaseForTesting())
        assertEquals("一次预取只能记 1 次网络失败", 1, BinanceHostGate.consecutiveFailuresForTesting())
    }

    // ---------- 4. 失败回落 ----------

    @Test
    fun `批量500时该批不进map_且逐标的getCryptoPrice仍能取到价`() = runBlocking {
        val client = recordingClient { req ->
            if (req.url.toString().contains("symbols=")) {
                // 批量端点挂了
                respond(500, "", req)
            } else {
                // 单只端点正常
                respond(
                    200,
                    """{"symbol":"BTCUSDT","lastPrice":"67000.0","priceChange":"1.0",""" +
                        """"priceChangePercent":"2.0","highPrice":"68000","lowPrice":"66000","volume":"100.5"}""",
                    req
                )
            }
        }

        val map = UnifiedPriceService.prewarmCryptoPrices(
            listOf("BTC"),
            forceRefresh = true,
            client = client
        ).getOrNull()!!

        assertTrue("批量 500 时不应把 BTC 塞进 map（让它回落逐标的）", map.isEmpty())

        // 回落路径必须完好：批量失败不代表取价失败
        val single = UnifiedPriceService.getCryptoPrice("BTC", client = client)
        assertTrue("批量失败后逐标的 getCryptoPrice 必须仍能取到价", single.isSuccess)
        assertEquals(67000.0, single.getOrNull()!!.price, 1e-9)
    }

    // ---------- 5. 整片 400：切小重试一层 ----------

    @Test
    fun `整片400时切成子片重试_合法标的仍从子片拿到价`() = runBlocking {
        // 10 个合法 + 1 个非法（放最后，使其单独落在一个子片里）。
        // 币安对「含任一非法 symbol」的整批直接 400：若不重试，10 个合法标的会被这一个
        // 坏码拖累、全部回落逐标的；切小重试后它们照常拿到价。
        val valid = (1..10).map { "TOK$it" }
        val invalidCode = "LDSHIB2"            // 库里存的 symbol（理财壳 + 填错的码）
        val invalidPair = "SHIB2USDT"          // 归一化后实际要查的交易对
        val client = recordingClient { req ->
            val asked = symbolsOf(req.url.toString())
            if (invalidPair in asked) {
                respond(400, """{"code":-1121,"msg":"Invalid symbol."}""", req)
            } else {
                respond(200, tickers(*asked.map { it to "100" }.toTypedArray()), req)
            }
        }

        val map = UnifiedPriceService.prewarmCryptoPrices(
            valid + invalidCode,
            forceRefresh = true,
            client = client
        ).getOrNull()!!

        // 10 个合法标的全部从子片拿到价（不再被整片拖累）
        assertEquals(10, map.size)
        valid.forEach { assertEquals("$it 应拿到价", 100.0, priceOf(map, it), 1e-9) }
        assertTrue("非法 symbol 不应进 map（回落逐标的）", invalidCode !in map)
        // 1 次整片 + 2 次子片（10 + 1）
        assertEquals("整片 400 应切成 2 个子片重试：共 3 次请求", 3, requestCount.get())
    }

    @Test
    fun `重试只切一层不递归_请求次数有硬上限`() = runBlocking {
        // 60 个**全部非法**的 symbol：整片 400 → 切 6 个子片 → 子片全部 400 后直接回落。
        // 若哪天被改成递归二分，请求数会炸到几十上百；这里钉死上限 = 1 + ⌈60/10⌉ = 7。
        val codes = (1..60).map { "BAD$it" }
        val client = recordingClient { req -> respond(400, """{"code":-1121,"msg":"Invalid symbol."}""", req) }

        val map = UnifiedPriceService.prewarmCryptoPrices(codes, forceRefresh = true, client = client)
            .getOrNull()!!

        assertTrue("全部非法时不应有任何标的进 map", map.isEmpty())
        // 常量声明在 object UnifiedPriceService 内部，跨文件引用必须带对象限定名
        val retrySize = UnifiedPriceService.CRYPTO_BATCH_RETRY_SYMBOLS
        val upperBound = 1 + (codes.size + retrySize - 1) / retrySize
        val actual = requestCount.get()
        assertEquals("请求数应为 1 次整片 + 6 次子片", upperBound, actual)
        val withinBudget: Boolean = actual <= upperBound
        assertTrue(
            "请求数必须收敛在「1 + 子片数」以内，不得递归爆炸：实际 $actual > $upperBound",
            withinBudget
        )
    }

    /** 从批量请求 URL 里解出本次请求的 symbols 列表 */
    private fun symbolsOf(url: String): List<String> {
        val decoded = URLDecoder.decode(url, "UTF-8")
        val json = decoded.substringAfter("symbols=", "").substringBefore("&")
        return Regex("\"([A-Za-z0-9]+)\"").findAll(json).map { it.groupValues[1] }.toList()
    }

    // ---------- helpers ----------

    /** 记录请求次数与最后一次 URL 的假客户端 */
    private fun recordingClient(handle: (Request) -> Response): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            requestCount.incrementAndGet()
            lastUrl.set(chain.request().url.toString())
            handle(chain.request())
        }.build()

    private fun respond(code: Int, body: String, request: Request): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "err")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()

    /** 币安批量行情响应体：JSON 数组，字段值是字符串数字（与真实接口一致） */
    private fun tickers(vararg symbolPrice: Pair<String, String>): String =
        symbolPrice.joinToString(",", prefix = "[", postfix = "]") { (symbol, price) ->
            """{"symbol":"$symbol","lastPrice":"$price","priceChange":"1.5",""" +
                """"priceChangePercent":"2.5","highPrice":"$price","lowPrice":"$price","volume":"1234.5"}"""
        }

    private fun priceOf(map: Map<String, AssetPrice>, code: String): Double =
        (map[code] as AssetPrice.Crypto).data.price

    private fun codeOf(map: Map<String, AssetPrice>, code: String): String =
        (map[code] as AssetPrice.Crypto).data.code
}
