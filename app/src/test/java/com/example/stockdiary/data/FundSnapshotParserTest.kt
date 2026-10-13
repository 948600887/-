package com.example.stockdiary.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 基金E账户**截图**解析的回归。测试数据取自真实截图（1024×17256 长截图）的逐行转写。
 *
 * 与 [FundEStatementParserTest] 的区别：那条路读的是导出文件，代码是独立一列；
 * 这条走 OCR，要自己从名称后面的括号里抠代码，并且应付四个坑。
 */
class FundSnapshotParserTest {

    /** 截图顶部的噪声：标题、总资产、tab、数据日期 */
    private val noise = listOf(
        "公募基金查询",
        "总资产",
        "人民币",
        "99,731.62",
        "我的账户 添加账户",
        "数据日期：2026年10月08日 筛选",
        "按产品展示 按账户展示"
    )

    // ---------- 卡片三行结构 ----------

    @Test
    fun 标准卡片三行解析出来() {
        val r = FundSnapshotParser.parse(
            noise + listOf(
                "中银如意宝货币A (004502)",
                "持有份额 参考净值 资产情况",
                "10,025.31 1.0000 10,025.31"
            )
        )
        assertEquals(1, r.holdings.size)
        val h = r.holdings[0]
        assertEquals("中银如意宝货币A", h.name)
        assertEquals("fund_004502", h.symbol)
        assertEquals(10025.31, h.quantity, 0.001)
        assertEquals(1.0000, h.nav ?: 0.0, 1e-9)
    }

    @Test
    fun 数据日期被提取出来() {
        val r = FundSnapshotParser.parse(
            noise + listOf(
                "中银如意宝货币A (004502)",
                "持有份额 参考净值 资产情况",
                "10,025.31 1.0000 10,025.31"
            )
        )
        assertEquals("2026年10月08日", r.dataDate)
    }

    // ---------- 坑一：括号全角半角混用 ----------

    @Test
    fun 全角括号也要认() {
        val r = FundSnapshotParser.parse(
            noise + listOf(
                "汇添富货币市场基金A（519518）",
                "持有份额 参考净值 资产情况",
                "189.37 1.1093 210.07"
            )
        )
        assertEquals("fund_519518", r.holdings[0].symbol)
        assertEquals("汇添富货币市场基金A", r.holdings[0].name)
    }

    // ---------- 坑二：名称里自带的 (LOF) 不是代码 ----------

    @Test
    fun 名称里的LOF括号不能被当成代码() {
        // 「兴全商业模式优选混合型证券投资基金(LOF)」——括号里是字母不是数字，
        // 若按「括号里有什么」判定，这里会读出一个不存在的代码
        val r = FundSnapshotParser.parse(
            noise + listOf(
                "兴全商业模式优选混合型证券投资基金(LOF)",
                "(163415)",
                "持有份额 参考净值 资产情况",
                "84.75 4.7090 399.09"
            )
        )
        assertEquals(1, r.holdings.size)
        assertEquals("fund_163415", r.holdings[0].symbol)
        assertEquals(84.75, r.holdings[0].quantity, 0.001)
        assertEquals(4.7090, r.holdings[0].nav ?: 0.0, 1e-9)
    }

    // ---------- 坑三：长名称换行，代码单独成段 ----------

    @Test
    fun 长名称换行后代码单独一行也要配对() {
        val r = FundSnapshotParser.parse(
            noise + listOf(
                "易方达稳健收益债券型证券投资基金B类",
                "(110008)",
                "持有份额 参考净值 资产情况",
                "40.16 1.4184 56.96"
            )
        )
        assertEquals(1, r.holdings.size)
        assertEquals("易方达稳健收益债券型证券投资基金B类", r.holdings[0].name)
        assertEquals("fund_110008", r.holdings[0].symbol)
        assertTrue(r.incomplete.isEmpty())
    }

    // ---------- 坑四：千分位与小数位 ----------

    @Test
    fun 千分位份额与四位小数净值() {
        val r = FundSnapshotParser.parse(
            noise + listOf(
                "中银如意宝货币A (004502)",
                "持有份额 参考净值 资产情况",
                "10,025.31 1.0000 10,025.31"
            )
        )
        assertEquals(10025.31, r.holdings[0].quantity, 0.001)
        // 净值 4 位：按 2 位显示会看不出差别，而「净值一模一样」
        // 恰恰是判断有没有读错列的最直接依据
        assertEquals(1.0000, r.holdings[0].nav ?: 0.0, 1e-9)
    }

    // ---------- 噪声 ----------

    @Test
    fun 总资产与tab名不能被当成持仓() {
        val r = FundSnapshotParser.parse(
            noise + listOf(
                "中银如意宝货币A (004502)",
                "持有份额 参考净值 资产情况",
                "10,025.31 1.0000 10,025.31"
            )
        )
        assertEquals(1, r.holdings.size)
        assertTrue(r.incomplete.isEmpty())
    }

    // ---------- 多个卡片 ----------

    @Test
    fun 多张卡片各自结算() {
        val r = FundSnapshotParser.parse(
            noise + listOf(
                "中银如意宝货币A (004502)",
                "持有份额 参考净值 资产情况",
                "10,025.31 1.0000 10,025.31",
                "招商招利宝货币A (003537)",
                "持有份额 参考净值 资产情况",
                "10,020.68 1.0000 10,020.68",
                "富国信用债债券A/B (000191)",
                "持有份额 参考净值 资产情况",
                "94.04 1.3627 128.15"
            )
        )
        assertEquals(3, r.holdings.size)
        assertEquals(
            listOf("fund_004502", "fund_003537", "fund_000191"),
            r.holdings.map { it.symbol }
        )
        assertTrue(r.incomplete.isEmpty())
    }

    // ---------- 辅助函数 ----------

    @Test
    fun 独立代码行的判定() {
        assertTrue(FundSnapshotParser.looksLikeStandaloneCode("(163415)"))
        assertTrue(FundSnapshotParser.looksLikeStandaloneCode("（163415）"))
        assertTrue(!FundSnapshotParser.looksLikeStandaloneCode("兴全商业模式优选混合型证券投资基金(LOF)"))
    }

    @Test
    fun 空输入不炸() {
        val r = FundSnapshotParser.parse(emptyList())
        assertTrue(r.holdings.isEmpty())
        assertNull(r.dataDate)
    }
}
