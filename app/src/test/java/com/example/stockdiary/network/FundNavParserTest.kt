package com.example.stockdiary.network

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 轻量净值源（FundMNBasicInformation）的解析与回退链回归。
 *
 * ## 这批测试要钉死什么
 *
 * 1. **货币基金不能把「每万份收益」当单位净值。** 实测 004972 的 `DWJZ` 是
 *    `0.2812`（每万份收益），而它的单位净值恒为 1.0000。当成净值用会让货币
 *    基金市值直接乘成 0.2812 倍 —— 这是**静默算错**，比内存问题更难发现。
 *    所以这里不仅断言 `price == 1.0`，还显式断言 `price != 0.2812`。
 * 2. **无效报文必须返回 null 而不是半截数据。** 返回 null 才会触发回退到
 *    legacy 源；返回半截数据（比如净值为 0 / 名称为空）会让持仓显示错价，
 *    且没有任何报错。
 * 3. **回退链与整源熔断**：轻量源拿不到时一定落到 legacy；连续失败到阈值后
 *    本进程不再白跑轻量请求。
 *
 * 下面的样例报文均**取自真实响应**（2026-10 实测），只裁掉了与解析无关
 * 的字段（如 FUNDINVEST / FUNDSUBJECTLIST），保留的键值一字未改。
 */
class FundNavParserTest {

    // ==================== 真实报文（字段裁剪自线上响应） ====================

    /** 110022 易方达消费行业股票：普通股票型，FUNDTYPE=001。 */
    private val normalFundJson = """
        {"Datas":{"FCODE":"110022","SHORTNAME":"易方达消费行业股票","FTYPE":"股票型",
        "FUNDTYPE":"001","ISEXCHG":"0","FSRQ":"2026-10-16","RZDF":"0.51",
        "DWJZ":"2.7850","LJJZ":"2.7850","JJGS":"易方达基金"}}
    """.trimIndent()

    /** 004972 长城收益宝货币A：货币型，FUNDTYPE=005，DWJZ 是每万份收益 0.2812。 */
    private val moneyFundJson = """
        {"Datas":{"FCODE":"004972","SHORTNAME":"长城收益宝货币A","FTYPE":"货币型-普通货币",
        "FUNDTYPE":"005","ISEXCHG":"0","FSRQ":"2026-10-18","RZDF":"--",
        "DWJZ":"0.2812","LJJZ":"1.0660","JJGS":"长城基金"}}
    """.trimIndent()

    /** 016668 景顺长城全球半导体芯片股票C(QDII-LOF)：QDII，净值照常解析。 */
    private val qdiiFundJson = """
        {"Datas":{"FCODE":"016668","SHORTNAME":"景顺长城全球半导体芯片股票C(QDII-LOF)(人民币)",
        "FTYPE":"QDII-普通股票","FUNDTYPE":"007","ISEXCHG":"0","FSRQ":"2026-10-15",
        "RZDF":"-1.41","DWJZ":"2.7628","LJJZ":"2.7628","JJGS":"景顺长城基金"}}
    """.trimIndent()

    /** 不存在的基金码 999999：HTTP 200 但 Datas 为 null、ErrCode 为 0。 */
    private val missingFundJson = """
        {"Datas":null,"ErrCode":0,"Success":true,"ErrMsg":null,"TotalCount":0}
    """.trimIndent()

    @Before
    fun setUp() {
        // 熔断状态与注入源是进程级全局的，每个用例都从出厂状态开始
        FundAPI.resetNavSourcesForTest()
    }

    @After
    fun tearDown() {
        FundAPI.resetNavSourcesForTest()
    }

    // ==================== 普通基金 / QDII ====================

    @Test
    fun 普通基金_解析出净值_日期_日涨幅与名称() {
        val info = FundNavParser.parseBasicInfo(normalFundJson, "110022")
        assertNotNull(info)
        assertEquals("易方达消费行业股票", info!!.name)
        assertEquals("110022", info.code)
        assertEquals(2.7850, info.price, 0.000001)
        assertEquals(2.7850, info.netWorth!!, 0.000001)
        assertEquals(0.51, info.changePercent!!, 0.000001)
        assertEquals("2026-10-16", info.date)
        assertNull(info.millionCopiesIncome)
    }

