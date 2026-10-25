package com.example.stockdiary.data

import android.content.Context
import com.example.stockdiary.network.EastmoneyDividendApiService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 东方财富股票分红 API 服务 [EastmoneyDividendApiService] 的回归（分页组装 + 单条映射 + 网络层降级）。
 *
 * `client` 提为 `internal var` 注入 MockK 客户端；[EastmoneyDividendApiService.mapDividendItem]
 * 提为 `internal` 直接单测。依赖 testImplementation 的真 `org.json`（android.jar 的 stub
 * 会让 optString/optJSONObject 返回 null，分页恒空、映射必 NPE），否则本文件的 JSON 语义跑不起来。
 *
 * ⚠️ ticker 口径：`getDividendHistory(...)` 走 `fetchBySecurityCode(code, code)`，
 * 结果是**请求时**的代码；`getDividendHistoryBySecurityCodes(...)` 的结果 ticker 是
 * **接口返回的** SECURITY_CODE（可能带 `.SH` 之类的后缀）——两个方法不能按同一种口径断言。
 */
class EastmoneyDividendApiServiceTest {

    private val service = EastmoneyDividendApiService(mockk<Context>())

    private fun responseOf(json: String): Response =
        Response.Builder()
            .request(
                Request.Builder()
                    .url("https://datacenter-web.eastmoney.com/api/data/v1/get")
                    .build()
            )
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(ResponseBody.create("application/json".toMediaTypeOrNull(), json))
            .build()

    // ---------- 分页组装 ----------

    @Test
    fun `getDividendHistory 翻页合并两页数据并按日期降序`() = runBlocking {
        val page1 = responseOf(
            """
            {"code":0,"message":"ok","result":{"pages":2,"data":[
                {"SECURITY_CODE":"600519","EX_DIVIDEND_DATE":"2023-06-06 00:00:00","PLAN_NOTICE_DATE":"2023-05-20 00:00:00","ASSIGN_PROGRESS":"实施","PRETAX_BONUS_RMB":25.1},
                {"SECURITY_CODE":"600519","EX_DIVIDEND_DATE":"2022-06-07 00:00:00","PLAN_NOTICE_DATE":"2022-05-18 00:00:00","ASSIGN_PROGRESS":"实施","PRETAX_BONUS_RMB":21.7}
            ]}}
            """.trimIndent()
        )
        val page2 = responseOf(
            """
            {"code":0,"message":"ok","result":{"pages":2,"data":[
                {"SECURITY_CODE":"600519","EX_DIVIDEND_DATE":"2021-06-08 00:00:00","PLAN_NOTICE_DATE":"2021-05-21 00:00:00","ASSIGN_PROGRESS":"完毕","PRETAX_BONUS_RMB":17.0}
            ]}}
            """.trimIndent()
        )
        val call = mockk<Call>()
        every { call.execute() } returnsMany listOf(page1, page2)
        val client = mockk<OkHttpClient>(relaxed = true)
        every { client.newCall(any()) } returns call
        service.client = client

        val result = service.getDividendHistory("600519")

        assertTrue(result.isSuccess)
        val list = result.getOrNull()!!
        assertEquals(3, list.size)
        // 按 EX_DIVIDEND_DATE 降序：2023 → 2022 → 2021
        assertEquals("2023-06-06", list[0].date)
        assertEquals("2022-06-07", list[1].date)
        assertEquals("2021-06-08", list[2].date)
        // 逐条查询：ticker 是请求时的代码，不是接口返回的 SECURITY_CODE 字段
        assertTrue(list.all { it.ticker == "600519" })
        assertEquals(25.1, list[0].dividendPerShare, 1e-9)
        assertEquals(21.7, list[1].dividendPerShare, 1e-9)
        assertEquals(17.0, list[2].dividendPerShare, 1e-9)
        assertEquals("2023-05-20", list[0].reportDate)
        // pages=2 必须真的发第二次请求；returnsMany 只有两条，多翻一页 MockK 会直接抛错
        verify(exactly = 2) { client.newCall(any()) }
    }

