package com.example.stockdiary.network

import com.example.stockdiary.network.FundDividendApiService
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 天天基金 fhsp 整源熔断（通用失败累计触发 + 指数退避 + 抖动 + 两档并发）的回归。
 *
 * 钉的契约：
 * - 连续 [FHSP_FAIL_THRESHOLD]=5 次通用失败才熔断（单发/4 发不误熔）；
 * - 514/429、DNS 这两种「确定性单一原因」短退避**不计入**整源熔断（正交）；
 * - 熔断期间顶部短路，绝不发起网络请求；
 * - 冷却过期后的「探测失败」会重新熔断且退避档位 +1（指数增长，封顶 10min）；
 * - 一次真正成功即自愈，清零计数与退避档位（源已恢复不背惩罚）；
 * - 顶部短路在 [getFundDividendHistory] 里生效，命中后连注入的「爆炸客户端」都不会被触达。
 *
 * [FHSP_FAIL_THRESHOLD] 等常量是 private，这里用字面量对齐；如改阈值需同步此文件。
 */
class FundDividendApiServiceFhspBreakerTest {

    private val service = FundDividendApiService()

    /** 真分红页 fixture（含「权益登记日」表头，isRealDividendPage=true） */
    private val fundHtml = """
        <table>
          <tr><th>年份</th><th>权益登记日</th><th>除息日</th><th>每10份分红</th><th>分红发放日</th></tr>
          <tr><td>2023</td><td>2023-06-05</td><td>2023-06-06</td><td>1.20</td><td>2023-06-09</td></tr>
        </table>
    """.trimIndent()

    @Before
    fun reset() {
        FundDividendApiService.fhspResetForTesting()
        FundDividendApiService.rateLimitedUntil = 0L
        FundDividendApiService.hostUnreachableUntil = 0L
    }

    @After
    fun resetAfter() {
        FundDividendApiService.fhspResetForTesting()
        FundDividendApiService.rateLimitedUntil = 0L
        FundDividendApiService.hostUnreachableUntil = 0L
    }

    // ---------- 状态机（直接驱动内部函数，不走网络） ----------

    @Test
    fun `fhsp 单发通用失败不熔断_仅计数`() {
        FundDividendApiService.fhspOnFailure(1000L)
        assertEquals("单发失败不熔断，相位仍 CLOSED", false, FundDividendApiService.fhspIsOpenForTesting())
        assertEquals(1, FundDividendApiService.fhspConsecutiveForTesting())
        assertFalse(FundDividendApiService.fhspDegraded)
    }

    @Test
    fun `fhsp 连续5次通用失败熔断_OPEN`() {
        repeat(5) { FundDividendApiService.fhspOnFailure(1000L) }
        assertEquals(true, FundDividendApiService.fhspIsOpenForTesting())
        assertTrue("熔断期间应粘死（降级串行）", FundDividendApiService.fhspDegraded)
        // 退避首档 ≈ 30s（+ 抖动），blockedUntil 落在此范围内
        val backoff = FundDividendApiService.fhspBlockedUntil - 1000L
        assertTrue("首档退避应 >= 30s", backoff >= 30_000L)
        assertTrue("首档退避应 <= 30s + 30% 抖动", backoff <= 39_000L + 1_000L)
    }

    @Test
    fun `fhsp 成功自愈_清零计数与退避档位`() {
        repeat(5) { FundDividendApiService.fhspOnFailure(1000L) }
        assertTrue(FundDividendApiService.fhspDegraded)
        FundDividendApiService.fhspOnSuccess()
        assertEquals(false, FundDividendApiService.fhspIsOpenForTesting())
        assertEquals(0, FundDividendApiService.fhspConsecutiveForTesting())
        assertEquals(0, FundDividendApiService.fhspBackoffHitsForTesting())
        assertFalse("自愈后恢复健康档（非降级）", FundDividendApiService.fhspDegraded)
    }

    @Test
    fun `fhsp 冷却后探测失败重新熔断_退避指数增长`() {
        val t0 = 1_000_000L
        // 首次熔断（5 连失败）
        repeat(5) { FundDividendApiService.fhspOnFailure(t0) }
        assertEquals(1, FundDividendApiService.fhspBackoffHitsForTesting())
        val b1 = FundDividendApiService.fhspBlockedUntil - t0

        // 冷却过期后探测失败 → 重新熔断，档位 +1（≈60s）
        val t1 = FundDividendApiService.fhspBlockedUntil + 1_000L
        FundDividendApiService.fhspOnFailure(t1)
        assertEquals(2, FundDividendApiService.fhspBackoffHitsForTesting())
        val b2 = FundDividendApiService.fhspBlockedUntil - t1
        assertTrue("第二档退避应大于首档（指数增长）", b2 > b1)

        // 再失败一次 → 第三档（≈120s）
        val t2 = FundDividendApiService.fhspBlockedUntil + 1_000L
        FundDividendApiService.fhspOnFailure(t2)
        assertEquals(3, FundDividendApiService.fhspBackoffHitsForTesting())
        val b3 = FundDividendApiService.fhspBlockedUntil - t2
        assertTrue("第三档退避应大于第二档", b3 > b2)
    }