    @Test
    fun QDII_与普通基金同一条解析路径() {
        val info = FundNavParser.parseBasicInfo(qdiiFundJson, "016668")
        assertNotNull(info)
        assertEquals(2.7628, info!!.price, 0.000001)
        assertEquals(-1.41, info.changePercent!!, 0.000001)
        assertEquals("2026-10-15", info.date)
        assertTrue(info.name.isNotBlank())
    }

    @Test
    fun 报文缺FCODE时_用请求时的基金码兜底() {
        val json = """
            {"Datas":{"SHORTNAME":"某基金","FTYPE":"混合型","FUNDTYPE":"002",
            "FSRQ":"2026-10-16","RZDF":"0.10","DWJZ":"1.2340"}}
        """.trimIndent()
        assertEquals("000001", FundNavParser.parseBasicInfo(json, "000001")!!.code)
    }

    // ==================== 货币基金（本文件的核心） ====================

    @Test
    fun 货币基金_单位净值恒为1_0_且每万份收益单独给出() {
        val info = FundNavParser.parseBasicInfo(moneyFundJson, "004972")
        assertNotNull(info)
        assertEquals(1.0, info!!.price, 0.000001)
        assertEquals(1.0, info.netWorth!!, 0.000001)

        // 这条断言就是「货币基金陷阱」的钉子：0.2812 是每万份收益，
        // 绝不能被当成单位净值 —— 当成净值，市值会被乘成 0.2812 倍。
        assertNotEquals(0.2812, info.price, 0.000001)
        assertEquals(0.2812, info.millionCopiesIncome!!, 0.000001)

        // 货币基金没有日增长率（线上给的是 "--"），不能解析成 0.0 去参与计算
        assertNull(info.changePercent)
        assertEquals("长城收益宝货币A", info.name)
        assertEquals("2026-10-18", info.date)
    }

    @Test
    fun 货币基金_只靠FUNDTYPE类型码也能识别() {
        val json = """
            {"Datas":{"FCODE":"004972","SHORTNAME":"某货币","FTYPE":"",
            "FUNDTYPE":"005","FSRQ":"2026-10-18","RZDF":"--","DWJZ":"0.2812"}}
        """.trimIndent()
        val info = FundNavParser.parseBasicInfo(json, "004972")
        assertNotNull(info)
        assertEquals(1.0, info!!.price, 0.000001)
        assertEquals(0.2812, info.millionCopiesIncome!!, 0.000001)
    }

    @Test
    fun 货币基金_只靠FTYPE文案也能识别() {
        val json = """
            {"Datas":{"FCODE":"004972","SHORTNAME":"某货币","FTYPE":"货币型-普通货币",
            "FUNDTYPE":"","FSRQ":"2026-10-18","RZDF":"--","DWJZ":"0.2812"}}
        """.trimIndent()
        val info = FundNavParser.parseBasicInfo(json, "004972")
        assertNotNull(info)
        assertEquals(1.0, info!!.price, 0.000001)
        assertEquals(0.2812, info.millionCopiesIncome!!, 0.000001)
    }

    @Test
    fun 货币基金_DWJZ为0_当天无收益仍解析_单位净值1每万份收益0() {
        // 货币基金 DWJZ 是「每万份收益」，当天无收益时合法为 0。修复 #low 后，
        // 货币基金的 DWJZ=0 不再被 `nav <= 0` 整条拒绝，而是返回单位净值恒为 1.0、
        // 每万份收益 = 0 的结果（非货币基金 DWJZ=0 仍按原语义返回 null，见上方
        // 净值为空值或占位符_返回null 用例）。
        val json = """
            {"Datas":{"FCODE":"004972","SHORTNAME":"某货币","FTYPE":"货币型-普通货币",
            "FUNDTYPE":"005","FSRQ":"2026-10-18","RZDF":"--","DWJZ":"0"}}
        """.trimIndent()
        val info = FundNavParser.parseBasicInfo(json, "004972")
        assertNotNull("货币基金 DWJZ=0 不应被剔除", info)
        assertEquals(1.0, info!!.price, 0.000001)
        assertEquals(0.0, info.millionCopiesIncome!!, 0.000001)
    }

