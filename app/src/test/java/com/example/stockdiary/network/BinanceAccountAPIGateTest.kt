package com.example.stockdiary.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * QA 独立验收：熔断器（[BinanceHostGate]）**只保护公开行情镜像**，账户路径**不碰**它。
 *
 * ## 为什么要这组测试
 *
 * 公开行情已改走 `data-api.binance.vision`（不做地区封锁），而账户签名 `/account`
 * 只能走 `api.binance.com`。两台主机**可达性不同**：封锁地区里账户必失败、镜像行情却可达。
 * 若账户失败也喂进同一个熔断器，封锁地区每次导入都会把「能用的镜像行情」一并熔断短路，
 * 等于白修。所以本组钉死两条相反的契约：
 *
 *  - **取价路径（[BinanceAccountAPI.getPrices]，走镜像）** 仍然喂 gate：
 *    网络失败逐次 1:1 喂 [BinanceHostGate.onNetworkFailure]，累计到
 *    [BinanceHostGate.FAIL_THRESHOLD] 熔断；2xx/400（主机可达）喂 [BinanceHostGate.onSuccess] 清零。
 *  - **账户路径（[BinanceAccountAPI.getAccount]，走 api.binance.com）** **绝不喂 gate**：
 *    无论网络失败 / 451 地区封锁 / 200 成功 / 畸形 JSON，**gate 计数与相位一律不动**，
 *    账户的失败直接透传给导入流程提示用户。
 *
 * 另加四条契约：
 *
 *  1. 取价网络级失败逐次喂 gate，阈值 → OPEN（1:1，不漏接、不重复记）；
 *  2. 取价拿到 HTTP 响应（2xx 或 400）即喂 onSuccess 复位——「主机可达」不因业务错误被误判；
 *  3. 账户的任意结果都不碰 gate（核心解耦属性，防封锁地区误伤镜像行情）；
 *  4. [CancellationException] 原样抛回、不记账（两条路径都适用）。
 *
 * 跨路径用例钉死「账户的失败不计入公开行情 gate」：3 次账户 + 2 次取价，gate 只累加取价的 2 次。
 *
 * ## 全局状态
 *
 * [BinanceHostGate] 是进程内 object：本组用例会真的把闸门打到 OPEN，若不做复位，
 * 熔断状态会漏给同一 JVM 里的后续测试类（Gradle 默认单 JVM 跑全部单测）。
 * 故 `@Before` / `@After` 双向 `resetForTesting`（与 [BinanceHostGateTest] 同惯例）。
 */
class BinanceAccountAPIGateTest {

    /** 演示密钥，非真实机密：账户接口的 URL 拼接 / 签名都会用到，但请求不出网 */
    private val key = "testKey"
    private val secret = "testSecret"

    @Before
    fun resetGate() {
        BinanceHostGate.resetForTesting()
    }

    @After
    fun resetGateAfter() {
        // 与 @Before 对称：本组会把闸门打到 OPEN，绝不能把「暂不可达」漏给别的测试类
        BinanceHostGate.resetForTesting()
    }

    // ---------- 假 Interceptor：模拟币安的各类「网络语义」 ----------

