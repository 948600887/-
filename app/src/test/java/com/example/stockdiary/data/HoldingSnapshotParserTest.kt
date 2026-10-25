package com.example.stockdiary.data

import com.example.stockdiary.data.importer.HoldingSnapshotParser
import com.example.stockdiary.data.importer.ParsedHolding
import com.example.stockdiary.utils.StockCodeManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 持仓快照解析器的回归。测试数据取自「中国结算 · 一码通持有查询」的真实截图。
 */
class HoldingSnapshotParserTest {

    /** 截图里真实出现的表头与上方噪声 */
    private val noise = listOf(
        "一码通持有查询",
        "我的一码通 证券持有余额 证券持有变更",
        "新股配号中签情况 放弃认购数据 股息红利所得税",
        "数据日期 2026-10-09",
        "名称/代码 持有数量 其中冻结数量"
    )

    // ---------- 数量落在哪一行，三种形态都得认 ----------

    @Test
    fun 数量跟在名称同一行() {
        val r = HoldingSnapshotParser.parse(noise + listOf("黄金LOF 262", "160719"))
        assertEquals(1, r.holdings.size)
        assertEquals("黄金LOF", r.holdings[0].name)
        assertEquals("160719", r.holdings[0].symbol)
        assertEquals(262.0, r.holdings[0].quantity, 0.001)
    }

    @Test
    fun 数量跟在代码同一行() {
        val r = HoldingSnapshotParser.parse(noise + listOf("黄金LOF", "160719 262"))
        assertEquals(1, r.holdings.size)
        assertEquals("黄金LOF", r.holdings[0].name)
        assertEquals(262.0, r.holdings[0].quantity, 0.001)
    }

    @Test
    fun 数量单独成行() {
        // 数量的垂直中心正好夹在名称与代码中间，OCR 可能把它切出来单独成行
        val r = HoldingSnapshotParser.parse(noise + listOf("黄金LOF", "262", "160719"))
        assertEquals(1, r.holdings.size)
        assertEquals("黄金LOF", r.holdings[0].name)
        assertEquals(262.0, r.holdings[0].quantity, 0.001)
    }

    // ---------- 中文单位：最危险的一处 ----------

    @Test
    fun 万的单位必须展开_不能按字面读成一点四四() {
        val r = HoldingSnapshotParser.parse(noise + listOf("综指ETF 1.44万", "510210"))
        assertEquals(14400.0, r.holdings[0].quantity, 0.001)
    }

    @Test
    fun 被空格拆开的万也要认() {
        // OCR 常把「1.44万」切成「1.44」和「万」两个 token
        val r = HoldingSnapshotParser.parse(noise + listOf("中证红利 2.17 万", "515080"))
        assertEquals(21700.0, r.holdings[0].quantity, 0.001)
    }

    @Test
    fun 原始写法要保留下来供核对() {
        val r = HoldingSnapshotParser.parse(noise + listOf("综指ETF 1.44万", "510210"))
        assertEquals("1.44万", r.holdings[0].rawQuantity)
    }

    // ---------- 完整截图 ----------

    private val realScreenshot = noise + listOf(
        "黄金LOF 262", "160719",
        "国投白银LOF 1264", "161226",
        "黄金LOF 156", "164701",
        "R东方1 100", "400264",
        "全球芯片 44", "501225",
        "海外科技 195", "501312",
        "华电REIT 1", "508016",
        "北京亦庄 1", "508080",
        "恒泰租住 1", "508085",
        "综指ETF 1.44万", "510210",
        "中证红利 2.17万", "515080",
        "科创50 9300", "588000",
        "中国电信 1400", "601728",
        "中国银行 900", "601988"
    )

    @Test
    fun 真实截图十四条全部解析出来() {
        val r = HoldingSnapshotParser.parse(realScreenshot)
        assertEquals(14, r.holdings.size)
        assertTrue("不该有没读懂的行: ${r.incomplete}", r.incomplete.isEmpty())
    }

    /** 梦瑶 2026-10-16 发来的真实一码通截图：同上版式，数量是较新快照，含 1.10万 这种带尾零的万 */
    private val mengyaoScreenshot = noise + listOf(
        "嘉实黄金LOF 202", "160719",
        "国投白银LOF 1264", "161226",
        "黄金LOF 156", "164701",
        "R东方1 100", "400264",
        "全球芯片 44", "501225",
        "海外科技 195", "501312",
        "华电REIT 1", "508016",
        "北京亦庄 1", "508080",
        "恒泰租住 1", "508085",
        "综指ETF 1.74万", "510210",
        "中证红利 2.45万", "515080",
        "科创50 1.10万", "588000",
        "中国电信 1400", "601728",
        "中国银行 900", "601988"
    )