    // ==================== 无效报文一律 null（触发回退） ====================

    @Test
    fun 不存在的基金码_Datas为null_返回null() {
        // HTTP 200 + ErrCode 0，但 Datas 是 null：看起来「成功」的失败响应，
        // 绝不能当成净值用
        assertNull(FundNavParser.parseBasicInfo(missingFundJson, "999999"))
    }

    @Test
    fun 业务码非0_返回null() {
        assertNull(
            FundNavParser.parseBasicInfo(
                """{"Datas":{},"ErrCode":-999,"ErrMsg":"网络繁忙"}""",
                "110022"
            )
        )
        assertNull(
            FundNavParser.parseBasicInfo(
                """{"Datas":{},"ErrCode":61136,"ErrMsg":"网络繁忙"}""",
                "110022"
            )
        )
    }

    @Test
    fun 空列表式响应_返回null() {
        assertNull(FundNavParser.parseBasicInfo("""{"Datas":[],"ErrCode":0}""", "999999"))
        assertNull(FundNavParser.parseBasicInfo("""{"Datas":{}}""", "999999"))
    }

    @Test
    fun 净值为空值或占位符_返回null() {
        // 未公布净值时东财给 "--" 或空串：解析成 0.0 会把市值算没，
        // 必须整条丢弃并回退
        assertNull(FundNavParser.parseBasicInfo(dwjzJson("--"), "110022"))
        assertNull(FundNavParser.parseBasicInfo(dwjzJson(""), "110022"))
        assertNull(FundNavParser.parseBasicInfo(dwjzJson("0"), "110022"))
        assertNull(FundNavParser.parseBasicInfo(dwjzJson("-1.5"), "110022"))
    }

    @Test
    fun 名称为空_返回null_守住name非空契约() {
        // name 为空会被行情门面当成「没找到基金数据」丢弃（UnifiedPriceService），
        // 与其丢在下游，不如在这里就回退
        val json = """
            {"Datas":{"FCODE":"110022","SHORTNAME":"","FTYPE":"股票型","FUNDTYPE":"001",
            "FSRQ":"2026-10-16","RZDF":"0.51","DWJZ":"2.7850"}}
        """.trimIndent()
        assertNull(FundNavParser.parseBasicInfo(json, "110022"))
    }

    @Test
    fun 净值日期为空_返回null() {
        // 日期为空的净值落库后永远无法判断新旧，不如直接回退
        val json = """
            {"Datas":{"FCODE":"110022","SHORTNAME":"某基金","FTYPE":"股票型","FUNDTYPE":"001",
            "FSRQ":"","RZDF":"0.51","DWJZ":"2.7850"}}
        """.trimIndent()
        assertNull(FundNavParser.parseBasicInfo(json, "110022"))
    }

    @Test
    fun 报文畸形或为空_返回null() {
        assertNull(FundNavParser.parseBasicInfo("", "110022"))
        assertNull(FundNavParser.parseBasicInfo("   ", "110022"))
        assertNull(FundNavParser.parseBasicInfo("not json at all", "110022"))
        assertNull(FundNavParser.parseBasicInfo("{\"Datas\":{", "110022"))
        assertNull(FundNavParser.parseBasicInfo("<html>502 Bad Gateway</html>", "110022"))
    }

    // ==================== 回退链与整源熔断 ====================

    @Test
    fun 轻量源拿到数据时不打legacy() {
        var legacyCalls = 0
        val light = FundAPI.FundInfo(
            name = "轻量源基金", code = "110022", price = 2.7850,
            netWorth = 2.7850, changePercent = 0.51, date = "2026-10-16"
        )
        FundAPI.lightweightNavSource = { light }
        FundAPI.legacyNavSource = { legacyCalls++; null }

        val result = runBlocking { FundAPI.fetchFundInfo("110022") }
        assertEquals(light, result)
        assertEquals(0, legacyCalls)
        assertFalse(FundAPI.isLightweightSourceDisabled())
    }