    @Test
    fun `fhsp 退避封顶10min`() {
        val t0 = 1_000_000L
        repeat(5) { FundDividendApiService.fhspOnFailure(t0) }
        // 反复重探失败，把档位推到远超封顶所需的次数；逐次记录「本次实际应用的退避」
        var t = FundDividendApiService.fhspBlockedUntil + 1_000L
        var lastBackoff = 0L
        repeat(20) {
            FundDividendApiService.fhspOnFailure(t)
            // 本次 fhspOnFailure 把 blockedUntil 推到 now(=t) + backoff，
            // 故本次退避 = blockedUntil - t（在更新 t 之前量）。
            lastBackoff = FundDividendApiService.fhspBlockedUntil - t
            t = FundDividendApiService.fhspBlockedUntil + 1_000L
        }
        // 高档位时单次退避应封顶在 10min + 30% 抖动以内
        assertTrue("退避应封顶在 10min 内（含抖动）", lastBackoff <= 600_000L + 600_000L / 10 * 3 + 2_000L)
        // 钉死 #2 溢出回归：旧实现用 `Int shl shift`，shift 较大时溢出为负，
        // blockedUntil 落到过去、熔断冷却形同虚设。退避必须严格为正。
        assertTrue("退避必须为正（防 Int 溢出变负，否则熔断失效）", lastBackoff > 0)
    }

    @Test
    fun `fhspIsBlocked 冷却内true_冷却后false`() {
        repeat(5) { FundDividendApiService.fhspOnFailure(1_000L) }
        assertTrue(FundDividendApiService.fhspIsBlocked(1_000L + 1_000L))      // 冷却内
        assertFalse(FundDividendApiService.fhspIsBlocked(1_000L + 600_000L))   // 远超冷却
    }

    // ---------- 接线（getFundDividendHistory，假客户端，不真出网） ----------

    private fun okHttpClientReturning(code: Int, body: String): OkHttpClient {
        val resp = Response.Builder()
            .request(Request.Builder().url("https://fundf10.eastmoney.com/fhsp_110011.html").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "err")
            .body(ResponseBody.create("text/html; charset=utf-8".toMediaTypeOrNull(), body))
            .build()
        val call = mockk<Call>()
        every { call.execute() } returns resp
        val client = mockk<OkHttpClient>(relaxed = true)
        every { client.newCall(any()) } returns call
        return client
    }

    @Test
    fun `getFundDividendHistory 命中514不计入整源熔断_相位仍CLOSED`() = runBlocking {
        service.client = okHttpClientReturning(514, "")
        val result = service.getFundDividendHistory("110011")
        assertTrue("命中 514 应失败", result.isFailure)
        // 514 由 rateLimitedUntil 单独处理，绝不能把整源熔断相位翻成 OPEN
        assertEquals(false, FundDividendApiService.fhspIsOpenForTesting())
        assertEquals(0, FundDividendApiService.fhspConsecutiveForTesting())
    }

    @Test
    fun `getFundDividendHistory 整源熔断期间顶部短路不发起网络`() = runBlocking {
        // 用当前时间触发熔断（冷却进行中）
        repeat(5) { FundDividendApiService.fhspOnFailure(System.currentTimeMillis()) }
        assertTrue(FundDividendApiService.fhspIsBlocked(System.currentTimeMillis()))

        // 爆炸客户端：任何网络调用都抛错，用于证明短路确实拦在请求之前
        val explodingClient = mockk<OkHttpClient>(relaxed = true)
        every { explodingClient.newCall(any()) } throws AssertionError("熔断期间不应发起网络请求")
        service.client = explodingClient

        val result = service.getFundDividendHistory("110011")
        assertTrue("熔断期间应直接失败", result.isFailure)
    }

    @Test
    fun `getFundDividendHistory 200成功自愈整源熔断`() = runBlocking {
        // 用「过去时间」触发熔断，使冷却早已过期 → fhspIsBlocked 放行真实抓取，
        // 从而验证成功路径会调用 fhspOnSuccess 自愈。
        repeat(5) { FundDividendApiService.fhspOnFailure(0L) }
        assertEquals(true, FundDividendApiService.fhspIsOpenForTesting())
        assertTrue(FundDividendApiService.fhspDegraded)

        service.client = okHttpClientReturning(200, fundHtml)
        val result = service.getFundDividendHistory("110011")
        assertTrue("200 真分红页应成功", result.isSuccess)
        // 成功即自愈：相位回到 CLOSED、降级解除（证明 getFundDividendHistory 确实喂了 fhspOnSuccess）
        assertEquals(false, FundDividendApiService.fhspIsOpenForTesting())
        assertFalse(FundDividendApiService.fhspDegraded)
        assertEquals(0, FundDividendApiService.fhspConsecutiveForTesting())
    }

    @Test
    fun `getFundDividendHistory 命中404按无分红返回_不计入整源熔断`() = runBlocking {
        // 404 = 该基金代码在天天基金没有 fhsp 页（确定性结论，非源健康度问题）。
        // 修正 #4 后必须：按无分红成功返回空列表，且绝不把整源熔断相位翻成 OPEN。
        service.client = okHttpClientReturning(404, "")
        val result = service.getFundDividendHistory("110011")
        assertTrue("404 应视为无分红（成功空列表）", result.isSuccess)
        val list = result.getOrNull()
        assertNotNull("成功结果不应为 null", list)
        assertTrue("404 按无分红处理应为空列表", list!!.isEmpty())
        // 404 是确定性「无此页」，绝不能计入整源熔断
        assertEquals(false, FundDividendApiService.fhspIsOpenForTesting())
        assertEquals(0, FundDividendApiService.fhspConsecutiveForTesting())
    }
}
