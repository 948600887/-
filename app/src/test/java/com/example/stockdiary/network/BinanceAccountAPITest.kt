package com.example.stockdiary.network

import com.example.stockdiary.data.importer.baseAssetToSymbol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

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
        // 不能用普通 mutableListOf：getEarnPositions 用两个并发 async 在 Dispatchers.IO 上各自发请求，
        // 两条 recorded.add 会并发执行，ArrayList 非线程安全会偶发丢一条（满负载争用时 recorded.size 变 1）。
        // 用 CopyOnWriteArrayList 保证并发 add 不丢，否则这条用例在全量套件下偶发失败。
        val recorded = CopyOnWriteArrayList<String>()
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

        // 活期 + 定期两个核心接口各发一次，外加一个 Staking 诊断探针（权限全开却 earn 拉不到
        // 时，用来确认钱是否其实在 Staking 而非 Simple Earn）。探针只记日志、不并入合并链路。
        assertEquals("应发出 3 个请求（活期+定期+Staking诊断探针）", 3, recorded.size)
        assertTrue(
            "活期接口应为 /sapi/v1/simple-earn/flexible/position",
            recorded.any { it == "/sapi/v1/simple-earn/flexible/position" }
        )
        assertTrue(
            "定期接口应为 /sapi/v1/simple-earn/locked/position",
            recorded.any { it == "/sapi/v1/simple-earn/locked/position" }
        )
        assertTrue(
            "Staking诊断探针应为 /sapi/v1/staking/position",
            recorded.any { it == "/sapi/v1/staking/position" }
        )
        assertTrue(
            "SAPI 绝不能带 /api/v3 前缀（那是现货基址，拼上去路径不存在→404）",
            recorded.none { it.contains("/api/v3/") }
        )
    }

    // ---------- 理财持仓解析：字段口径必须与币安现行文档一致 ----------

    /**
     * 官方 `GET /sapi/v1/simple-earn/flexible/position` 的响应形状（逐字段照抄文档）。
     *
     * 钉死这一条是为了让「理财数量不对」这类问题能一眼分清责任：
     * 若币安按文档回数据而 App 取不到，锅在 App；若这条绿而真机还是少，
     * 那就是接口侧（权限/地区/接口）没给数据，与解析无关。
     */
    private val officialFlexibleBody = """
        {
          "rows": [
            {
              "totalAmount": "142.08565983",
              "tierAnnualPercentageRate": {"0-5BTC": 0.05, "5-10BTC": 0.03},
              "latestAnnualPercentageRate": "0.0702",
              "yesterdayAirdropPercentageRate": "0.02599895",
              "asset": "USDT",
              "airDropAsset": "BETH",
              "canRedeem": true,
              "collateralAmount": "17.49055419",
              "productId": "USDT001",
              "yesterdayRealTimeRewards": "0.10293829",
              "cumulativeBonusRewards": "0.22759183",
              "cumulativeRealTimeRewards": "0.22759183",
              "cumulativeTotalRewards": "0.45459183",
              "autoSubscribe": true
            }
          ],
          "total": 1
        }
    """.trimIndent()

    /** 官方 `GET /sapi/v1/simple-earn/locked/position` 的响应形状（定期行的数量在 amount） */
    private val officialLockedBody = """
        {
          "rows": [
            {
              "positionId": "123123",
              "projectId": "Axs*90",
              "asset": "BNB",
              "amount": "0.01000000",
              "purchaseTime": "1646182276000",
              "duration": "60",
              "accrualDays": "4",
              "rewardAsset": "BNB",
              "APY": "0.23",
              "isRenewable": true,
              "isAutoRenew": true,
              "redeemDate": "1732182276000"
            }
          ],
          "total": 1
        }
    """.trimIndent()

    @Test
    fun 活期理财持仓按官方文档形状解析() {
        // 活期的数量在 totalAmount（不是 amount），且文档里那个 tierAnnualPercentageRate
        // 是嵌套对象——解析不能因为它而整页失效。
        val parsed = BinanceAccountAPI.parseEarnPositions(officialFlexibleBody, locked = false)
        assertEquals(1, parsed.positions.size)
        assertEquals("USDT", parsed.positions[0].asset)
        assertEquals(142.08565983, parsed.positions[0].amount, 1e-8)
        assertFalse("活期持仓 locked 应为 false", parsed.positions[0].locked)
        assertFalse("按文档回数据时不该报任何形状问题", parsed.hasShapeProblem)
    }

    @Test
    fun 定期理财持仓按官方文档形状解析() {
        // 定期的数量在 amount（字段与活期不同），解析必须按 locked 取对应字段
        val parsed = BinanceAccountAPI.parseEarnPositions(officialLockedBody, locked = true)
        assertEquals(1, parsed.positions.size)
        assertEquals("BNB", parsed.positions[0].asset)
        assertEquals(0.01, parsed.positions[0].amount, 1e-8)
        assertTrue("定期持仓 locked 应为 true", parsed.positions[0].locked)
        assertFalse("按文档回数据时不该报任何形状问题", parsed.hasShapeProblem)
    }

    // ---------- 「HTTP 200 但形状不对」绝不能再伪装成成功 ----------

    @Test
    fun 响应体没有rows数组时算失败() {
        // 真机嫌疑最大的一条：网关/接口返回 200，但响应体不是 {"rows":[...]} 形状。
        // 旧实现直接 return emptyList()，调用方无从分辨「接口形状变了」与「用户没有理财」，
        // 于是预览页零警告、数量却比官方少一截。
        val parsed = BinanceAccountAPI.parseEarnPositions(
            """{"code":-2015,"msg":"Invalid API-key, IP, or permissions for action."}""",
            locked = false
        )
        assertTrue("没有 rows 数组必须被识别成形状问题", parsed.hasShapeProblem)
        assertTrue(parsed.failures("活期").any { it.contains("没有 rows 数组") })
    }

    @Test
    fun 响应体不是JSON对象时算失败() {
        // 地区限制页常返回 HTML/空串；旧实现会抛异常后被吞成空列表，同样零提示
        val parsed = BinanceAccountAPI.parseEarnPositions(
            "<html><body>Service unavailable</body></html>",
            locked = false
        )
        assertTrue("HTML 响应必须被识别成形状问题", parsed.hasShapeProblem)
        assertTrue(parsed.failures("活期").any { it.contains("不是 JSON") })
    }

    @Test
    fun rows为空数组不算失败() {
        // {"rows":[],"total":0} 是「真的没有理财持仓」，绝不能报假警报
        val parsed = BinanceAccountAPI.parseEarnPositions("""{"rows":[],"total":0}""", locked = false)
        assertTrue(parsed.positions.isEmpty())
        assertFalse("空 rows 是真的没有持仓，不是形状问题", parsed.hasShapeProblem)
        assertTrue(parsed.failures("活期").isEmpty())
    }

    @Test
    fun 行内缺数量字段时算失败_但要带出原因() {
        // 币安若把活期的数量字段改了名，整行会被跳过（旧实现 continue 就吞了）
        val parsed = BinanceAccountAPI.parseEarnPositions(
            """{"rows":[{"asset":"USDT","totalAmountOld":"142.08565983"}],"total":1}""",
            locked = false
        )
        assertTrue("缺数量字段必须被报出来", parsed.hasShapeProblem)
        assertTrue(
            parsed.failures("活期").any { it.contains("缺数量字段") }
        )
        assertTrue(parsed.positions.isEmpty())
    }

    @Test
    fun 行内缺资产名时算失败() {
        val parsed = BinanceAccountAPI.parseEarnPositions(
            """{"rows":[{"totalAmount":"142.08565983"}],"total":1}""",
            locked = false
        )
        assertTrue(parsed.hasShapeProblem)
        assertTrue(parsed.failures("活期").any { it.contains("缺资产名") })
    }

    @Test
    fun 部分行坏了时好行照留_同时报问题() {
        // 部分成功：好行必须留下（丢掉等于少算），坏行必须报（否则用户不知道少了）
        val parsed = BinanceAccountAPI.parseEarnPositions(
            """
            {"rows":[
                {"asset":"USDT","totalAmount":"142.08565983"},
                {"asset":"USDC","totalAmountOld":"43.57300526"}
            ],"total":2}
            """.trimIndent(),
            locked = false
        )
        assertEquals(142.08565983, parsed.positions.single().amount, 1e-8)
        assertTrue("坏行必须被报出来", parsed.hasShapeProblem)
        assertTrue(parsed.failures("活期").any { it.contains("1/2 行被跳过") })
    }

    @Test
    fun 数量为0的空壳行不算失败() {
        // 已赎回的空壳行数量是 0，属正常状态，不该给用户报假警报
        val parsed = BinanceAccountAPI.parseEarnPositions(
            """{"rows":[{"asset":"USDT","totalAmount":"0.00000000"}],"total":1}""",
            locked = false
        )
        assertTrue(parsed.positions.isEmpty())
        assertEquals(1, parsed.emptyRows)
        assertFalse("空壳行不是形状问题", parsed.hasShapeProblem)
    }

    @Test
    fun 非对象行不再把整页带崩() {
        // 旧实现用 getJSONObject(i)，非对象行会抛出、被外层 catch 成「整页失效」
        val parsed = BinanceAccountAPI.parseEarnPositions(
            """{"rows":["oops",{"asset":"USDT","totalAmount":"142.08565983"}],"total":2}""",
            locked = false
        )
        assertEquals(142.08565983, parsed.positions.single().amount, 1e-8)
        assertTrue(parsed.failures("活期").any { it.contains("不是对象") })
    }

    @Test
    fun 接口回200但形状不对时_getEarnPositions把问题带给用户() = runBlocking {
        // 端到端：HTTP 200 + 无 rows 的响应，不能再伪装成「全部成功」。
        // 这是本次根因排查的关键——用户预览页必须看得见东西，否则下一轮还是靠猜。
        val client = OkHttpClient.Builder()
            .addInterceptor(object : Interceptor {
                override fun intercept(chain: Interceptor.Chain): Response =
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(
                            """{"data":[],"success":true}"""
                                .toResponseBody("application/json".toMediaType())
                        )
                        .build()
            })
            .build()

        val fetch = BinanceAccountAPI.getEarnPositions("dummyKey", "dummySecret", client)
        assertTrue(fetch.positions.isEmpty())
        assertEquals("两个接口各一条形状问题", 2, fetch.failures.size)
        assertNotNull("必须给出用户能看到的原因", fetch.warning)
        assertTrue(fetch.warning!!.contains("没有 rows 数组"))
    }

    @Test
    fun 理财接口按官方文档返回时能拿到官方全量() = runBlocking {
        // 决定性：接口按文档回 142.08565983 时，App 端到端拿到的就是 142.08565983。
        // 这条绿 = 请求 URL/签名/解析三段都没问题（真机若仍少，原因在接口侧）。
        val client = OkHttpClient.Builder()
            .addInterceptor(object : Interceptor {
                override fun intercept(chain: Interceptor.Chain): Response {
                    val body = if (chain.request().url.encodedPath.contains("/locked/")) {
                        officialLockedBody
                    } else {
                        officialFlexibleBody
                    }
                    return Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                }
            })
            .build()

        val fetch = BinanceAccountAPI.getEarnPositions("dummyKey", "dummySecret", client)
        assertTrue("接口按文档回数据时不该有失败", fetch.failures.isEmpty())
        val usdt = fetch.positions.single { it.asset == "USDT" }
        assertEquals(142.08565983, usdt.amount, 1e-8)
        assertFalse(usdt.locked)
        assertEquals(0.01, fetch.positions.single { it.asset == "BNB" }.amount, 1e-8)
    }

    // ---------- 理财持仓分页：持仓数 > 单页上限时必须翻页拉全 ----------

    /**
     * 复现真机根因：用户活期理财持仓 > 100，旧代码只拉 `current=1` 第一页，
     * 第 2 页（含 USDT 144.99）整批被漏，预览页零警告。
     *
     * 这里用满一页（100 行）+ 一页少量（含 USDT）的响应，验证：
     * 1. 翻页真的发出了 `current=2` 请求；
     * 2. 第 2 页的 USDT 能被拉到，不漏。
     *
     * 注意 `EARN_PAGE_SIZE` 是 `private const val`，本测试文件无法引用，这里硬编码 100
     * 并钉死——若改动 [BinanceAccountAPI.EARN_PAGE_SIZE] 必须同步更新此处的 100。
     */
    @Test
    fun 理财持仓超过单页上限时翻页拉全_不漏第2页() = runBlocking {
        val pageSize = 100
        val recordedCurrent = CopyOnWriteArrayList<Int>()

        // total 用「真实总条数」101（第 1 页 100 + 第 2 页 1），走 total 精确翻页路径
        fun buildRows(n: Int, startAsset: Int, total: Int = n) = buildString {
            append("{\"rows\":[")
            append(
                (0 until n).joinToString(",") {
                    """{"asset":"EARN${startAsset + it}","totalAmount":"1.0"}"""
                }
            )
            append("],\"total\":$total}")
        }
        val flexiblePage1 = buildRows(pageSize, 0, pageSize + 1) // 满一页 100 行、total=101 → 触发翻页
        val flexiblePage2 = """{"rows":[{"asset":"USDT","totalAmount":"144.99343452"}],"total":101}"""

        val client = OkHttpClient.Builder()
            .addInterceptor(object : Interceptor {
                override fun intercept(chain: Interceptor.Chain): Response {
                    val url = chain.request().url
                    val q = url.query ?: ""
                    val current = q.substringAfter("current=").substringBefore("&").toIntOrNull() ?: 1
                    val path = url.encodedPath
                    val body = when {
                        path.contains("/flexible/") -> {
                            recordedCurrent.add(current)
                            if (current <= 1) flexiblePage1 else flexiblePage2
                        }
                        else -> """{"rows":[],"total":0}""" // locked / staking 探针：空，不干扰断言
                    }
                    return Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                }
            })
            .build()

        val fetch = BinanceAccountAPI.getEarnPositions("dummyKey", "dummySecret", client)
        assertTrue("翻页必须发出 current=2 请求，否则第 2 页持仓会漏", recordedCurrent.contains(2))
        assertTrue("接口按文档回数据时不该有失败", fetch.failures.isEmpty())
        assertEquals("第1页满100 + 第2页1 = 101", 101, fetch.positions.size)
        val usdt = fetch.positions.single { it.asset == "USDT" }
        assertEquals(144.99343452, usdt.amount, 1e-8)
        assertFalse(usdt.locked)
    }

    /**
     * 边界：持仓数恰好等于一页上限（100 行）且接口 `total=100` 时，total 精确翻页应
     * **只发 1 次请求就停**，不再靠「多打一页空请求探测末页」——这正是 total 驱动的收益。
     */
    @Test
    fun 刚好一页持仓时按total精确停止_不多打空请求() = runBlocking {
        val pageSize = 100
        val recordedCurrent = CopyOnWriteArrayList<Int>()
        val flexiblePage1 = buildString {
            append("{\"rows\":[")
            append((0 until pageSize).joinToString(",") { """{"asset":"EARN$it","totalAmount":"1.0"}""" })
            append("],\"total\":$pageSize}")
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(object : Interceptor {
                override fun intercept(chain: Interceptor.Chain): Response {
                    val url = chain.request().url
                    val q = url.query ?: ""
                    val current = q.substringAfter("current=").substringBefore("&").toIntOrNull() ?: 1
                    val path = url.encodedPath
                    val body = when {
                        path.contains("/flexible/") -> {
                            recordedCurrent.add(current)
                            // 只有第 1 页有数据；若请求第 2 页则返回空（用来暴露「是否白打空请求」）
                            if (current <= 1) flexiblePage1 else """{"rows":[],"total":0}"""
                        }
                        else -> """{"rows":[],"total":0}"""
                    }
                    return Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                }
            })
            .build()

        val fetch = BinanceAccountAPI.getEarnPositions("dummyKey", "dummySecret", client)
        assertTrue(fetch.failures.isEmpty())
        assertEquals(pageSize, fetch.positions.size)
        // total=100 且已拉 100 行 → 第 1 页即判定拉全，绝不该再请求第 2 页
        assertEquals("恰好满一页时只发 1 次请求（不白打空请求）", 1, recordedCurrent.size)
    }

    /**
     * 回退路径：接口**没给 `total`**（字段缺失/为 0）时，仍要靠「本页行数 < 单页上限」启发式
     * 翻页拉全——保证 total 字段哪天消失也不会退回「只拉第一页」的老 bug。
     */
    @Test
    fun 接口无total字段时回退启发式仍能翻页拉全() = runBlocking {
        val pageSize = 100
        val recordedCurrent = CopyOnWriteArrayList<Int>()
        // 两条都刻意不写 total（optInt 默认 0 → 视为未知）
        val page1 = buildString {
            append("{\"rows\":[")
            append((0 until pageSize).joinToString(",") { """{"asset":"EARN$it","totalAmount":"1.0"}""" })
            append("]}")
        }
        val page2 = """{"rows":[{"asset":"USDT","totalAmount":"144.99343452"}]}"""
        val client = OkHttpClient.Builder()
            .addInterceptor(object : Interceptor {
                override fun intercept(chain: Interceptor.Chain): Response {
                    val url = chain.request().url
                    val q = url.query ?: ""
                    val current = q.substringAfter("current=").substringBefore("&").toIntOrNull() ?: 1
                    val path = url.encodedPath
                    val body = when {
                        path.contains("/flexible/") -> {
                            recordedCurrent.add(current)
                            if (current <= 1) page1 else page2
                        }
                        else -> """{"rows":[]}"""
                    }
                    return Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                }
            })
            .build()

        val fetch = BinanceAccountAPI.getEarnPositions("dummyKey", "dummySecret", client)
        assertTrue("total 缺失属于正常回退，不该报失败", fetch.failures.isEmpty())
        assertTrue("回退路径也必须发出 current=2", recordedCurrent.contains(2))
        assertEquals("100 + 1 = 101", 101, fetch.positions.size)
        assertEquals(144.99343452, fetch.positions.single { it.asset == "USDT" }.amount, 1e-8)
    }

    // ---------- 单页重试：弱网抖一次不该让整块持仓消失 ----------

    /**
     * 构造一个「活期第 2 页首次请求抛 SocketTimeoutException、重试起正常」的假 client。
     *
     * @return 生成的 client + 第 2 页的请求尝试次数计数器（用来断言真的重试了）
     */
    private fun clientWithFlakySecondPage(
        flexiblePage1: String,
        flexiblePage2: String
    ): Pair<OkHttpClient, AtomicInteger> {
        val page2Attempts = AtomicInteger(0)
        val client = OkHttpClient.Builder()
            .addInterceptor(object : Interceptor {
                override fun intercept(chain: Interceptor.Chain): Response {
                    val url = chain.request().url
                    val current = (url.query ?: "")
                        .substringAfter("current=")
                        .substringBefore("&")
                        .toIntOrNull() ?: 1
                    val path = url.encodedPath
                    // 只有活期接口参与本用例；定期与 Staking 探针回空数组，不干扰断言
                    if (!path.contains("/flexible/")) {
                        return jsonResponse(chain.request(), """{"rows":[],"total":0}""")
                    }
                    if (current <= 1) {
                        return jsonResponse(chain.request(), flexiblePage1)
                    }
                    // 第 2 页：**第一次**抛读超时（真机 20:59:52 就是这个异常），之后正常
                    if (page2Attempts.incrementAndGet() == 1) {
                        throw SocketTimeoutException("timeout")
                    }
                    return jsonResponse(chain.request(), flexiblePage2)
                }
            })
            .build()
        return client to page2Attempts
    }

    /** 拼一个 200 + application/json 的假响应 */
    private fun jsonResponse(request: okhttp3.Request, body: String): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()

    /**
     * 真机根因回归：活期第 2 页**首次**抛 `SocketTimeoutException`，重试后返回正常数据 → 仍拉全。
     *
     * 诊断日志 2026-11-01 20:59:52：「币安理财接口[活期] 第2页拉取失败，停止翻页：
     * SocketTimeoutException」——一次弱网抖动就让 USDT（144.99，在第 2 页）整块漏掉，
     * 用户侧表现为「导入的资产比币安 App 少」。修复后单页最多重试 2 次（共 3 次尝试），
     * 第 2 次尝试成功即照常翻页，USDT 不再漏。
     *
     * 注意退避传 0：重试会引入真实 delay，单测绝不能靠 Thread.sleep 撑时间。
     */
    @Test
    fun 活期第2页首次超时_重试后仍拉全() = runBlocking {
        val pageSize = 100
        val page1 = buildString {
            append("{\"rows\":[")
            append((0 until pageSize).joinToString(",") { """{"asset":"EARN$it","totalAmount":"1.0"}""" })
            append("],\"total\":101}")
        }
        val page2 = """{"rows":[{"asset":"USDT","totalAmount":"144.99343452"}],"total":101}"""
        val (client, page2Attempts) = clientWithFlakySecondPage(page1, page2)

        val fetch = BinanceAccountAPI.getEarnPositions(
            "dummyKey",
            "dummySecret",
            client,
            retryDelaysMs = listOf(0L, 0L)
        )

        assertEquals("第 2 页应尝试 2 次（首次超时 + 重试成功）", 2, page2Attempts.get())
        assertTrue("重试成功后不该再报失败", fetch.failures.isEmpty())
        assertEquals("第1页满100 + 第2页1 = 101，一条都不该漏", 101, fetch.positions.size)
        val usdt = fetch.positions.single { it.asset == "USDT" }
        assertEquals(144.99343452, usdt.amount, 1e-8)
    }

    /**
     * 重试用尽仍失败时，契约不变：保留已拉到的页、记 failure、**提示按网络类分流**。
     *
     * 真机教训：第 2 页明明是 `SocketTimeoutException`（网络），旧文案却让用户去确认
     * 「读取 / Enable Reading」权限与 IP 白名单——用户照着翻了一整轮 API Key 设置也解决不了。
     */
    @Test
    fun 单页超时重试用尽后_提示网络问题而不是权限() = runBlocking {
        val client = OkHttpClient.Builder()
            .addInterceptor(object : Interceptor {
                override fun intercept(chain: Interceptor.Chain): Response {
                    val path = chain.request().url.encodedPath
                    if (path.contains("/flexible/")) {
                        // 活期一直超时：重试用尽后必须放弃翻页
                        throw SocketTimeoutException("timeout")
                    }
                    return jsonResponse(chain.request(), """{"rows":[],"total":0}""")
                }
            })
            .build()

        // retryDelaysMs 传空 = 不重试，避免单测等真实退避；重试路径由上一个用例覆盖
        val fetch = BinanceAccountAPI.getEarnPositions(
            "dummyKey",
            "dummySecret",
            client,
            retryDelaysMs = emptyList()
        )

        assertFalse(fetch.failures.isEmpty())
        val warning = fetch.warning
        assertNotNull("失败必须给出用户能看到的原因", warning)
        assertTrue(
            "超时属网络问题，提示必须指向网络/代理：实际=$warning",
            warning!!.contains("网络或代理不稳定")
        )
        assertFalse(
            "网络问题不该让用户去翻 API Key 权限/白名单：实际=$warning",
            warning.contains("Enable Reading")
        )
    }

    // ---------- 重试的**边界**：哪些异常绝不重试 ----------
    // 上面两条证明「IO 失败会重试」，下面两条证明「取消」与「业务/权限错误」不会被重试。
    // 少了它们，把 `retryPageRequest` 写成「catch (e: Exception) 一律重试 3 次」也能过测试，
    // 而那会造成两个真机问题：用户关掉导入弹窗后仍继续打接口；-2015 权限不足白刷 3 次。

    /**
     * 协程取消必须**原样抛出、绝不重试**。
     *
     * 「用户关掉导入弹窗」在协程里就是 [CancellationException]。如果它被
     * `catch (e: Exception)` 当成可重试异常吞掉，真机表现是：弹窗已关，后台还在按
     * 500ms→1200ms 退避继续发签名请求，并且被记成一次「失败」影响后续提示。
     *
     * 判据取两件事：**只发出 1 次请求**（没重试）+ 抛出来的仍是 CancellationException（没被包装）。
     */
    @Test
    fun 协程取消不被重试_且原样抛出() = runBlocking {
        // 只数**活期接口**的请求次数：`getEarnPositions` 还会并发拉定期、并额外发一次
        // Staking 诊断探针，把它们算进来就分不清「重试」和「另一个接口」了
        val attempts = AtomicInteger(0)
        val client = OkHttpClient.Builder()
            .addInterceptor(object : Interceptor {
                override fun intercept(chain: Interceptor.Chain): Response {
                    val path = chain.request().url.encodedPath
                    if (!path.contains("/simple-earn/flexible/")) {
                        return jsonResponse(chain.request(), """{"rows":[],"total":0}""")
                    }
                    attempts.incrementAndGet()
                    throw CancellationException("用户关掉了导入弹窗")
                }
            })
            .build()

        // 捕获 Throwable：CancellationException 在协程里是「取消」而非「失败」，
        // 用 catch (e: Exception) 也接得到，但写全 Throwable 免得将来换成别的取消类型漏掉。
        val thrown: Throwable? = try {
            BinanceAccountAPI.getEarnPositions("dummyKey", "dummySecret", client, retryDelaysMs = listOf(0L, 0L))
            null
        } catch (e: Throwable) {
            e
        }

        assertEquals("取消不该被重试：活期接口应只发出 1 次请求", 1, attempts.get())
        assertTrue(
            "取消必须原样抛出（不能被包装成普通失败）：实际=${thrown?.javaClass?.name}",
            thrown is CancellationException
        )
    }

    /**
     * 币安业务 / 权限错误（HTTP 4xx、业务码 -2015）**绝不重试**。
     *
     * 关键实现细节：[BinanceAccountAPI.binanceError] 返回的是普通 [Exception]，不是
     * `IOException`，而 `retryPageRequest` 只在 `e is IOException` 时重试。这里直接从
     * 外部可观测行为钉住这条：401/-2015 只发 1 次请求。
     *
     * 顺带钉住文案分流没有把权限建议一起弄丢：-2015 属权限类，必须**仍然**提示
     * 「Enable Reading / IP 白名单」，且**不能**改口说成网络问题。
     */
    @Test
    fun 币安业务错误不重试_且提示仍指向权限() = runBlocking {
        val attempts = AtomicInteger(0)
        val client = OkHttpClient.Builder()
            .addInterceptor(object : Interceptor {
                override fun intercept(chain: Interceptor.Chain): Response {
                    // 只拦活期/定期这两个理财接口；Staking 探针回空数组免得干扰
                    if (!chain.request().url.encodedPath.contains("/simple-earn/")) {
                        return jsonResponse(chain.request(), """{"rows":[],"total":0}""")
                    }
                    attempts.incrementAndGet()
                    return Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(401)
                        .message("Unauthorized")
                        .body(
                            """{"code":-2015,"msg":"Invalid API-key, IP, or permissions for action."}"""
                                .toResponseBody("application/json".toMediaType())
                        )
                        .build()
                }
            })
            .build()

        val fetch = BinanceAccountAPI.getEarnPositions("dummyKey", "dummySecret", client, retryDelaysMs = listOf(0L, 0L))

        assertEquals(
            "-2015 是权限不足，重试 3 次也还是 -2015：活期+定期各只该请求 1 次",
            2,
            attempts.get()
        )
        val warning = fetch.warning
        assertNotNull("失败必须给出用户能看到的原因", warning)
        assertTrue(
            "权限类失败仍必须给出权限/IP 白名单建议：实际=$warning",
            warning!!.contains("Enable Reading")
        )
        assertFalse(
            "权限类失败不该被引导去查网络：实际=$warning",
            warning.contains("网络或代理不稳定")
        )
    }

    /**
     * 网络类判定只认网络异常：[BinanceAccountAPI.isNetworkFailure] 的分类表。
     *
     * 这条是文案分流的唯一依据，而它必须**不依赖文案字符串**（否则改文案就静默失效）。
     * 逐类各取一个代表，尤其是「[BinanceAccountAPI.binanceError] 的返回值不算网络」
     * —— 那正是把用户骗去翻 API Key 设置的根因。
     */
    @Test
    fun 网络类失败判定只认网络异常_权限错误不算网络() {
        assertTrue("读超时属网络", BinanceAccountAPI.isNetworkFailure(SocketTimeoutException("timeout")))
        assertTrue("DNS 解析不了属网络", BinanceAccountAPI.isNetworkFailure(UnknownHostException("api.binance.com")))
        assertTrue("TCP 连不上属网络", BinanceAccountAPI.isNetworkFailure(ConnectException("Connection refused")))
        assertTrue("callTimeout 收尾成 InterruptedIOException，也属网络", BinanceAccountAPI.isNetworkFailure(InterruptedIOException("timeout")))

        val denied = BinanceAccountAPI.binanceError(
            """{"code":-2015,"msg":"Invalid API-key, IP, or permissions for action."}""",
            401
        )
        assertFalse(
            "binanceError 抛的是普通 Exception（权限/业务错误），绝不能算成网络失败",
            BinanceAccountAPI.isNetworkFailure(denied)
        )
        // 「响应体为空」在本文件里也是 IOException：它会被重试（换个时机可能就好了），
        // 但**不该**引导用户去查网络/代理——它不是连不上。
        assertFalse(
            "空响应是响应异常而非连不上，不该提示网络",
            BinanceAccountAPI.isNetworkFailure(java.io.IOException("币安返回了空响应"))
        )
    }

    /**
     * 生产默认退避必须**非空且非零**：空列表等于静默关掉重试，全 0 等于热循环打接口。
     *
     * 这条不是形式主义：真机那次就是「一次 SocketTimeout 让 USDT 整块消失」，
     * 如果将来有人为了「让单测快一点」把默认值改成空列表，重试会在生产里悄悄失效，
     * 而所有单测（都显式注入退避）依然全绿。
     */
    @Test
    fun 生产默认退避非空且非零() {
        val defaults = BinanceAccountAPI.DEFAULT_PAGE_RETRY_BACKOFF_MS
        assertTrue("默认必须真的重试（空列表 = 静默关掉重试）", defaults.isNotEmpty())
        assertTrue("退避不能是 0，否则失败时会热循环打接口：实际=$defaults", defaults.all { it > 0L })
    }

    // ---------- 文案分流三类齐全：网络 / 限流或服务端 / 权限 ----------
    // 三类失败给用户的动作完全不同：查网络、等几分钟、查权限。任何一类被归错，
    // 用户就会照着错误的建议白折腾（这正是这轮 bug 的形态）。下面三条端到端钉住
    // 「只有本类关键词出现、另两类关键词都不出现」——用真实文案断言，而不是看代码。

    /** 网络类的关键词（只在网络提示里出现） */
    private val networkKeyword = "网络或代理不稳定"

    /** 限流/服务端类的关键词（只在限流提示里出现） */
    private val rateLimitKeyword = "币安接口限流或服务端异常"

    /** 权限类的关键词（只在权限提示里出现） */
    private val permissionKeyword = "Enable Reading"

    /** 造一个「活期 + 定期都回指定 HTTP 失败」的假 client，并统计理财接口的请求次数 */
    private fun clientAlwaysFailing(
        code: Int,
        body: String
    ): Pair<OkHttpClient, AtomicInteger> {
        val attempts = AtomicInteger(0)
        val client = OkHttpClient.Builder()
            .addInterceptor(object : Interceptor {
                override fun intercept(chain: Interceptor.Chain): Response {
                    // 只拦两个理财接口；Staking 诊断探针回空数组，不干扰计数
                    if (!chain.request().url.encodedPath.contains("/simple-earn/")) {
                        return jsonResponse(chain.request(), """{"rows":[],"total":0}""")
                    }
                    attempts.incrementAndGet()
                    return Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(code)
                        .message("Failed")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                }
            })
            .build()
        return client to attempts
    }

    /**
     * 网络类：超时 → 只提示网络，不许提权限、不许提限流。
     *
     * 真机原话：「第2页拉取失败，停止翻页：SocketTimeoutException」，旧文案却让用户去
     * 确认 API Key 权限与 IP 白名单。
     */
    @Test
    fun 失败文案分流_网络类只提示网络() = runBlocking {
        val client = OkHttpClient.Builder()
            .addInterceptor(object : Interceptor {
                override fun intercept(chain: Interceptor.Chain): Response {
                    if (chain.request().url.encodedPath.contains("/simple-earn/")) {
                        throw SocketTimeoutException("timeout")
                    }
                    return jsonResponse(chain.request(), """{"rows":[],"total":0}""")
                }
            })
            .build()

        val fetch = BinanceAccountAPI.getEarnPositions(
            "dummyKey", "dummySecret", client, retryDelaysMs = emptyList()
        )
        val warning = fetch.warning!!

        assertTrue("必须提示网络：实际=$warning", warning.contains(networkKeyword))
        assertFalse("网络问题不该提权限：实际=$warning", warning.contains(permissionKeyword))
        assertFalse("网络问题不该提限流：实际=$warning", warning.contains(rateLimitKeyword))
    }

    /**
     * 权限类：HTTP 401 + `-2015` → 仍然提示权限/IP 白名单（既有能力不能丢），
     * 且不说成网络或限流。
     */
    @Test
    fun 失败文案分流_权限类只提示权限() = runBlocking {
        val (client, _) = clientAlwaysFailing(
            401,
            """{"code":-2015,"msg":"Invalid API-key, IP, or permissions for action."}"""
        )

        val fetch = BinanceAccountAPI.getEarnPositions(
            "dummyKey", "dummySecret", client, retryDelaysMs = listOf(0L, 0L)
        )
        val warning = fetch.warning!!

        assertTrue("权限问题必须提示权限/IP 白名单：实际=$warning", warning.contains(permissionKeyword))
        assertFalse("权限问题不该说成网络：实际=$warning", warning.contains(networkKeyword))
        assertFalse("权限问题不该说成限流：实际=$warning", warning.contains(rateLimitKeyword))
    }

    /**
     * 限流/服务端类：HTTP 429 → 提示「限流或服务端异常（HTTP 429），请过几分钟再试」，
     * 不再叫用户去翻 API Key 权限（限流与用户的 Key 设置毫无关系）。
     *
     * 同时钉住第二条要求：**429 绝不重试**（币安限流时重试会加重封禁）。
     * 理财接口共两个（活期+定期），各只该请求 1 次 → 总计 2 次，不因"可重试"变成 6 次。
     */
    @Test
    fun 失败文案分流_限流类只提示限流且不重试() = runBlocking {
        val (client, attempts) = clientAlwaysFailing(
            429,
            """{"code":-1003,"msg":"Too many requests; current limit is 1200 requests per minute."}"""
        )

        val fetch = BinanceAccountAPI.getEarnPositions(
            "dummyKey", "dummySecret", client, retryDelaysMs = listOf(0L, 0L)
        )
        val warning = fetch.warning!!

        assertTrue("必须提示限流/服务端异常：实际=$warning", warning.contains(rateLimitKeyword))
        assertTrue("必须带上 HTTP 状态码，便于用户核对：实际=$warning", warning.contains("HTTP 429"))
        assertTrue("必须告诉用户过几分钟再试：实际=$warning", warning.contains("请过几分钟再试"))
        assertFalse("限流不该提权限：实际=$warning", warning.contains(permissionKeyword))
        assertFalse("限流不该说成网络：实际=$warning", warning.contains(networkKeyword))
        assertEquals("429 绝不重试：两个理财接口各 1 次，共 2 次", 2, attempts.get())
    }

    /**
     * 5xx 同样归「服务端异常」：币安网关抖动时用户什么都改不了，只该等。
     * 用 503（最典型的服务端不可用）而不是 500，顺带覆盖 `>= 500` 的判定分支。
     */
    @Test
    fun 失败文案分流_服务端5xx归限流类() = runBlocking {
        val (client, attempts) = clientAlwaysFailing(503, """{"code":0,"msg":"Service Unavailable"}""")

        val fetch = BinanceAccountAPI.getEarnPositions(
            "dummyKey", "dummySecret", client, retryDelaysMs = listOf(0L, 0L)
        )
        val warning = fetch.warning!!

        assertTrue("5xx 属服务端异常：实际=$warning", warning.contains(rateLimitKeyword))
        assertTrue("必须带上 HTTP 状态码：实际=$warning", warning.contains("HTTP 503"))
        assertFalse("5xx 不该提权限：实际=$warning", warning.contains(permissionKeyword))
        // 响应体恰好写着 "Service Unavailable" 时，绝不能被当成「地区被限制」去劝用户换代理节点
        assertFalse("5xx 不该说成地区限制：实际=$warning", warning.contains("地区被币安限制"))
        assertEquals("5xx 不重试（重试也还是服务端异常）：两个接口各 1 次", 2, attempts.get())
    }

    /**
     * 418（币安「IP 被临时封禁」）也必须归「限流/服务端」类，而不是权限类。
     *
     * 币安对超频的惩罚是 418 而不是 429（`{"code":-1003,"msg":"Way too many requests; IP banned
     * until ..."}`），两者在用户侧的动作完全一样：**等**。工程师的用例只覆盖了 429/503，
     * 418 这条分支（`httpCode == 418`）没有被任何用例走到——这里补上。
     */
    @Test
    fun 失败文案分流_418IP临时封也归限流类且不重试() = runBlocking {
        val (client, attempts) = clientAlwaysFailing(
            418,
            """{"code":-1003,"msg":"Way too many requests; IP banned until 1730000000000."}"""
        )

        val fetch = BinanceAccountAPI.getEarnPositions(
            "dummyKey", "dummySecret", client, retryDelaysMs = listOf(0L, 0L)
        )
        val warning = fetch.warning!!

        assertTrue("418 属限流：实际=$warning", warning.contains(rateLimitKeyword))
        assertTrue("必须带上 HTTP 418：实际=$warning", warning.contains("HTTP 418"))
        assertFalse("IP 临时封与 API Key 权限无关：实际=$warning", warning.contains(permissionKeyword))
        assertEquals("418 绝不重试（重试只会延长封禁）：两个接口各 1 次", 2, attempts.get())
    }

    /**
     * 回归守卫：451 地区限制**仍要**给「换代理节点」的可操作提示。
     *
     * 本轮工程师收紧了 `isGeo`（删掉 `msg.contains("Service unavailable")`），风险是
     * 把真正的 451 一起收紧掉。451 由**状态码**兜住，所以仍然命中；这条用例从
     * `getEarnPositions` 的**用户可见文案**这一端钉住它（`BinanceAccountAPIGateTest`
     * 只钉了 `getAccount` 的异常 message，是另一条路）。
     *
     * 同时反向钉住：451 是「用户可修」的（换节点），**不能**被归到「限流/服务端，等几分钟」，
     * 也**不能**被说成网络问题。
     */
    @Test
    fun 失败文案分流_451地区限制仍提示换代理节点() = runBlocking {
        val (client, attempts) = clientAlwaysFailing(
            451,
            """{"code":0,"msg":"Service unavailable from a restricted location."}"""
        )

        val fetch = BinanceAccountAPI.getEarnPositions(
            "dummyKey", "dummySecret", client, retryDelaysMs = listOf(0L, 0L)
        )
        val warning = fetch.warning!!

        assertTrue(
            "451 必须给出可操作的地区提示（换代理节点）：实际=$warning",
            warning.contains("更换到允许地区的节点")
        )
        assertFalse("451 不是服务端抖动，不能说成限流/等几分钟：实际=$warning", warning.contains(rateLimitKeyword))
        assertFalse("451 不是网络抖动：实际=$warning", warning.contains(networkKeyword))
        assertEquals("451 不重试：两个接口各 1 次", 2, attempts.get())
    }

    // ---------- 借币（Flexible Loan）负债接口 ----------

    /**
     * 借币接口的 429 限流：同样不重试，且提示「等几分钟」而不是叫用户去查 Loan 权限。
     *
     * `BinanceLoanFetch.warning` 本轮也加了三分支，但**没有任何用例覆盖它**（工程师的
     * 借币用例只走 404 → 权限分支）。借币失败会静默降级成"空负债"（净资产虚高整笔欠款），
     * 文案错了用户根本不知道该等还是该改权限。
     */
    @Test
    fun 借币429限流不重试_提示等几分钟而不是查Loan权限() = runBlocking {
        val loanAttempts = AtomicInteger(0)
        val client = OkHttpClient.Builder()
            .addInterceptor(object : Interceptor {
                override fun intercept(chain: Interceptor.Chain): Response {
                    if (!chain.request().url.encodedPath.contains("/loan/")) {
                        return jsonResponse(chain.request(), """{"rows":[],"total":0}""")
                    }
                    loanAttempts.incrementAndGet()
                    return Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(429)
                        .message("Too Many Requests")
                        .body(
                            """{"code":-1003,"msg":"Too many requests; current limit is 1200 requests per minute."}"""
                                .toResponseBody("application/json".toMediaType())
                        )
                        .build()
                }
            })
            .build()

        val result = BinanceAccountAPI.getLoans(
            "dummyKey", "dummySecret", client, retryDelaysMs = listOf(0L, 0L)
        )
        val warning = result.warning!!

        assertEquals("借币 429 绝不重试（重试会加重币安封禁）", 1, loanAttempts.get())
        assertTrue("必须提示限流：实际=$warning", warning.contains(rateLimitKeyword))
        assertTrue("必须带上 HTTP 429：实际=$warning", warning.contains("HTTP 429"))
        assertFalse("限流与 Loan 读取权限无关：实际=$warning", warning.contains("Loan 读取权限"))
        assertTrue("借币失败仍须明示净资产会虚高：实际=$warning", warning.contains("净资产会虚高"))
    }

    /**
     * 借币接口超时：提示网络，而不是「请确认 API Key 已勾选 Loan 读取权限」。
     *
     * 退避传空列表 = 不重试（重试本身由 `借币单页超时后重试成功_负债不丢` 覆盖），
     * 这里只验「重试用尽后的分流文案」。
     */
    @Test
    fun 借币超时提示网络_不叫用户去查Loan权限() = runBlocking {
        val client = OkHttpClient.Builder()
            .addInterceptor(object : Interceptor {
                override fun intercept(chain: Interceptor.Chain): Response {
                    if (!chain.request().url.encodedPath.contains("/loan/")) {
                        return jsonResponse(chain.request(), """{"rows":[],"total":0}""")
                    }
                    throw SocketTimeoutException("timeout")
                }
            })
            .build()

        val result = BinanceAccountAPI.getLoans(
            "dummyKey", "dummySecret", client, retryDelaysMs = emptyList()
        )
        val warning = result.warning!!

        assertTrue("超时属网络类：实际=$warning", warning.contains(networkKeyword))
        assertFalse("网络问题不该叫用户去查 Loan 权限：实际=$warning", warning.contains("Loan 读取权限"))
        assertFalse("网络问题不该说成限流：实际=$warning", warning.contains(rateLimitKeyword))
    }

    @Test
    fun 借币负债解析totalDebt与loanCoin_并解析抵押资产() {
        // 币安借币接口负债在 totalDebt（不是 amount），借入币在 loanCoin；
        // 同一订单的抵押资产在 collateralCoin/collateralAmount，也必须解析出来
        // （漏掉抵押资产会让净资产只减借来的钱、没加回押出去的资产，被严重低估）
        val json = """{"rows":[{"loanCoin":"USDT","totalDebt":"50.80033811","collateralCoin":"BNB","currentLTV":"0.57"},{"loanCoin":"BNB","totalDebt":"1.5"}],"total":2}"""
        val debts = BinanceAccountAPI.parseLoanOrders(json)
        assertEquals(2, debts.size)
        assertEquals("USDT", debts[0].asset)
        assertEquals(50.80033811, debts[0].debt, 1e-9)
        // 第一行有抵押 BNB（没给 collateralAmount → 默认 0.0），第二行无抵押字段 → null/0
        assertEquals("BNB", debts[0].collateralCoin)
        assertEquals(0.0, debts[0].collateralAmount, 1e-9)
        assertEquals("BNB", debts[1].asset)
        assertEquals(1.5, debts[1].debt, 1e-9)
        assertEquals(null, debts[1].collateralCoin)
        assertEquals(0.0, debts[1].collateralAmount, 1e-9)
    }

    @Test
    fun 借币订单解析抵押资产_作为用户资产加回() {
        // 抵押 1.23 BNB 借 50.8 USDT：collateralCoin/collateralAmount 是用户自己的担保资产，
        // 必须被解析出来（否则净资产漏算这 1.23 BNB）
        val json = """{"rows":[{"loanCoin":"USDT","totalDebt":"50.8","collateralCoin":"BNB","collateralAmount":"1.23"}],"total":1}"""
        val debts = BinanceAccountAPI.parseLoanOrders(json)
        assertEquals(1, debts.size)
        assertEquals("USDT", debts[0].asset)
        assertEquals(50.8, debts[0].debt, 1e-9)
        assertEquals("BNB", debts[0].collateralCoin)
        assertEquals(1.23, debts[0].collateralAmount, 1e-9)
    }

    @Test
    fun 借币负债跳过零值与空行() {
        // debt <= 0 的已还空壳行没导入意义
        val json = """{"rows":[{"loanCoin":"USDT","totalDebt":"0.0"},{"loanCoin":"BNB","totalDebt":"-1.0"},{"loanCoin":"ETH","totalDebt":""}],"total":3}"""
        val debts = BinanceAccountAPI.parseLoanOrders(json)
        assertTrue("零值/负值/空值行都应被跳过", debts.isEmpty())
    }

    @Test
    fun 借币接口走正确的v2SAPI路径_且返回负债() = runBlocking {
        // 回归根因：/sapi/v1/loan/... 已于 2024-04-24 退役，必须用 v2，否则 404。
        // 同时钉死真实发出的路径，并验证返回体被正确解析成 BinanceLoanDebt。
        val recorded = mutableListOf<String>()
        val interceptor = object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                recorded.add(chain.request().url.encodedPath)
                return Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        """{"rows":[{"loanCoin":"USDT","totalDebt":"50.80033811"}],"total":1}"""
                            .toResponseBody("application/json".toMediaType())
                    )
                    .build()
            }
        }
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val result = BinanceAccountAPI.getLoans("dummyKey", "dummySecret", client)

        assertEquals("应发出 1 个借币接口请求", 1, recorded.size)
        assertEquals(
            "借币接口应为 /sapi/v2/loan/flexible/ongoing/orders",
            "/sapi/v2/loan/flexible/ongoing/orders",
            recorded[0]
        )
        assertTrue(
            "SAPI 绝不能带 /api/v3 前缀（拼上去路径不存在→404）",
            recorded.none { it.contains("/api/v3/") }
        )
        // 返回体被正确解析；失败时 failures 为空、warning 为 null
        assertEquals(1, result.debts.size)
        assertEquals("USDT", result.debts[0].asset)
        assertEquals(50.80033811, result.debts[0].debt, 1e-9)
        assertTrue(result.failures.isEmpty())
        assertEquals(null, result.warning)
    }

    @Test
    fun 借币在途超过单页上限时翻页拉全() = runBlocking {
        // 与 earn 同源的潜在截断 bug：旧 fetchLoanPage 写死 current=1，借币 > 100 会被截断。
        // 这里造满一页 100 + 第 2 页 1，total=101，验证翻页后拿到 101 笔。
        val pageSize = 100
        val recordedCurrent = CopyOnWriteArrayList<Int>()
        val page1 = buildString {
            append("{\"rows\":[")
            append(
                (0 until pageSize).joinToString(",") {
                    """{"loanCoin":"USDT","totalDebt":"0.1","collateralCoin":"BNB","collateralAmount":"0.01"}"""
                }
            )
            append("],\"total\":101}")
        }
        val page2 = """{"rows":[{"loanCoin":"USDT","totalDebt":"0.1"}],"total":101}"""
        val client = OkHttpClient.Builder()
            .addInterceptor(object : Interceptor {
                override fun intercept(chain: Interceptor.Chain): Response {
                    val url = chain.request().url
                    val q = url.query ?: ""
                    val current = q.substringAfter("current=").substringBefore("&").toIntOrNull() ?: 1
                    recordedCurrent.add(current)
                    val body = if (current <= 1) page1 else page2
                    return Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                }
            })
            .build()

        val result = BinanceAccountAPI.getLoans("dummyKey", "dummySecret", client)
        assertTrue("翻页必须发出 current=2", recordedCurrent.contains(2))
        assertTrue(result.failures.isEmpty())
        assertEquals("100 + 1 = 101 笔负债", 101, result.debts.size)
    }

    /**
     * 借币接口也走单页重试：弱网下一次超时不该把整块负债丢掉。
     *
     * 工程师的重试用例只覆盖了理财（earn）路径，借币路径（`fetchLoanSinglePage` +
     * `retryPageRequest`）**没有任何用例**。漏这条的后果特别严重：借币失败会降级成
     * 「空负债」，净资产直接虚高整笔欠款（真机 USDT 借币 50.80），而且是静默的。
     */
    @Test
    fun 借币单页超时后重试成功_负债不丢() = runBlocking {
        val attempts = AtomicInteger(0)
        val client = OkHttpClient.Builder()
            .addInterceptor(object : Interceptor {
                override fun intercept(chain: Interceptor.Chain): Response {
                    if (!chain.request().url.encodedPath.contains("/loan/")) {
                        return jsonResponse(chain.request(), """{"rows":[],"total":0}""")
                    }
                    // 首次超时，重试时返回真机那笔 USDT 借币
                    if (attempts.incrementAndGet() == 1) {
                        throw SocketTimeoutException("timeout")
                    }
                    return jsonResponse(
                        chain.request(),
                        """{"rows":[{"loanCoin":"USDT","totalDebt":"50.80033811"}],"total":1}"""
                    )
                }
            })
            .build()

        val result = BinanceAccountAPI.getLoans("dummyKey", "dummySecret", client, retryDelaysMs = listOf(0L, 0L))

        assertEquals("借币单页也应重试：首次超时 + 重试成功 = 2 次请求", 2, attempts.get())
        assertTrue("重试救回来之后不该降级成空负债：实际=${result.failures}", result.failures.isEmpty())
        assertEquals(1, result.debts.size)
        assertEquals(50.80033811, result.debts[0].debt, 1e-9)
    }

    @Test
    fun 借币接口失败时降级_不抛且warning非空() = runBlocking {
        // 缺 Loan 读取权限 / 接口下线 / 地区限制都属常态，绝不能让整个导入崩溃。
        // 失败应降级为空负债 + 非空 warning，交预览页提示。
        val interceptor = object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                return Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(404)
                    .message("Not Found")
                    .body(
                        """{"code":-2015,"msg":"Invalid API-key, IP, or permissions for action."}"""
                            .toResponseBody("application/json".toMediaType())
                    )
                    .build()
            }
        }
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val result = BinanceAccountAPI.getLoans("dummyKey", "dummySecret", client)

        // 关键：不抛异常、降级为空负债
        assertEquals(0, result.debts.size)
        assertEquals(1, result.failures.size)
        assertTrue("失败原因应带回预览页提示", result.warning != null)
        assertTrue(result.warning!!.contains("借币负债没拉到"))
    }
}