    @Test
    fun 轻量源返回null时回退legacy() {
        var legacyCalls = 0
        val legacy = FundAPI.FundInfo(
            name = "legacy源基金", code = "110022", price = 2.7850,
            netWorth = 2.7850, changePercent = 0.51, date = "2026-10-16"
        )
        FundAPI.lightweightNavSource = { null }
        FundAPI.legacyNavSource = { legacyCalls++; legacy }

        val result = runBlocking { FundAPI.fetchFundInfo("110022") }
        assertEquals(legacy, result)
        assertEquals(1, legacyCalls)
    }

    @Test
    fun 轻量源抛异常时同样回退legacy() {
        var legacyCalls = 0
        val legacy = FundAPI.FundInfo(
            name = "legacy源基金", code = "110022", price = 1.0,
            netWorth = 1.0, changePercent = null, date = "2026-10-16"
        )
        FundAPI.lightweightNavSource = { throw java.io.IOException("连接超时") }
        FundAPI.legacyNavSource = { legacyCalls++; legacy }

        val result = runBlocking { FundAPI.fetchFundInfo("110022") }
        assertEquals(legacy, result)
        assertEquals(1, legacyCalls)
    }

    @Test
    fun 连续失败3次后整源熔断_后续基金不再白跑轻量请求() {
        var lightCalls = 0
        var legacyCalls = 0
        val legacy = FundAPI.FundInfo(
            name = "legacy源基金", code = "110022", price = 1.0,
            netWorth = 1.0, changePercent = null, date = "2026-10-16"
        )
        FundAPI.lightweightNavSource = { lightCalls++; null }
        FundAPI.legacyNavSource = { legacyCalls++; legacy }

        runBlocking {
            repeat(4) { index -> FundAPI.fetchFundInfo("00000$index") }
        }

        // 第 4 只直接走 legacy：轻量源只被问了 3 次
        assertEquals(3, lightCalls)
        assertEquals(4, legacyCalls)
        assertTrue(FundAPI.isLightweightSourceDisabled())
    }

    @Test
    fun 熔断前成功一次_连续失败计数清零() {
        var lightCalls = 0
        var legacyCalls = 0
        val ok = FundAPI.FundInfo(
            name = "轻量源基金", code = "110022", price = 2.7850,
            netWorth = 2.7850, changePercent = 0.51, date = "2026-10-16"
        )
        val legacy = FundAPI.FundInfo(
            name = "legacy源基金", code = "110022", price = 2.7850,
            netWorth = 2.7850, changePercent = 0.51, date = "2026-10-16"
        )
        // 失败、失败、成功（清零）、失败、失败 —— 从未凑够 3 次连续失败
        val sequence = listOf(null, null, ok, null, null)
        FundAPI.lightweightNavSource = { sequence[lightCalls++] }
        FundAPI.legacyNavSource = { legacyCalls++; legacy }

        runBlocking {
            repeat(sequence.size) { FundAPI.fetchFundInfo("110022") }
        }

        assertFalse(FundAPI.isLightweightSourceDisabled())
        assertEquals(5, lightCalls)
        // 只有那四次 null 才走 legacy
        assertEquals(4, legacyCalls)
        // 第 3 次成功后清零，末尾两次失败只累积到 2 —— 差一次就熔断
        assertEquals(2, FundAPI.lightweightFailureCount())
    }

    // ==================== 辅助 ====================

    /** 只替换 DWJZ 的报文，用来测各种「净值字段不正常」的情况。 */
    private fun dwjzJson(dwjz: String): String {
        return """
            {"Datas":{"FCODE":"110022","SHORTNAME":"易方达消费行业股票","FTYPE":"股票型",
            "FUNDTYPE":"001","FSRQ":"2026-10-16","RZDF":"0.51","DWJZ":"$dwjz"}}
        """.trimIndent()
    }
}