    @Test
    fun `getDividendHistoryBySecurityCodes ticker 取接口返回的 SECURITY_CODE 而非请求代码`() = runBlocking {
        val resp = responseOf(
            """
            {"code":0,"result":{"pages":1,"data":[
                {"SECURITY_CODE":"SH.600519","EX_DIVIDEND_DATE":"2023-06-06 00:00:00","PLAN_NOTICE_DATE":"2023-05-20 00:00:00","ASSIGN_PROGRESS":"实施","PRETAX_BONUS_RMB":25.1}
            ]}}
            """.trimIndent()
        )
        val call = mockk<Call>()
        every { call.execute() } returns resp
        val client = mockk<OkHttpClient>(relaxed = true)
        every { client.newCall(any()) } returns call
        service.client = client

        val result = service.getDividendHistoryBySecurityCodes(listOf("600519"))

        assertTrue(result.isSuccess)
        val list = result.getOrNull()!!
        assertEquals(1, list.size)
        // 批量查询：ticker 是接口返回的 SECURITY_CODE（可能带市场后缀），调用方靠它把结果发回给对应标的
        assertEquals("SH.600519", list[0].ticker)
        assertEquals("2023-06-06", list[0].date)
        verify(exactly = 1) { client.newCall(any()) }
    }

    // ---------- 单条映射 ----------

    @Test
    fun `mapDividendItem 实施状态返回完整字段`() {
        val item = JSONObject(
            """{"EX_DIVIDEND_DATE":"2024-07-10 00:00:00","PLAN_NOTICE_DATE":"2024-06-01 00:00:00","ASSIGN_PROGRESS":"实施","PRETAX_BONUS_RMB":30.66}"""
        )

        val data = service.mapDividendItem(item, "600519")

        assertNotNull(data)
        assertEquals("600519", data!!.ticker)
        assertEquals("2024-07-10", data.date)
        assertEquals(0.0, data.dividend, 1e-9)
        assertEquals(30.66, data.dividendPerShare, 1e-9)
        assertEquals("2024-06-01", data.reportDate)
    }

    @Test
    fun `mapDividendItem 完毕状态也返回且公告期缺失时报告期为空串`() {
        val item = JSONObject(
            """{"EX_DIVIDEND_DATE":"2024-07-10","ASSIGN_PROGRESS":"完毕","PRETAX_BONUS_RMB":8.5}"""
        )

        val data = service.mapDividendItem(item, "600519")

        assertNotNull(data)
        assertEquals("2024-07-10", data!!.date)
        assertEquals(8.5, data.dividendPerShare, 1e-9)
        assertEquals("", data.reportDate)
    }

    @Test
    fun `mapDividendItem 预案返回 null`() {
        val item = JSONObject(
            """{"EX_DIVIDEND_DATE":"2024-07-10 00:00:00","PLAN_NOTICE_DATE":"2024-06-01 00:00:00","ASSIGN_PROGRESS":"预案","PRETAX_BONUS_RMB":30.66}"""
        )

        assertNull(service.mapDividendItem(item, "600519"))
    }

    @Test
    fun `mapDividendItem 每十股分红非正返回 null`() {
        val zero = JSONObject(
            """{"EX_DIVIDEND_DATE":"2024-07-10 00:00:00","ASSIGN_PROGRESS":"实施","PRETAX_BONUS_RMB":0}"""
        )
        val negative = JSONObject(
            """{"EX_DIVIDEND_DATE":"2024-07-10 00:00:00","ASSIGN_PROGRESS":"实施","PRETAX_BONUS_RMB":-5}"""
        )
        // 字段缺失时 optDouble 默认 -1.0，同样按非正过滤
        val missing = JSONObject(
            """{"EX_DIVIDEND_DATE":"2024-07-10 00:00:00","ASSIGN_PROGRESS":"实施"}"""
        )

        assertNull(service.mapDividendItem(zero, "600519"))
        assertNull(service.mapDividendItem(negative, "600519"))
        assertNull(service.mapDividendItem(missing, "600519"))
    }

