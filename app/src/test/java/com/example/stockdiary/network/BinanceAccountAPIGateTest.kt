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
 * QA 独立验收：账户/资产路径**确实喂了** [BinanceHostGate]。
 *
 * ## 为什么要这组测试
 *
 * 账户路径（[BinanceAccountAPI.getAccount] / [BinanceAccountAPI.getPrices]）此前从没接过
 * 主机级熔断器：连不上 api.binance.com 时，行情路径（[UnifiedPriceService.getCryptoPrice]）
 * 知道、资产路径不知道——[BinanceHostGate] 的连续失败计数缺一大半来源，
 * 「币安暂不可达」的 UI 标签（AssetCards / PortfolioSearch）也失去了一半依据。
 *
 * 本组用假 [Interceptor] 直接驱动 `getAccount` / `getPrices` 的真实请求路径，
 * 钉死四条契约——**缺哪条接线，对应的用例就红**：
 *
 *  1. 网络级失败（连不上 / 域名解析不了）：**逐次 1:1 喂 [BinanceHostGate.onNetworkFailure]**，
 *     累计到 [BinanceHostGate.FAIL_THRESHOLD] 熔断（不是不喂，也不是一次喂多下）；
 *  2. 拿到 HTTP 响应（**2xx 或 400/429/5xx 等非 451/403 的响应**）：**喂 [BinanceHostGate.onSuccess]**，
 *     连续失败计数清零——「主机可达」不因业务错误被误判成不可达；
 *     ⚠️ **例外**：451/403 地区封锁 / 访问被拒——主机可达但本用户用不了，等同「主机不可用」，
 *     必须喂 [BinanceHostGate.onNetworkFailure]（触发熔断短路），**不能**当成功清零
 *     （旧实现把它当成功，导致地区封锁用户每轮同步都被 451 刷屏、熔断永远顶不上）；
 *  3. 非 IO 异常（JSON 解析失败）：**不记账**——它不是「主机不可达」，不该顶爆熔断；
 *  4. [CancellationException]：**原样抛回、不记账**——取消不是主机不可达。
 *
 * 另有一条跨路径用例钉死「两条路径共用同一闸门」（3 次账户 + 2 次取价 = 5 次 → 熔断），
 * 这条直接对着本次改动的目的：两条路径共用一个计数器。
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

    // ---------- 契约 1：网络级失败逐次喂 gate，累计到阈值熔断 ----------

    @Test
    fun `getAccount 连不上时逐次喂 gate 并在第5次熔断`() = runBlocking {
        // 前 4 次：1:1 累计，未达阈值不熔断
        failAccountTimes(BinanceHostGate.FAIL_THRESHOLD - 1)
        assertEquals(Phase.CLOSED, BinanceHostGate.phaseForTesting())
        assertEquals(
            "每次网络失败都必须喂一次 gate（漏接则为 0）",
            BinanceHostGate.FAIL_THRESHOLD - 1,
            BinanceHostGate.consecutiveFailuresForTesting()
        )
        assertFalse("未熔断前 UI 不该显示暂不可达", BinanceHostGate.isUnavailable())

        // 第 5 次：达阈值，熔断
        failAccountTimes(1)
        assertEquals(Phase.OPEN, BinanceHostGate.phaseForTesting())
        assertTrue("熔断后 UI 应显示「币安暂不可达」", BinanceHostGate.isUnavailable())
        assertEquals(1, BinanceHostGate.blockCountForTesting())
        // 熔断瞬间计数清零（冷却期内的迟来失败重新累计）
        assertEquals(0, BinanceHostGate.consecutiveFailuresForTesting())
    }

    // ---------- 契约 2：拿到 HTTP 响应即记账成功、计数清零（451/403 除外） ----------

    @Test
    fun `getAccount 拿到响应即记账成功并清零计数`() = runBlocking {
        failAccountTimes(4)
        assertEquals(4, BinanceHostGate.consecutiveFailuresForTesting())

        val result = BinanceAccountAPI.getAccount(
            key, secret, fakeClient(AccountInterceptor(200))
        )
        assertTrue("合法余额应解析成功", result.isSuccess)
        val balances = result.getOrNull()!!
        assertEquals(2, balances.size)
        // 可用 0.5 + 冻结 0.1 = 0.6：total 的口径一并钉住
        assertEquals(0.6, balances.first { it.asset == "BTC" }.total, 1e-9)

        assertEquals("拿到响应必须喂 onSuccess 复位计数", 0, BinanceHostGate.consecutiveFailuresForTesting())
        // 真清零：再连 4 次仍不熔断（若没清零，4+4=8 早已越过阈值 5）
        failAccountTimes(4)
        assertEquals(Phase.CLOSED, BinanceHostGate.phaseForTesting())
    }

    // ---------- 契约 2a：451/403 地区封锁必须触发熔断，不能当成功清零 ----------

    @Test
    fun `getAccount 收到451地区封锁应触发熔断而非当成功`() = runBlocking {
        failAccountTimes(4)
        // 前置哨兵：先确认计数真被推到了 4。没有它，这条用例在「gate 漏接」时会空转绿灯
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
        // 关键修复点：451 是「主机可达但本用户被地区封锁」——等同主机不可用，必须喂
        // onNetworkFailure 触发熔断，而不是像旧代码那样当成功清零计数。
        // 4 次前置失败 + 这次 451 = 5 次达阈值 → 熔断 OPEN、计数清零、blockCount+1。
        assertEquals(Phase.OPEN, BinanceHostGate.phaseForTesting())
        assertTrue("451 后应显示「币安暂不可达」", BinanceHostGate.isUnavailable())
        assertEquals(1, BinanceHostGate.blockCountForTesting())
        assertEquals(0, BinanceHostGate.consecutiveFailuresForTesting())

        // 熔断后再来 4 次网络失败：冷却期内不顺延、不重复记 blockCount（仍只 1），相位保持 OPEN
        failAccountTimes(4)
        assertEquals(Phase.OPEN, BinanceHostGate.phaseForTesting())
        assertEquals(1, BinanceHostGate.blockCountForTesting())
    }

    // ---------- 契约 3：非 IO 异常不记账（不顶爆熔断） ----------

    @Test
    fun `getAccount 遇到畸形JSON不记账_非IO异常不顶爆熔断`() = runBlocking {
        failAccountTimes(2)
        assertEquals(2, BinanceHostGate.consecutiveFailuresForTesting())

        val result = BinanceAccountAPI.getAccount(
            key, secret, fakeClient(AccountInterceptor(200, AccountInterceptor.MALFORMED_BODY))
        )
        assertTrue("畸形响应应转成 Result.failure 而非抛出去", result.isFailure)

        // 记账顺序是「先 onSuccess（200 说明主机应答了），再解析 body」：
        // 所以这里的正确读数是 0（被 200 的 onSuccess 清零），而**不是** 1。
        // 若哪天有人把 `if (e is IOException)` 的判定放宽/删掉，JSON 解析异常就会被
        // 回补记成一次网络失败，计数变成 1 —— 这条正是挡住那个回归的哨兵：
        // 解析失败意味着内容不对，主机明明应答了，绝不能顶爆熔断。
        assertEquals(
            "JSON 解析失败不是「主机不可达」：200 清零后不得再回补一笔网络失败",
            0,
            BinanceHostGate.consecutiveFailuresForTesting()
        )
        assertEquals(Phase.CLOSED, BinanceHostGate.phaseForTesting())
        assertEquals(0, BinanceHostGate.blockCountForTesting())
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

    // ---------- 跨路径：两条路径共用同一闸门（本次改动的核心目的） ----------

    @Test
    fun `账户路径与批量取价路径共用同一闸门_合计5次才熔断`() = runBlocking {
        // 3 次账户路径失败
        failAccountTimes(3)
        assertEquals(Phase.CLOSED, BinanceHostGate.phaseForTesting())
        assertEquals(3, BinanceHostGate.consecutiveFailuresForTesting())

        // 2 次取价路径失败：必须与账户路径落在同一个计数器上，合计 5 次即熔断
        failPricesTimes(2)
        assertEquals(Phase.OPEN, BinanceHostGate.phaseForTesting())
        assertTrue(BinanceHostGate.isUnavailable())
        assertEquals(1, BinanceHostGate.blockCountForTesting())
    }

    @Test
    fun `熔断冷却期内账户路径拿到响应也不提前解封`() = runBlocking {
        failAccountTimes(BinanceHostGate.FAIL_THRESHOLD)
        assertEquals(Phase.OPEN, BinanceHostGate.phaseForTesting())

        // 冷却期内账户路径成功应答：按闸门设计「迟到成功不解封」（防抖动即解封），只清计数。
        // 账户路径刻意不加 tryAcquireProbe 短路——它是用户手动触发的导入，必须真发一次请求。
        val result = BinanceAccountAPI.getAccount(
            key, secret, fakeClient(AccountInterceptor(200))
        )
        assertTrue(result.isSuccess)
        assertEquals("迟到成功不得提前解封", Phase.OPEN, BinanceHostGate.phaseForTesting())
        assertTrue(BinanceHostGate.isUnavailable())
        // 解封只认冷却自然过去或半开探测成功
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
