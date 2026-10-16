package com.example.stockdiary.data

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
}
