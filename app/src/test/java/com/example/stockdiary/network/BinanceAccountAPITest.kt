package com.example.stockdiary.network

import com.example.stockdiary.data.importer.baseAssetToSymbol
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

/**
 * 币安签名与 symbol 映射的回归。
 *
 * 重点是 [BinanceAccountAPI.sign]：它是整条账户链路里唯一「错一位全盘皆错」
 * 又不依赖网络的部分，所以用币安官方文档的已知向量直接验 HMAC 实现，
 * 不必连网、不必伪造 OkHttp。
 */
class BinanceAccountAPITest {

    /**
     * 币安官方文档的 HMAC-SHA256 示例向量（公开的演示密钥，非真实机密）。
     *
     * 用它是为了对齐币安的真实签名口径：编码（UTF-8）、算法（HmacSHA256）、
     * 输出（小写 hex）。任何一处对不上，签名就会被币安拒。
     */
    private val docSecret = "NhqPtmdSJYdKjVHjA7PZj4Mge3R5YNiP1e3UZjInClVN65XAbvqqM6A7H5fATj0j"
    private val docQuery =
        "symbol=LTCBTC&side=BUY&type=LIMIT&timeInForce=GTC&quantity=1&price=0.1&recvWindow=5000&timestamp=1499827319559"
    private val docSignature =
        "c8db56825ae71d6d79447849e617115f4a920fa2acdcab2b053c4b2838bd6b71"

    @Test
    fun 签名匹配币安官方文档向量() {
        assertEquals(docSignature, BinanceAccountAPI.sign(docSecret, docQuery))
    }

    @Test
    fun 同一输入总是同一输出() {
        val a = BinanceAccountAPI.sign(docSecret, docQuery)
        val b = BinanceAccountAPI.sign(docSecret, docQuery)
        assertEquals(a, b)
    }

