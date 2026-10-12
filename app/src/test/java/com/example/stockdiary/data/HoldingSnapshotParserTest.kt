package com.example.stockdiary.data

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
        assertEquals(StockCodeManager.CodeCategory.ETF, bySymbol.getValue("510210").category)
        assertEquals(StockCodeManager.CodeCategory.ETF, bySymbol.getValue("588000").category)
        assertEquals(StockCodeManager.CodeCategory.LOF, bySymbol.getValue("160719").category)
        assertEquals(StockCodeManager.CodeCategory.STOCK, bySymbol.getValue("601988").category)
        // REITs（508xxx）走 5 开头 → 场内基金
        assertEquals(StockCodeManager.CodeCategory.EXCHANGE_FUND, bySymbol.getValue("508016").category)
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
}