    @Test
    fun 梦瑶的截图十四条全部解析_万带尾零也认() {
        val r = HoldingSnapshotParser.parse(mengyaoScreenshot)
        assertEquals(14, r.holdings.size)
        assertTrue("不该有没读懂的行: ${r.incomplete}", r.incomplete.isEmpty())
        val bySymbol = r.holdings.associateBy { it.symbol }
        // 三个「万」：1.74万=17400、2.45万=24500、1.10万=11000（小数带尾零也要展开对）
        assertEquals(17400.0, bySymbol.getValue("510210").quantity, 0.001)
        assertEquals(24500.0, bySymbol.getValue("515080").quantity, 0.001)
        assertEquals(11000.0, bySymbol.getValue("588000").quantity, 0.001)
        // 名称相近（都叫黄金LOF）但代码不同的两只，必须各归各、不能混成一条
        assertEquals("嘉实黄金LOF", bySymbol.getValue("160719").name)
        assertEquals(202.0, bySymbol.getValue("160719").quantity, 0.001)
        assertEquals("黄金LOF", bySymbol.getValue("164701").name)
        assertEquals(156.0, bySymbol.getValue("164701").quantity, 0.001)
    }

    @Test
    fun 真实截图的数量逐个核对() {
        val bySymbol = HoldingSnapshotParser.parse(realScreenshot).holdings.associateBy { it.symbol }
        assertEquals(262.0, bySymbol.getValue("160719").quantity, 0.001)
        assertEquals(1264.0, bySymbol.getValue("161226").quantity, 0.001)
        assertEquals(156.0, bySymbol.getValue("164701").quantity, 0.001)
        assertEquals(100.0, bySymbol.getValue("400264").quantity, 0.001)
        assertEquals(44.0, bySymbol.getValue("501225").quantity, 0.001)
        assertEquals(195.0, bySymbol.getValue("501312").quantity, 0.001)
        assertEquals(1.0, bySymbol.getValue("508016").quantity, 0.001)
        assertEquals(1.0, bySymbol.getValue("508080").quantity, 0.001)
        assertEquals(1.0, bySymbol.getValue("508085").quantity, 0.001)
        assertEquals(14400.0, bySymbol.getValue("510210").quantity, 0.001)
        assertEquals(21700.0, bySymbol.getValue("515080").quantity, 0.001)
        assertEquals(9300.0, bySymbol.getValue("588000").quantity, 0.001)
        assertEquals(1400.0, bySymbol.getValue("601728").quantity, 0.001)
        assertEquals(900.0, bySymbol.getValue("601988").quantity, 0.001)
    }

    @Test
    fun 同名不同代码不会被弄混() {
        // 截图里真有两行都叫「黄金LOF」——160719 和 164701。
        // 这正是交割单那条路要靠名称猜代码时的噩梦，有代码就无所谓。
        val golds = HoldingSnapshotParser.parse(realScreenshot).holdings.filter { it.name == "黄金LOF" }
        assertEquals(2, golds.size)
        assertEquals(setOf("160719", "164701"), golds.map { it.symbol }.toSet())
        assertEquals(setOf(262.0, 156.0), golds.map { it.quantity }.toSet())
    }

    @Test
    fun 名称里自带数字的不会被当成数量() {
        // 「科创50」「R东方1」——名称本身含数字，剥数量时不能伤到它们
        val bySymbol = HoldingSnapshotParser.parse(realScreenshot).holdings.associateBy { it.symbol }
        assertEquals("科创50", bySymbol.getValue("588000").name)
        assertEquals("R东方1", bySymbol.getValue("400264").name)
    }

    // ---------- 类型推断 ----------

    @Test
    fun 按代码前缀推断类别() {
        val bySymbol = HoldingSnapshotParser.parse(realScreenshot).holdings.associateBy { it.symbol }
        // 类别判定统一走 StockCodeManager.classifyCode（ParsedHolding 已移除自带 category 字段）
        assertEquals(StockCodeManager.CodeCategory.ETF, StockCodeManager.classifyCode(bySymbol.getValue("510210").symbol))
        assertEquals(StockCodeManager.CodeCategory.ETF, StockCodeManager.classifyCode(bySymbol.getValue("588000").symbol))
        assertEquals(StockCodeManager.CodeCategory.LOF, StockCodeManager.classifyCode(bySymbol.getValue("160719").symbol))
        assertEquals(StockCodeManager.CodeCategory.STOCK, StockCodeManager.classifyCode(bySymbol.getValue("601988").symbol))
        // REITs（508xxx）走 5 开头 → 场内基金
        assertEquals(StockCodeManager.CodeCategory.EXCHANGE_FUND, StockCodeManager.classifyCode(bySymbol.getValue("508016").symbol))
    }

    // ---------- 表头与噪声 ----------

