package com.example.stockdiary.data

import com.example.stockdiary.network.FundDividendApiService
import com.example.stockdiary.network.FundDividendPageVerdict
import com.example.stockdiary.network.classifyFundDividendPage
import com.example.stockdiary.network.countDividendDataRows
import com.example.stockdiary.network.fundDividendSuspiciousMessage
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

    /** 测试用：取页面第一张表的数据行数（生产侧分母只算解析器实际读取的那张表）。 */
    private fun countFirstTableRows(html: String): Int =
        countDividendDataRows(org.jsoup.Jsoup.parse(html).select("table").first()!!)

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

    @Test
    fun `parseDividendHtml 除息日脏月份行被丢弃_合法行保留`() {
        // 2026-13-01 形态合法但日历不存在：曾让消费端 month=13 炸 IndexOutOfBounds，
        // 现在必须在入口就丢；同一表里的合法行不受影响
        val html = """
            <table>
              <tr><th>年份</th><th>权益登记日</th><th>除息日</th><th>每10份分红</th><th>分红发放日</th></tr>
              <tr><td>2026</td><td>2026-12-30</td><td>2026-13-01</td><td>1.20</td><td>2026-12-31</td></tr>
              <tr><td>2023</td><td>2023-06-05</td><td>2023-06-06</td><td>1.20</td><td>2023-06-09</td></tr>
            </table>
        """.trimIndent()

        val records = service.parseDividendHtml(html, "110011")

        assertEquals(1, records.size)
        assertEquals("2023-06-06", records[0].exDividendDate)
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

    @Test
    fun `getFundDividendHistory 有表头但解析0条时按解析可疑失败_不产生success空列表`() = runBlocking {
        // R1 端到端回归：真机日志里 79 次「HTTP 200 / 41KB / 含分红表格 / 解析 0 条」。
        // 旧逻辑把它当「确实无分红」返回 success(emptyList())，Repository 随即删旧记录 +
        // 写 7 天负缓存，年化收入在点刷新后当场缩水。现在必须失败，让上层保留旧记录。
        val headerOnly = """
            <table>
              <tr><th>年份</th><th>权益登记日</th><th>除息日</th><th>每10份分红</th><th>分红发放日</th></tr>
            </table>
        """.trimIndent()
        val resp = Response.Builder()
            .request(Request.Builder().url("https://example.com/fhsp_110011.html").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(ResponseBody.create("text/html; charset=utf-8".toMediaTypeOrNull(), headerOnly))
            .build()
        val call = mockk<Call>()
        every { call.execute() } returns resp
        val client = mockk<OkHttpClient>(relaxed = true)
        every { client.newCall(any()) } returns call
        service.client = client

        val result = service.getFundDividendHistory("110011")

        assertTrue("有表头但 0 条必须失败（保留旧分红记录），不能是 success(emptyList)", result.isFailure)
        // 解析可疑不计入整源熔断：一轮几十个空页面不该把整源静默屏蔽
        assertFalse(FundDividendApiService.fhspIsOpenForTesting())
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

    // ---------- A16 残缺解析判定（countDividendDataRows + classifyFundDividendPage） ----------

    @Test
    fun `classifyFundDividendPage 记录数远少于实际数据行_判解析可疑`() {
        // 分红表实际有 10 个数据行，却只解析出 2 条（模拟残缺解析）：必须 SUSPICIOUS_EMPTY_PARSE
        // （保留旧记录），绝不能当 HAS_RECORDS 放行去洗库 —— 否则「首次 10 条 / 二次仅 2 条」
        // 会把另外 8 条分红记录删掉，年化收入当场缩水。
        val html = """
            <table>
              <tr><th>年份</th><th>权益登记日</th><th>除息日</th><th>每10份分红</th><th>分红发放日</th></tr>
              <tr><td>2015</td><td>2015-06-01</td><td>2015-06-02</td><td>1.00</td><td>2015-06-05</td></tr>
              <tr><td>2016</td><td>2016-06-01</td><td>2016-06-02</td><td>1.10</td><td>2016-06-05</td></tr>
              <tr><td>2017</td><td>2017-06-01</td><td>2017-06-02</td><td>1.20</td><td>2017-06-05</td></tr>
              <tr><td>2018</td><td>2018-06-01</td><td>2018-06-02</td><td>1.30</td><td>2018-06-05</td></tr>
              <tr><td>2019</td><td>2019-06-01</td><td>2019-06-02</td><td>1.40</td><td>2019-06-05</td></tr>
              <tr><td>2020</td><td>2020-06-01</td><td>2020-06-02</td><td>1.50</td><td>2020-06-05</td></tr>
              <tr><td>2021</td><td>2021-06-01</td><td>2021-06-02</td><td>1.60</td><td>2021-06-05</td></tr>
              <tr><td>2022</td><td>2022-06-01</td><td>2022-06-02</td><td>1.70</td><td>2022-06-05</td></tr>
              <tr><td>2023</td><td>2023-06-01</td><td>2023-06-02</td><td>1.80</td><td>2023-06-05</td></tr>
              <tr><td>2024</td><td>2024-06-01</td><td>2024-06-02</td><td>1.90</td><td>2024-06-05</td></tr>
            </table>
        """.trimIndent()
        // 解析只解出 2 条（残缺），但 countDividendDataRows 数出 10 行 → 2 远小于 10 → 触发可疑
        assertEquals(
            FundDividendPageVerdict.SUSPICIOUS_EMPTY_PARSE,
            classifyFundDividendPage(html, recordCount = 2, expectedRowCount = countFirstTableRows(html))
        )
    }

    @Test
    fun `classifyFundDividendPage 解析完整_记录数接近实际行数_判有记录`() {
        // 解析出的记录数与实际数据行数接近（完整页），必须 HAS_RECORDS，正常落库。
        val html = """
            <table>
              <tr><th>年份</th><th>权益登记日</th><th>除息日</th><th>每10份分红</th><th>分红发放日</th></tr>
              <tr><td>2023</td><td>2023-06-05</td><td>2023-06-06</td><td>1.20</td><td>2023-06-09</td></tr>
            </table>
        """.trimIndent()
        assertEquals(
            FundDividendPageVerdict.HAS_RECORDS,
            classifyFundDividendPage(html, recordCount = 1, expectedRowCount = countFirstTableRows(html))
        )
    }

    @Test
    fun `countDividendDataRows 只数数据行不含表头与汇总行`() {
        val html = """
            <table>
              <tr><th>年份</th><th>权益登记日</th><th>除息日</th><th>每10份分红</th><th>分红发放日</th></tr>
              <tr><td>2023</td><td>2023-06-05</td><td>2023-06-06</td><td>1.20</td><td>2023-06-09</td></tr>
              <tr><td>2024</td><td>2024-06-05</td><td>2024-06-06</td><td>1.30</td><td>2024-06-09</td></tr>
              <tr><td>共 1 页</td></tr>
            </table>
        """.trimIndent()
        assertEquals(2, countFirstTableRows(html))
    }

    @Test
    fun `拆分表页面_分母只算解析器实际读取的表_不误判残缺`() {
        // 分红被拆成两张表：解析器读到第一张表产出 1 条就 break（后面的表根本不读）。
        // 若按整页所有表做分母（3 行），1 条会被误判成 SUSPICIOUS → 反复失败 + 指数退避
        // → 基金分红长期停更。分母必须与 break 位置一致，只算第一张表。
        val html = """
            <table>
              <tr><th>年份</th><th>权益登记日</th><th>除息日</th><th>每10份分红</th><th>分红发放日</th></tr>
              <tr><td>2024</td><td>2024-06-05</td><td>2024-06-06</td><td>1.30</td><td>2024-06-09</td></tr>
            </table>
            <table>
              <tr><th>年份</th><th>权益登记日</th><th>除息日</th><th>每10份分红</th><th>分红发放日</th></tr>
              <tr><td>2023</td><td>2023-06-05</td><td>2023-06-06</td><td>1.20</td><td>2023-06-09</td></tr>
              <tr><td>2022</td><td>2022-06-05</td><td>2022-06-06</td><td>1.10</td><td>2022-06-09</td></tr>
            </table>
        """.trimIndent()

        val parsed = service.parseDividendHtmlDetailed(html, "110011")
        assertEquals(1, parsed.records.size)
        // 分母 = 解析器实际读取的第一张表（1 行），不是整页 3 行
        assertEquals(1, parsed.expectedRowCount)
        assertEquals(
            FundDividendPageVerdict.HAS_RECORDS,
            classifyFundDividendPage(html, parsed.records.size, parsed.expectedRowCount)
        )
    }

    @Test
    fun `可疑解析文案_同时给出实际条数与预期条数_不再笼统写0条`() {
        val message = fundDividendSuspiciousMessage(
            "110011",
            actualRecordCount = 2,
            expectedRowCount = 10
        )
        assertTrue("文案必须写明实际解析条数: $message", message.contains("实际解析 2 条"))
        assertTrue("文案必须写明预期条数: $message", message.contains("预期 10 条"))
        assertFalse("残缺场景不能笼统写成「解析到 0 条」: $message", message.contains("解析到 0 条"))
    }

    @After
    fun resetBackoff() {
        // 防止退避窗口污染相邻用例
        FundDividendApiService.rateLimitedUntil = 0L
        FundDividendApiService.hostUnreachableUntil = 0L
        FundDividendApiService.backoffStore = null
        FundDividendApiService.fhspResetForTesting()
    }

    /**
     * 持久化退避窗口生效：模拟「进程重启后从 SP 读回的未来退避时间戳」——
     * 内存 [FundDividendApiService.rateLimitedUntil] 为 0，但注入的 store 返回未来值，
     * [getFundDividendHistory] 仍应在顶部短路、绝不发起网络。这钉死方案 B 的「合并内存与
     * 持久化、取更晚者」逻辑，确保冷启动后仍能记得上一轮撞过的限流退避。
     */
    @Test
    fun `getFundDividendHistory 持久化退避窗口生效_顶部短路不发起网络请求`() = runBlocking {
        val store = object : com.example.stockdiary.network.OtcBackoffStore {
            override fun get(key: String): Long = System.currentTimeMillis() + 60_000L
            override fun set(key: String, value: Long) = Unit
        }
        FundDividendApiService.backoffStore = store
        try {
            val explodingClient = mockk<OkHttpClient>(relaxed = true)
            every { explodingClient.newCall(any()) } throws AssertionError("持久化退避期间不应发起网络请求")
            service.client = explodingClient

            val result = service.getFundDividendHistory("110011")

            assertTrue("持久化退避窗口应直接失败", result.isFailure)
        } finally {
            FundDividendApiService.backoffStore = null
        }
    }
}