    /**
     * 模拟「网络级不可达」：连不上 / 域名解析不了。
     *
     * OkHttp 对这类故障抛的就是 IOException 家族（[java.net.UnknownHostException] /
     * [java.net.ConnectException] / [java.net.SocketTimeoutException]），
     * 这里抛 UnknownHostException 正是真实场景里「DNS 解析不出来」的那一种。
     */
    private class NetworkDownInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            throw java.net.UnknownHostException("api.binance.com")
        }
    }

    /** 模拟协程取消：直接在请求链路上抛 [CancellationException] */
    private class CancellingInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            throw CancellationException("测试：协程被取消")
        }
    }

    /**
     * 模拟账户接口 `GET /api/v3/account` 的应答。
     *
     * @param httpCode HTTP 状态码
     * @param body     响应体；默认是一份合法的两条余额（BTC + USDT）
     */
    private class AccountInterceptor(
        private val httpCode: Int,
        private val body: String = DEFAULT_BALANCES
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            return Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(httpCode)
                .message(if (httpCode == 200) "OK" else "Client Error")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }

        companion object {
            /** 合法响应：free/locked 在币安响应里是字符串，这里照真实格式给 */
            const val DEFAULT_BALANCES =
                """{"balances":[{"asset":"BTC","free":"0.50000000","locked":"0.10000000"},""" +
                    """{"asset":"USDT","free":"1000.00000000","locked":"0.00000000"}]}"""

            /** 地区封锁：币安按条款 "b. Eligibility" 挡部分地区，常回 451 + 这段 msg */
            const val GEO_BLOCKED_BODY =
                """{"code":0,"msg":"Service unavailable from a restricted location."}"""

            /** 畸形响应：200 但不是 JSON，解析必然抛 JSONException（非 IO 异常） */
            const val MALFORMED_BODY = "这不是JSON"
        }
    }

    /**
     * 模拟公开取价 `GET /ticker/price?symbols=[...]` 的应答。
     *
     * 只保留本次需要的两种：整批 400（币安「一个符号非法 → 整批失败」）与正常 200。
     */
    private class PriceInterceptor(private val httpCode: Int) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val body = if (httpCode == 200) {
                """[{"symbol":"BTCUSDT","price":"67000.00"}]"""
            } else {
                """{"code":-1121,"msg":"Invalid symbol"}"""
            }
            return Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(httpCode)
                .message(if (httpCode == 200) "OK" else "Bad Request")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }

    /** 造一个只挂 [interceptor] 的假 client（不连网、不复用真实连接池） */
    private fun fakeClient(interceptor: Interceptor): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(interceptor).build()

    /** 连打 [times] 次「账户接口连不上」，用于把闸门推到阈值边缘 */
    private suspend fun failAccountTimes(times: Int) {
        val client = fakeClient(NetworkDownInterceptor())
        repeat(times) {
            val result = BinanceAccountAPI.getAccount(key, secret, client)
            assertTrue("连不上时必须返回 Result.failure", result.isFailure)
            // 顺带钉死文案没被改动：连接类失败要换成一句人话，不是 OkHttp 的原始 message
            assertTrue(
                "连接失败要给一句人话，实际是：${result.exceptionOrNull()?.message}",
                result.exceptionOrNull()?.message?.contains("连不上币安服务器") == true
            )
        }
    }

    /** 连打 [times] 次「批量取价连不上」 */
    private suspend fun failPricesTimes(times: Int) {
        val client = fakeClient(NetworkDownInterceptor())
        repeat(times) {
            // 取价失败降级为空 map（绝不阻断导入）——公开语义，一并钉住
            assertTrue(BinanceAccountAPI.getPrices(listOf("BTCUSDT"), client).isEmpty())
        }
    }

    // ---------- 契约 3（账户路径）：账户的任何结果都不碰 gate ----------

    @Test
    fun `getAccount 连不上也不喂 gate_不会误熔断公开行情`() = runBlocking {
        // 账户接口只走 api.binance.com，与公开行情镜像 data-api.binance.vision 可达性不同。
        // 即便账户连不上 5+ 次，也绝不能把公开行情的熔断器顶开——否则封锁地区里账户一失败
        // 就把能用的镜像行情也熔断短路（等于白修）。
        failAccountTimes(BinanceHostGate.FAIL_THRESHOLD + 3)
        assertEquals(Phase.CLOSED, BinanceHostGate.phaseForTesting())
        assertEquals(0, BinanceHostGate.consecutiveFailuresForTesting())
        assertFalse("账户失败不得显示「币安暂不可达」", BinanceHostGate.isUnavailable())
    }

    @Test
    fun `getAccount 成功不影响 gate`() = runBlocking {
        // 先把 gate 用「取价」推到 4（取价走镜像、会喂 gate），确认起点
        failPricesTimes(4)
        assertEquals(4, BinanceHostGate.consecutiveFailuresForTesting())

        val result = BinanceAccountAPI.getAccount(
            key, secret, fakeClient(AccountInterceptor(200))
        )
        assertTrue("合法余额应解析成功", result.isSuccess)
        val balances = result.getOrNull()!!
        assertEquals(2, balances.size)
        // 可用 0.5 + 冻结 0.1 = 0.6：total 的口径一并钉住
        assertEquals(0.6, balances.first { it.asset == "BTC" }.total, 1e-9)

        // 账户成功**不**碰 gate：计数保持取价推到的 4，不被清零也不增加
        assertEquals(4, BinanceHostGate.consecutiveFailuresForTesting())
        assertEquals(Phase.CLOSED, BinanceHostGate.phaseForTesting())
    }

    @Test
    fun `getAccount 收451仍失败但不喂gate`() = runBlocking {
        // 前置：把 gate 用取价推到 4
        failPricesTimes(4)
        assertEquals(4, BinanceHostGate.consecutiveFailuresForTesting())

        val result = BinanceAccountAPI.getAccount(
            key, secret, fakeClient(AccountInterceptor(451, AccountInterceptor.GEO_BLOCKED_BODY))
        )
        // 业务上仍是失败（用户要看到地区限制的排障提示），文案不许改
        assertTrue(result.isFailure)
        assertTrue(
            "451 要给可操作的地区提示，实际是：${result.exceptionOrNull()?.message}",
            result.exceptionOrNull()?.message?.contains("代理/VPN") == true
        )
        // 关键解耦点：账户 451 不得喂 gate——否则封锁地区里每次导入都把镜像行情熔断。
        // 取价推到的 4 条计数保持不变，gate 仍 CLOSED。
        assertEquals(Phase.CLOSED, BinanceHostGate.phaseForTesting())
        assertFalse("账户 451 不得显示「币安暂不可达」", BinanceHostGate.isUnavailable())
        assertEquals(4, BinanceHostGate.consecutiveFailuresForTesting())
    }

    @Test
    fun `getAccount 遇到畸形JSON也返回失败且不碰gate`() = runBlocking {
        val result = BinanceAccountAPI.getAccount(
            key, secret, fakeClient(AccountInterceptor(200, AccountInterceptor.MALFORMED_BODY))
        )
        assertTrue("畸形响应应转成 Result.failure 而非抛出去", result.isFailure)

        // 账户 200 + 畸形 body：解析失败转 Result.failure，但账户路径不碰 gate，
        // 所以 gate 计数保持 0（既不因 200 清零、也不因解析失败记账）。
        assertEquals(0, BinanceHostGate.consecutiveFailuresForTesting())
        assertEquals(Phase.CLOSED, BinanceHostGate.phaseForTesting())
    }

    // ---------- 契约 1/2 在批量取价路径上同样成立 ----------

    @Test
    fun `getPrices 连不上时逐次喂 gate 并在第5次熔断`() = runBlocking {
        // 4 次 = 4 次记账：fetchBatch 与 getPrices 不得重复记一笔（否则这里是 8）
        failPricesTimes(BinanceHostGate.FAIL_THRESHOLD - 1)
        assertEquals(Phase.CLOSED, BinanceHostGate.phaseForTesting())
        assertEquals(
            "一次 getPrices 只应记一次网络失败（重复记账则计数会翻倍）",
            BinanceHostGate.FAIL_THRESHOLD - 1,
            BinanceHostGate.consecutiveFailuresForTesting()
        )

        failPricesTimes(1)
        assertEquals(Phase.OPEN, BinanceHostGate.phaseForTesting())
        assertTrue(BinanceHostGate.isUnavailable())
        assertEquals(1, BinanceHostGate.blockCountForTesting())
    }

    @Test
    fun `getPrices 整批400也算网络级成功并清零计数`() = runBlocking {
        failPricesTimes(4)
        // 前置哨兵（同上）：确认计数真被推到 4，否则「漏接 gate」时会空转绿灯
        assertEquals(4, BinanceHostGate.consecutiveFailuresForTesting())

        // 一个非法符号 → 整批 400 → 对半重试到单条 → 丢弃，结果为空 map。
        // 关键：400 是主机应答了，必须喂 onSuccess，绝不能记成网络失败。
        val prices = BinanceAccountAPI.getPrices(
            listOf("BTCUSDT"), fakeClient(PriceInterceptor(400))
        )
        assertTrue("非法符号取不到价，应为空 map", prices.isEmpty())
        assertEquals(
            "整批 400 是主机可达，必须复位计数",
            0,
            BinanceHostGate.consecutiveFailuresForTesting()
        )
        failPricesTimes(4)
        assertEquals(Phase.CLOSED, BinanceHostGate.phaseForTesting())
    }

    // ---------- 跨路径：账户的失败不计入公开行情 gate（本次解耦的核心目的） ----------

    @Test
    fun `账户失败不计入公开行情gate_只有取价失败才累计`() = runBlocking {
        // 3 次账户路径失败：账户不碰 gate，计数应为 0
        failAccountTimes(3)
        assertEquals(0, BinanceHostGate.consecutiveFailuresForTesting())

        // 2 次取价路径失败：取价走镜像、会喂 gate，合计只有这 2 次
        failPricesTimes(2)
        assertEquals(2, BinanceHostGate.consecutiveFailuresForTesting())
        assertEquals(Phase.CLOSED, BinanceHostGate.phaseForTesting())
        assertFalse(BinanceHostGate.isUnavailable())
    }

    @Test
    fun `公开行情gate打开时账户成功不提前解封`() = runBlocking {
        // 用取价把 gate 顶开（取价 5 次失败），模拟「镜像行情真连不上」
        failPricesTimes(BinanceHostGate.FAIL_THRESHOLD)
        assertEquals(Phase.OPEN, BinanceHostGate.phaseForTesting())

        // 冷却期内账户路径成功应答：账户不碰 gate，所以不得解封公开行情的熔断
        // （解封只认冷却自然过去或半开探测成功）。这钉死「账户成功不会误关镜像熔断」。
        val result = BinanceAccountAPI.getAccount(
            key, secret, fakeClient(AccountInterceptor(200))
        )
        assertTrue(result.isSuccess)
        assertEquals("账户成功不得解封公开行情的熔断", Phase.OPEN, BinanceHostGate.phaseForTesting())
        assertTrue(BinanceHostGate.isUnavailable())
        assertFalse(BinanceHostGate.tryAcquireProbe(System.currentTimeMillis()))
    }

    // ---------- 契约 4：CancellationException 原样抛回、不记账 ----------

    @Test
    fun `getAccount 的 CancellationException 原样抛回且不喂gate`() = runBlocking {
        try {
            BinanceAccountAPI.getAccount(key, secret, fakeClient(CancellingInterceptor()))
            fail("取消必须原样抛回，不能被 catch(Exception) 吞成 Result.failure")
        } catch (e: CancellationException) {
            // 预期：取消穿透 getAccount，调用方（viewModel）才能靠它复位进度态
        }

        assertEquals(
            "协程取消不是「主机不可达」，绝不能被记成网络失败",
            0,
            BinanceHostGate.consecutiveFailuresForTesting()
        )
        assertEquals(Phase.CLOSED, BinanceHostGate.phaseForTesting())
        assertEquals(0, BinanceHostGate.blockCountForTesting())
    }

    @Test
    fun `getPrices 的 CancellationException 原样抛回且不喂gate`() = runBlocking {
        try {
            BinanceAccountAPI.getPrices(listOf("BTCUSDT"), fakeClient(CancellingInterceptor()))
            fail("取消必须原样抛回，不能被吞成空 map")
        } catch (e: CancellationException) {
            // 预期
        }

        assertEquals(
            "协程取消不是「主机不可达」，绝不能被记成网络失败",
            0,
            BinanceHostGate.consecutiveFailuresForTesting()
        )
        assertEquals(Phase.CLOSED, BinanceHostGate.phaseForTesting())
        assertEquals(0, BinanceHostGate.blockCountForTesting())
    }
}
