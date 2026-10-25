package com.example.stockdiary.data

import com.example.stockdiary.data.dividend.DividendService
import com.example.stockdiary.common.Result as AppResult
import io.mockk.coEvery
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
import java.io.IOException

/**
 * 新浪分红抓取服务 [DividendService] 的回归（解析 + 网络层降级）。
 *
 * 网络层通过把 `client` 提为 `internal var` 注入 MockK 伪造的 OkHttp 客户端，
 * 用本地 HTML fixture 与可控响应，离线跑通「解析」与「网络失败/空响应/异常 降级而非崩」。
 */
class DividendServiceTest {

    private val service = DividendService()

    private fun htmlResponse(code: Int, body: String, contentType: String = "text/html; charset=utf-8"): Response {
        return Response.Builder()
            .request(Request.Builder().url("https://example.com/dividend").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("OK")
            .body(ResponseBody.create(contentType.toMediaTypeOrNull(), body))
            .build()
    }

    private fun installClient(response: Response) {
        val call = mockk<Call>()
        every { call.execute() } returns response
        val client = mockk<OkHttpClient>(relaxed = true)
        every { client.newCall(any()) } returns call
        service.client = client
    }

    private fun installClientThrowing() {
        val call = mockk<Call>()
        every { call.execute() } throws IOException("network down")
        val client = mockk<OkHttpClient>(relaxed = true)
        every { client.newCall(any()) } returns call
        service.client = client
    }

    // 注意：parseDividendHtml 的入选条件是「表文本必须含『分红』且含『派息』或『分红方案』」，
    // 所以 fixture 里必须有「分红」二字（真实新浪页面在表格上方就有「分红配送记录」标题）。
    private val sinaHtml = """
        <table>
          <caption>分红配送记录</caption>
          <tr><th>公告日期</th><th>送股(股)</th><th>转增(股)</th>
              <th>派息(税前)(元)</th><th>进度</th><th>除权除息日</th></tr>
          <tr><td>2023-06-01</td><td>0</td><td>0</td><td>1.00</td><td>实施</td><td>2023-06-08</td></tr>
        </table>
    """.trimIndent()

    // ---------- 解析 ----------

    @Test
    fun `parseDividendHtml 解析新浪分红表`() {
        val records = service.parseDividendHtml(sinaHtml, "600519", "sh")
        assertEquals(1, records.size)
        val r = records[0]
        assertEquals("2023-06-08", r.exDividendDate)
        assertEquals("1.0", r.dividendPerShare)
        assertEquals("2023-06-01", r.announceDate)
        assertEquals("2023", r.fiscalYear)
    }

    @Test
    fun `parseDividendHtml 空脏数据降级为空不崩`() {
        assertTrue(service.parseDividendHtml("<html><body>暂无分红信息</body></html>", "600519", "sh").isEmpty())
        assertTrue(service.parseDividendHtml("", "600519", "sh").isEmpty())
    }

    @Test
    fun `parseDividendHtml 除息日脏月份行被丢弃`() {
        // 2026-13-08 形态合法但日历不存在：曾让消费端 month=13 炸 IndexOutOfBounds，
        // 现在必须在入口就丢，整表只此一行 → 解析结果为空
        val html = """
            <table>
              <caption>分红配送记录</caption>
              <tr><th>公告日期</th><th>送股(股)</th><th>转增(股)</th>
                  <th>派息(税前)(元)</th><th>进度</th><th>除权除息日</th></tr>
              <tr><td>2026-12-01</td><td>0</td><td>0</td><td>1.00</td><td>实施</td><td>2026-13-08</td></tr>
            </table>
        """.trimIndent()

        assertTrue(service.parseDividendHtml(html, "600519", "sh").isEmpty())
    }

    @Test
    fun `parseDividendHtml 除息日为空的预案行保持既有行为仍保留`() {
        // 空除息日不是脏数据：未实施的预案行可能没有除息日，既有行为是保留该行
        val html = """
            <table>
              <caption>分红配送记录</caption>
              <tr><th>公告日期</th><th>送股(股)</th><th>转增(股)</th>
                  <th>派息(税前)(元)</th><th>进度</th><th>除权除息日</th></tr>
              <tr><td>2026-12-01</td><td>0</td><td>0</td><td>1.00</td><td>预案</td><td></td></tr>
            </table>
        """.trimIndent()

        val records = service.parseDividendHtml(html, "600519", "sh")

        assertEquals(1, records.size)
        assertEquals("", records[0].exDividendDate)
    }

    // ---------- 网络层降级 ----------

    @Test
    fun `fetchDividendData 成功响应解析出分红记录`() = runBlocking {
        installClient(htmlResponse(200, sinaHtml))
        val result = service.fetchDividendData("600519", "sh")

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrNull()!!.size)
        assertEquals("2023-06-08", result.getOrNull()!![0].exDividendDate)
    }

    @Test
    fun `fetchDividendData 空响应体降级为 Error 不崩`() = runBlocking {
        installClient(htmlResponse(200, ""))
        val result = service.fetchDividendData("600519", "sh")
        assertFalse("解析到 0 条应判失败而非空成功", result.isSuccess)
    }

    @Test
    fun `fetchDividendData 非 2xx 降级为 Error 不崩`() = runBlocking {
        installClient(htmlResponse(404, "<html></html>"))
        val result = service.fetchDividendData("600519", "sh")
        assertFalse(result.isSuccess)
    }

    @Test
    fun `fetchDividendData 请求抛异常降级为 Error 不崩`() = runBlocking {
        installClientThrowing()
        val result = service.fetchDividendData("600519", "sh")
        assertFalse(result.isSuccess)
    }
}