    @Test
    fun 表头和功能入口不会被当成持仓() {
        val r = HoldingSnapshotParser.parse(noise)
        assertTrue(r.holdings.isEmpty())
        assertTrue(r.incomplete.isEmpty())
    }

    @Test
    fun 数据日期被提取出来() {
        assertEquals("2026-10-09", HoldingSnapshotParser.parse(realScreenshot).dataDate)
    }

    @Test
    fun 没有数据日期时为null() {
        assertNull(HoldingSnapshotParser.parse(listOf("黄金LOF 262", "160719")).dataDate)
    }

    @Test
    fun 冻结列的横杠不会被当成数量() {
        // 真实一行是「名称 | 持有数量 | 其中冻结数量」，还原后三者常在同行：
        // "黄金LOF 262 -"。末位那个 "-" 是冻结列，不能把 262 冲掉也不能让它自己变数量
        val r = HoldingSnapshotParser.parse(noise + listOf("黄金LOF 262 -", "160719"))
        assertEquals(1, r.holdings.size)
        assertEquals(262.0, r.holdings[0].quantity, 0.001)
        assertTrue(r.incomplete.isEmpty())
    }

    @Test
    fun 冻结列单独成行时也不干扰() {
        val r = HoldingSnapshotParser.parse(noise + listOf("黄金LOF 262", "160719", "-"))
        assertEquals(1, r.holdings.size)
        assertEquals(262.0, r.holdings[0].quantity, 0.001)
    }

    // ---------- 真实几何：数量与冻结列天然同行 ----------

    /**
     * 按真实截图量出来的垂直位置（见 HoldingSnapshotParser 类注释）分组：
     * 名称一行、数量跟着冻结列的横杠一行、代码一行。这是行聚合**最可能**产出的形态。
     */
    private val realScreenshotWithDash = noise + listOf(
        "黄金LOF", "262 -", "160719",
        "国投白银LOF", "1264 -", "161226",
        "黄金LOF", "156 -", "164701",
        "R东方1", "100 -", "400264",
        "全球芯片", "44 -", "501225",
        "海外科技", "195 -", "501312",
        "华电REIT", "1 -", "508016",
        "北京亦庄", "1 -", "508080",
        "恒泰租住", "1 -", "508085",
        "综指ETF", "1.44万 -", "510210",
        "中证红利", "2.17万 -", "515080",
        "科创50", "9300 -", "588000",
        "中国电信", "1400 -", "601728",
        "中国银行", "900 -", "601988"
    )

    @Test
    fun 数量跟着冻结横杠同行时也要认() {
        val r = HoldingSnapshotParser.parse(noise + listOf("黄金LOF", "156 -", "164701"))
        assertEquals(1, r.holdings.size)
        assertEquals("黄金LOF", r.holdings[0].name)
        assertEquals("164701", r.holdings[0].symbol)
        assertEquals(156.0, r.holdings[0].quantity, 0.001)
        assertTrue("不该有没读懂的行: ${r.incomplete}", r.incomplete.isEmpty())
    }

    @Test
    fun 数量跟横杠同行时中文单位照样展开() {
        val r = HoldingSnapshotParser.parse(noise + listOf("综指ETF", "1.44万 -", "510210"))
        assertEquals(14400.0, r.holdings[0].quantity, 0.001)
        assertEquals("1.44万", r.holdings[0].rawQuantity)
    }

    @Test
    fun 真实截图十四条在数量跟横杠同行的形态下也全部解析出来() {
        val r = HoldingSnapshotParser.parse(realScreenshotWithDash)
        assertEquals(14, r.holdings.size)
        assertTrue("不该有没读懂的行: ${r.incomplete}", r.incomplete.isEmpty())
    }

    @Test
    fun 两种行分组形态必须给出同样的结果() {
        // 数量并进名称、并进代码、自己一行、跟着横杠一行——四种分组都只是 OCR 的排布差异，
        // 解析结果必须完全一致，否则「同样的截图导两次得到不同的持仓」
        val a = HoldingSnapshotParser.parse(realScreenshot).holdings
        val b = HoldingSnapshotParser.parse(realScreenshotWithDash).holdings
        assertEquals(
            a.map { Triple(it.name, it.symbol, it.quantity) },
            b.map { Triple(it.name, it.symbol, it.quantity) }
        )
    }

    @Test
    fun 横杠并进名称行时名称要干净() {
        // 名字后面不能留个 "-"：预览页要显示它，脏字符会让人以为名称读错了
        val r = HoldingSnapshotParser.parse(noise + listOf("黄金LOF -", "156", "164701"))
        assertEquals("黄金LOF", r.holdings[0].name)
        assertEquals(156.0, r.holdings[0].quantity, 0.001)
    }