    @Test
    fun 输出是64位小写十六进制() {
        val sig = BinanceAccountAPI.sign(docSecret, docQuery)
        // 256 bit / 4 = 64 个 hex 字符
        assertEquals(64, sig.length)
        assertTrue("签名只能含 0-9a-f", sig.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun 换secret得到不同签名() {
        val other = BinanceAccountAPI.sign("另一个完全不同的secret", docQuery)
        assertNotEquals(BinanceAccountAPI.sign(docSecret, docQuery), other)
    }

    @Test
    fun query差一位签名就变() {
        val base = BinanceAccountAPI.sign(docSecret, "timestamp=1499827319559")
        val tweaked = BinanceAccountAPI.sign(docSecret, "timestamp=1499827319558")
        assertNotEquals(base, tweaked)
    }

    // ---------- symbol 映射：基础资产 → 交易对 ----------

    @Test
    fun 基础资产补成USDT交易对() {
        // 币安账户接口给裸资产名，落库必须是 App 取价用的交易对形态
        assertEquals("BTCUSDT", baseAssetToSymbol("BTC"))
        assertEquals("ETHUSDT", baseAssetToSymbol("ETH"))
        assertEquals("LTCUSDT", baseAssetToSymbol("LTC"))
        assertEquals("SOLUSDT", baseAssetToSymbol("SOL"))
    }

    @Test
    fun 大小写和空格都被归一() {
        assertEquals("BTCUSDT", baseAssetToSymbol("btc"))
        assertEquals("ETHUSDT", baseAssetToSymbol("  eth "))
    }

    @Test
    fun 已是USDT计价的稳定币原样返回() {
        // USDT 自身没有 USDTUSDT 交易对，原样保留（价格同步会留空，但余额准确）
        assertEquals("USDT", baseAssetToSymbol("USDT"))
    }

    // ---------- symbols 参数构造（币安批量询价 URL） ----------

    @Test
    fun symbols参数无空格否则币安会400() {
        // 回归币安批量询价整批 400 的根因：joinToString 默认 ", " 带空格，
        // 经 URLEncoder 变成 '+'，币安的 symbols 解析器不认 → 400。
        // 这里断言编码产物里不含 '+'（即不含空格），且解码回 JSON 无空格。
        val encoded = BinanceAccountAPI.buildSymbolsParam(listOf("BTCUSDT", "LTCUSDT"))
        assertTrue("symbols 参数编码后不该出现 '+'（那是 URL 编码后的空格）", !encoded.contains("+"))
        assertEquals("[\"BTCUSDT\",\"LTCUSDT\"]", URLDecoder.decode(encoded, "UTF-8"))
    }

    @Test
    fun symbols参数空列表返回空数组() {
        assertEquals("%5B%5D", BinanceAccountAPI.buildSymbolsParam(emptyList()))
    }

    // ---------- getPrices 整批 400 隔离坏符号（自定义 Interceptor 模拟币安语义） ----------

    /**
     * 模拟币安 `/ticker/price` 语义的假 Interceptor：请求 URL 的 symbols 里只要含一个
     * [badSymbols]，整批回 400（币安「一个非法符号 → 整批失败」的真实行为）；否则回
     * application/json 的价表。零外部依赖，不连网。
     */
    private class FakeBinanceInterceptor(private val badSymbols: Set<String>) : Interceptor {
        /** 记录实际发出的请求次数，用于断言「全合法批次零额外请求」 */
        var requestCount = 0
            private set

        override fun intercept(chain: Interceptor.Chain): Response {
            requestCount++
            val url = chain.request().url.toString()
            val symbolsParam = url.substringAfter("symbols=").substringBefore("&")
            val decoded = URLDecoder.decode(symbolsParam, "UTF-8") // 形如 ["BTCUSDT","BADCOINUSDT"]
            val list = decoded.removeSurrounding("[", "]")
                .split(",")
                .map { it.trim().removeSurrounding("\"", "\"") }
                .filter { it.isNotEmpty() }

            val builder = Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)

            return if (list.any { it in badSymbols }) {
                // 模拟币安整批 400：一个非法符号拖垮整批
                builder
                    .code(400)
                    .message("Bad Request")
                    .body("""{"code":-1121,"msg":"Invalid symbol"}""".toResponseBody("application/json".toMediaType()))
                    .build()
            } else {
                val priceArray = list.joinToString(",") { """{"symbol":"$it","price":"${priceOf(it)}"}""" }
                builder
                    .code(200)
                    .message("OK")
                    .body("[$priceArray]".toResponseBody("application/json".toMediaType()))
                    .build()
            }
        }

        /** 测试用价表：未知符号给 1.0，足够验证「好符号取到价、坏符号被丢弃」 */
        private fun priceOf(symbol: String): Double = when (symbol) {
            "BTCUSDT" -> 67000.0
            "ETHUSDT" -> 3500.0
            "BCHUSDT" -> 500.0
            "BNBUSDT" -> 600.0
            else -> 1.0
        }
    }

    @Test
    fun 整批含坏符号时隔离出好符号_坏符号被丢弃() = runBlocking {
        val interceptor = FakeBinanceInterceptor(setOf("BADCOINUSDT"))
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()

        val prices = BinanceAccountAPI.getPrices(
            listOf("BTCUSDT", "BADCOINUSDT", "ETHUSDT"),
            client
        )

        // 好符号都取到了价，坏符号被对半重试隔离后丢弃
        assertEquals(67000.0, prices["BTCUSDT"]!!, 1e-9)
        assertEquals(3500.0, prices["ETHUSDT"]!!, 1e-9)
        assertFalse("坏符号 BADCOINUSDT 不应出现在结果里", "BADCOINUSDT" in prices)
    }

    @Test
    fun 全合法批次零额外请求_只发一次() = runBlocking {
        val interceptor = FakeBinanceInterceptor(emptySet())
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()

        val pairs = (0 until 5).map { "GOOD${it}USDT" } // 5 个合法符号
        val prices = BinanceAccountAPI.getPrices(pairs, client)

        // 无坏符号：整批一次成功，不发任何重试请求
        assertEquals(1, interceptor.requestCount)
        assertEquals(5, prices.size)
    }

    @Test
    fun 重复交易对被去重() = runBlocking {
        val interceptor = FakeBinanceInterceptor(emptySet())
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()

        val prices = BinanceAccountAPI.getPrices(
            listOf("BTCUSDT", "BTCUSDT", "ETHUSDT"),
            client
        )
        assertEquals(2, prices.size)
        assertEquals(67000.0, prices["BTCUSDT"]!!, 1e-9)
    }

    @Test
    fun 空列表返回空Map() = runBlocking {
        val interceptor = FakeBinanceInterceptor(emptySet())
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        assertTrue(BinanceAccountAPI.getPrices(emptyList(), client).isEmpty())
    }

    // ---------- 理财 SAPI 接口基址（回归：绝不能误带 /api/v3 前缀） ----------

    @Test
    fun 理财接口走正确的SAPI基址_不带api_v3前缀() = runBlocking {
        // 回归根因：SAPI 与现货 REST 是两套基址，SAPI 不在 /api/v3 之下。旧代码把现货基址
        // （含 /api/v3）拼到 SAPI 路径前，生成 /api/v3/sapi/v1/... 这种不存在的路径，被网关回
        // 空 body 404，理财活期/定期永远拉不到、且伪装成「接口未开通」。这里钉死真实发出的路径。
        val recorded = mutableListOf<String>()
        val interceptor = object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                recorded.add(chain.request().url.encodedPath)
                return Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""{"rows":[],"total":0}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
        }
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        BinanceAccountAPI.getEarnPositions("dummyKey", "dummySecret", client)

        // 活期 + 定期两个接口各发一次
        assertEquals("应发出 2 个理财接口请求（活期+定期）", 2, recorded.size)
        assertTrue(
            "活期接口应为 /sapi/v1/simple-earn/flexible/position",
            recorded.any { it == "/sapi/v1/simple-earn/flexible/position" }
        )
        assertTrue(
            "定期接口应为 /sapi/v1/simple-earn/locked/position",
            recorded.any { it == "/sapi/v1/simple-earn/locked/position" }
        )
        assertTrue(
            "SAPI 绝不能带 /api/v3 前缀（那是现货基址，拼上去路径不存在→404）",
            recorded.none { it.contains("/api/v3/") }
        )
    }
}