    @Test
    fun `mapDividendItem 除权除息日为空返回 null`() {
        val empty = JSONObject(
            """{"EX_DIVIDEND_DATE":"","PLAN_NOTICE_DATE":"2024-06-01 00:00:00","ASSIGN_PROGRESS":"实施","PRETAX_BONUS_RMB":30.66}"""
        )
        val blank = JSONObject(
            """{"EX_DIVIDEND_DATE":"   ","PLAN_NOTICE_DATE":"2024-06-01 00:00:00","ASSIGN_PROGRESS":"实施","PRETAX_BONUS_RMB":30.66}"""
        )

        assertNull(service.mapDividendItem(empty, "600519"))
        assertNull(service.mapDividendItem(blank, "600519"))
    }

    @Test
    fun `mapDividendItem 除权除息日形态合法但日历不存在_判脏丢弃`() {
        // 2026-13-01 / 2026-00-10 月越界，2026-02-30 / 2023-02-29 日历不存在，
        // 2026-1-5 非补零——曾让消费端 month=13 炸 IndexOutOfBounds，必须在入口拦掉
        val month13 = JSONObject(
            """{"EX_DIVIDEND_DATE":"2026-13-01 00:00:00","ASSIGN_PROGRESS":"实施","PRETAX_BONUS_RMB":30.66}"""
        )
        val month00 = JSONObject(
            """{"EX_DIVIDEND_DATE":"2026-00-10","ASSIGN_PROGRESS":"实施","PRETAX_BONUS_RMB":30.66}"""
        )
        val feb30 = JSONObject(
            """{"EX_DIVIDEND_DATE":"2026-02-30","ASSIGN_PROGRESS":"实施","PRETAX_BONUS_RMB":30.66}"""
        )
        val feb29NonLeap = JSONObject(
            """{"EX_DIVIDEND_DATE":"2023-02-29","ASSIGN_PROGRESS":"实施","PRETAX_BONUS_RMB":30.66}"""
        )
        val notPadded = JSONObject(
            """{"EX_DIVIDEND_DATE":"2026-1-5","ASSIGN_PROGRESS":"实施","PRETAX_BONUS_RMB":30.66}"""
        )

        assertNull(service.mapDividendItem(month13, "600519"))
        assertNull(service.mapDividendItem(month00, "600519"))
        assertNull(service.mapDividendItem(feb30, "600519"))
        assertNull(service.mapDividendItem(feb29NonLeap, "600519"))
        assertNull(service.mapDividendItem(notPadded, "600519"))
    }

    // ---------- 降级 ----------

    @Test
    fun `getDividendHistory 接口错误码降级为空列表且只请求一次`() = runBlocking {
        val resp = responseOf("""{"code":1,"message":"param error"}""")
        val call = mockk<Call>()
        every { call.execute() } returns resp
        val client = mockk<OkHttpClient>(relaxed = true)
        every { client.newCall(any()) } returns call
        service.client = client

        val result = service.getDividendHistory("600519")

        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull()!!.isEmpty())
        verify(exactly = 1) { client.newCall(any()) }
    }

    @Test
    fun `getDividendHistory data 为空降级为空列表`() = runBlocking {
        val resp = responseOf("""{"code":0,"result":{"pages":1,"data":[]}}""")
        val call = mockk<Call>()
        every { call.execute() } returns resp
        val client = mockk<OkHttpClient>(relaxed = true)
        every { client.newCall(any()) } returns call
        service.client = client

        val result = service.getDividendHistory("600519")

        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull()!!.isEmpty())
    }

    @Test
    fun `getDividendHistory 网络异常降级为 Result failure`() = runBlocking {
        val call = mockk<Call>()
        every { call.execute() } throws IOException("network down")
        val client = mockk<OkHttpClient>(relaxed = true)
        every { client.newCall(any()) } returns call
        service.client = client

        val result = service.getDividendHistory("600519")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals("network down", result.exceptionOrNull()!!.message)
    }
}