    @Test
    fun 名称里的连字符不能被当成冻结横杠() {
        // 只认**独立成 token** 的横杠，否则「华泰-创新」会被削成「华泰」
        assertEquals("华泰-创新", HoldingSnapshotParser.stripQuantity("华泰-创新 100"))
        assertEquals("黄金LOF", HoldingSnapshotParser.stripQuantity("黄金LOF 262 -"))
    }

    @Test
    fun 真的填了冻结数量时取的是持有数量() {
        // 表头顺序是「持有数量 | 其中冻结数量」，横杠换成真数字时也不能把它当持仓数
        val r = HoldingSnapshotParser.parse(noise + listOf("黄金LOF", "500 100", "164701"))
        assertEquals(500.0, r.holdings[0].quantity, 0.001)
    }

    // ---------- 残缺输入 ----------

    @Test
    fun 有代码缺名称时不算持仓_但要报出来() {
        // 截图被裁在中间时会出现：代码行在、它上面的名称被切掉
        val r = HoldingSnapshotParser.parse(listOf("160719", "国投白银LOF 1264", "161226"))
        assertEquals(1, r.holdings.size)
        assertEquals("161226", r.holdings[0].symbol)
        assertEquals(1, r.incomplete.size)
        assertTrue(r.incomplete[0].contains("160719"))
    }

    @Test
    fun 末尾只有名称没有代码时也要报出来() {
        val r = HoldingSnapshotParser.parse(listOf("黄金LOF 262", "160719", "中国银行 900"))
        assertEquals(1, r.holdings.size)
        assertEquals(1, r.incomplete.size)
    }

    @Test
    fun 负数数量要当成没读懂_不能让它一路走到落库() {
        // Asset 构造器里有 require(balance >= 0)：负数会在写库时把整次导入打断，
        // 而且弹出来的「数量不能为负数」说不清是哪一行。这里提前判掉
        val r = HoldingSnapshotParser.parse(noise + listOf("黄金LOF", "-156", "164701"))
        assertTrue(r.holdings.isEmpty())
        assertEquals(1, r.incomplete.size)
    }

    @Test
    fun 空输入不炸() {
        val r = HoldingSnapshotParser.parse(emptyList())
        assertTrue(r.holdings.isEmpty())
        assertNull(r.dataDate)
    }

    // ---------- 类别标签：按号段/前缀判定（统一走 StockCodeManager.classifyCode） ----------

    @Test
    fun 场内代码仍按号段分类() {
        // 没有前缀时一切照旧：LOF 160719 是 LOF、ETF 510210 是 ETF、400264 是老三板股票。
        // 注意：ParsedHolding 已移除自带的 category 字段，类别判定统一走 StockCodeManager.classifyCode
        assertEquals(
            StockCodeManager.CodeCategory.LOF,
            StockCodeManager.classifyCode("160719")
        )
        assertEquals(
            StockCodeManager.CodeCategory.ETF,
            StockCodeManager.classifyCode("510210")
        )
        assertEquals(
            StockCodeManager.CodeCategory.STOCK,
            StockCodeManager.classifyCode("400264")
        )
    }

    // ---------- 边界：首位 '7' 与数量为零 ----------

    @Test
    fun 首位是7的代码也能识别为有效代码_不进incomplete() {
        // 一码通持有里 7 开头多为配股/可转债申购/优先股（如 733xxx）。
        // extractSymbol 的允许首位集合必须含 '7'，否则整条被丢进 incomplete。
        val r = HoldingSnapshotParser.parse(noise + listOf("某配股 100", "733001"))
        assertEquals(1, r.holdings.size)
        assertEquals("733001", r.holdings[0].symbol)
        assertEquals(100.0, r.holdings[0].quantity, 0.001)
        assertTrue("7 开头代码不该被当成没读懂", r.incomplete.isEmpty())
    }

    @Test
    fun 数量为零时按没读懂处理_与基金链路对齐() {
        // 与 FundEStatementParser（份额 <= 0 拒绝）口径一致：0 份额不落库，归为 incomplete。
        val r = HoldingSnapshotParser.parse(noise + listOf("黄金LOF 0", "160719"))
        assertTrue(r.holdings.isEmpty())
        assertEquals(1, r.incomplete.size)
        assertTrue(r.incomplete[0].contains("160719"))
    }

    @Test
    fun 数量为负时判为识别错误_进incomplete而非落库() {
        // 股票/基金不存在「负持仓」这种合法状态（借币/欠币这类负余额只发生在币安加密货币，
        // 由 BinanceImportService 单独保留）；负值在此视为数据错误，走 incomplete 让用户看清哪行被跳过。
        val r = HoldingSnapshotParser.parse(noise + listOf("黄金LOF -50", "160719"))
        assertTrue("负数量不应落库", r.holdings.isEmpty())
        assertEquals(1, r.incomplete.size)
        assertTrue(r.incomplete[0].contains("160719"))
    }
}
