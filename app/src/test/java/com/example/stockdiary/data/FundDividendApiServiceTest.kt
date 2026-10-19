package com.example.stockdiary.data

import com.example.stockdiary.network.FundDividendApiService
import com.example.stockdiary.network.isRealDividendPage
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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 场外基金分红 API 服务 [FundDividendApiService] 的回归（解析 + 单位换算 + 网络层降级）。
 *
 * `client` 提为 `internal var` 注入 MockK 客户端；[isRealDividendPage] 与
 * [FundDividendApiService.parseFundDividendAmount] 本就是 internal，可直接单测。
 * 用本地 HTML fixture 覆盖：分红表解析、每份/每10份单位换算、以及「拿到真分红页即成功、
 * 否则走反爬页降级」的判定。
 */
class FundDividendApiServiceTest {

    private val service = FundDividendApiService()

    private val fundHtml = """
        <table>
          <tr><th>年份</th><th>权益登记日</th><th>除息日</th><th>每10份分红</th><th>分红发放日</th></tr>
          <tr><td>2023</td><td>2023-06-05</td><td>2023-06-06</td><td>1.20</td><td>2023-06-09</td></tr>
        </table>
    """.trimIndent()

    // ---------- 解析 ----------

    @Test
    fun `parseDividendHtml 解析基金分红表_含分红发放日`() {
        val records = service.parseDividendHtml(fundHtml, "110011")
        assertEquals(1, records.size)
        val r = records[0]
        assertEquals("2023-06-06", r.exDividendDate)
        assertEquals("1.2", r.dividendPerShare)
        assertEquals("2023-06-05", r.announceDate)
        assertEquals("2023", r.fiscalYear)
        assertEquals("2023-06-09", r.paymentDate)
    }

    @Test
    fun `parseDividendHtml 脏数据降级为空不崩`() {
        assertTrue(service.parseDividendHtml("<html><body>暂无分红信息!</body></html>", "110011").isEmpty())
        assertTrue(service.parseDividendHtml("", "110011").isEmpty())
    }

    // ---------- 单位换算 ----------

    @Test
    fun `parseFundDividendAmount 每10份直接取值`() {
        assertEquals(0.2, service.parseFundDividendAmount("每10份派现金0.2000元"), 1e-9)
        assertEquals(1.5, service.parseFundDividendAmount("每10份派现金1.5元"), 1e-9)
    }

    @Test
    fun `parseFundDividendAmount 每份换算成每10份`() {
        assertEquals(0.2, service.parseFundDividendAmount("每份派现金0.0200元"), 1e-9)
    }

    @Test
    fun `parseFundDividendAmount 空或无法解析返回 0`() {
        assertEquals(0.0, service.parseFundDividendAmount(""), 1e-9)
        assertEquals(0.0, service.parseFundDividendAmount("abc"), 1e-9)
    }

    // ---------- 是否真分红页 ----------

    @Test
    fun `isRealDividendPage 有权益登记日表头为真`() {
        assertTrue(isRealDividendPage("<table><tr><th>权益登记日</th></tr></table>"))
    }

    @Test
    fun `isRealDividendPage 暂无分红信息也为真`() {
        assertTrue(isRealDividendPage("本基金暂无分红信息!"))
    }

    @Test
    fun `isRealDividendPage 无表头无提示为假`() {
        assertFalse(isRealDividendPage("<html><body>反爬验证页面</body></html>"))
    }

    // ---------- 网络层（拿到真分红页即成功） ----------

    @Test
    fun `getFundDividendHistory 真分红页 200 响应解析成功`() = runBlocking {
        val resp = Response.Builder()
            .request(Request.Builder().url("https://example.com/fhsp_110011.html").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(ResponseBody.create("text/html; charset=utf-8".toMediaTypeOrNull(), fundHtml))
            .build()
        val call = mockk<Call>()
        every { call.execute() } returns resp
        val client = mockk<OkHttpClient>(relaxed = true)
        every { client.newCall(any()) } returns call
        service.client = client

        val result = service.getFundDividendHistory("110011")

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrNull()!!.size)
        assertEquals("2023-06-06", result.getOrNull()!![0].exDividendDate)
    }

    // ---------- 限流退避（514/429）电路熔断 ----------

    /**
     * 退避期间顶部短路：把退避窗口设为未来时间，注入一个「一旦被调用就抛错」的客户端，
     * 调用 [FundDividendApiService.getFundDividendHistory] 必须直接失败，**且绝不发起任何网络请求**
     * （否则抛出 AssertionError 使测试失败）。
     */
    @Test
    fun `getFundDividendHistory 退避期间顶部短路不发起网络请求`() = runBlocking {
        // 退避窗口设为未来 60s
        FundDividendApiService.rateLimitedUntil = System.currentTimeMillis() + 60_000L

        // 伪造客户端：任何网络调用都抛错，用于证明短路确实拦在请求之前
        val explodingClient = mockk<OkHttpClient>(relaxed = true)
        every { explodingClient.newCall(any()) } throws AssertionError("退避期间不应发起网络请求")
        service.client = explodingClient

        val result = service.getFundDividendHistory("110011")

        assertTrue("退避期间应直接失败", result.isFailure)
    }

    /**
     * 命中 514 触发退避，随后调用被短路：
     * 1) 注入返回 514 的假客户端，调用一次 → 断言失败（并设下退避窗口）；
     * 2) 换上「一旦被调用就抛错」的客户端再次调用 → 必须被顶部短路，否则抛 AssertionError。
     */
    @Test
    fun `getFundDividendHistory 命中514触发退避后后续调用被短路`() = runBlocking {
        val rateLimitResp = Response.Builder()
            .request(Request.Builder().url("https://fundf10.eastmoney.com/fhsp_110011.html").build())
            .protocol(Protocol.HTTP_1_1)
            .code(514)
            .message("Frequency Capped")
            .body(ResponseBody.create("text/html; charset=utf-8".toMediaTypeOrNull(), ""))
            .build()
        val call = mockk<Call>()
        every { call.execute() } returns rateLimitResp
        val client = mockk<OkHttpClient>(relaxed = true)
        every { client.newCall(any()) } returns call
        service.client = client

        // 第一次：真实命中 514
        val first = service.getFundDividendHistory("110011")
        assertTrue("命中 514 应失败", first.isFailure)

        // 退避窗口此时已被置为未来时间
        assertTrue(
            "命中 514 应进入退避窗口",
            FundDividendApiService.rateLimitedUntil > System.currentTimeMillis()
        )

        // 第二次：换上爆炸客户端，必须被短路（若触达网络则抛 AssertionError 使测试失败）
        val explodingClient = mockk<OkHttpClient>(relaxed = true)
        every { explodingClient.newCall(any()) } throws AssertionError("退避期间不应再次发起网络请求")
        service.client = explodingClient

        val second = service.getFundDividendHistory("110011")
        assertTrue("退避期间应被短路直接失败", second.isFailure)
    }

    @After
    fun resetBackoff() {
        // 防止退避窗口污染相邻用例
        FundDividendApiService.rateLimitedUntil = 0L
    }
}
