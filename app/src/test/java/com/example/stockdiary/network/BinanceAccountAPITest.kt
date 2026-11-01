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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList

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

    // ---------- 借币（Flexible Loan）负债接口 ----------

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
