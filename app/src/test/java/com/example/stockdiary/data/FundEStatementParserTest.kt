package com.example.stockdiary.data

import com.example.stockdiary.data.importer.FundEStatementParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 基金E账户导出解析的回归。测试数据取自真实导出（136 条明细 → 93 只，合计 99,731.62）。
 *
 * 重点三件事：代码要加 fund_ 前缀、净值要读出来、尾部的免责声明不能变成一只基金。
 */
class FundEStatementParserTest {

    private val header = listOf(
        "序号", "基金代码", "基金名称", "份额类别", "基金管理人", "基金账户",
        "销售机构", "交易账户", "持有份额", "份额日期", "基金净值", "净值日期",
        "资产情况\n（结算币种）", "结算币种"
    )

    /** 真实导出的前 4 行：标题 + 姓名证件号码 + 空行 */
    private val privacyHead: List<List<String>> = listOf(
        listOf("基金E账户App投资者公募基金持有信息"),
        listOf("姓名", "证件类型", "证件号码"),
        listOf("*梦阳", "中国居民身份证", "371***********2673"),
        emptyList<String>()
    )

    private fun row(
        no: String,
        code: String,
        name: String,
        share: String,
        nav: String = "1.0000",
        navDate: String = "2026/10/08"
    ): List<String> = listOf(
        no, code, name, "前收费", "某基金", "381217765167", "蚂蚁（杭州）基金销售",
        "31013230820878824", share, navDate, nav, navDate, share, "人民币"
    )

    // 用 listOf(header) 而不是直接 + header：
    // `List<List<String>> + List<String>` 有两种解释（加一个元素 / 加一组元素），
    // 编译器会推成 Any
    private fun table(vararg rows: List<String>): List<List<String>> =
        privacyHead + listOf(header) + rows.toList()

    /** 截取真实导出的前 8 条 + 两条边界情况（跨销售机构、QDII 净值滞后） */
    private val realRows: List<List<String>> = listOf(
        row("1.0", "004502", "中银如意宝货币A", "10025.31", "1.0000"),
        row("2.0", "003537", "招商招利宝货币A", "10020.68", "1.0000"),
        row("3.0", "007014", "嘉合磐泰短债A", "7767.33", "1.1663"),
        row("4.0", "007467", "中证红利低波ETF联接C", "4229.90", "1.6215"),
        row("5.0", "000306", "天弘弘利债券A", "4504.10", "1.1703"),
        row("6.0", "015598", "国泰中证申万证券行业指数证券投资基金（LOF）C类", "3552.54", "1.1101"),
        row("7.0", "016718", "中银中短债债券C", "3270.10", "1.1025"),
        row("8.0", "004080", "万家鑫丰纯债债券型证券投资基金C类", "2946.67", "1.1237"),
        // 同一只 007014 在蚂蚁还有一份：解析阶段照常输出两条，合并交给落库服务
        row("48.0", "007014", "嘉合磐泰短债A", "181.84", "1.1663"),
        // QDII 的净值本来就滞后：净值日期 09/30，其余是 10/08
        listOf(
            "45.0", "015299", "华夏纳斯达克100交易型开放式指数证券投资基金发起式联接基金(QDII)",
            "前收费", "华夏基金", "031787230730", "蚂蚁（杭州）基金销售", "31013230820878824",
            "122.92", "2026/10/08", "2.0839", "2026/09/30", "256.15", "人民币"
        ),
        emptyList<String>(),
        listOf("总记录数： 136 笔"),
        listOf("数据来源：基金E账户App", "打印时间：2026年10月13日 12:59:05"),
        listOf("说明："),
        listOf("数量单位：持有份额单位为“份”"),
        listOf("免责声明：基金E账户基于全市场各管理人报送的数据为您提供公募基金查询服务，仅供参考。")
    )

    private fun realTable(): List<List<String>> = privacyHead + listOf(header) + realRows
    // ---------- 基本解析 ----------

    @Test
    fun 十条明细全部解析出来() {
        assertEquals(10, FundEStatementParser.parse(realTable()).holdings.size)
    }

    @Test
    fun 尾部的总记录数与免责声明不能变成持仓() {
        // 「总记录数：136 笔」「说明：」「免责声明：…」首列都不是数字，
        // 一旦被当成数据行，就会读出一批代码为空的假持仓
        val r = FundEStatementParser.parse(realTable())
        assertTrue(r.problems.isEmpty())
        assertEquals(10, r.holdings.size)
    }

    @Test
    fun 代码要带fund_前缀() {
        val byCode = FundEStatementParser.parse(realTable()).holdings.associateBy { it.name }
        // 不加前缀的话，000306 会被规范化成 sz000306，跟深市股票号段撞在一起
        assertEquals("fund_004502", byCode.getValue("中银如意宝货币A").symbol)
        assertEquals("fund_000306", byCode.getValue("天弘弘利债券A").symbol)
    }

    @Test
    fun 净值要读出来() {
        val byCode = FundEStatementParser.parse(realTable()).holdings.associateBy { it.name }
        assertEquals(1.0000, byCode.getValue("中银如意宝货币A").nav ?: 0.0, 1e-9)
        assertEquals(1.6215, byCode.getValue("中证红利低波ETF联接C").nav ?: 0.0, 1e-9)
    }

    @Test
    fun 数据日期取出现次数最多的净值日期() {
        assertEquals("2026-10-08", FundEStatementParser.parse(realTable()).navDate)
    }

    @Test
    fun QDII的陈旧净值日期要单独保留() {
        // 纳斯达克100 的净值本来就滞后三天以上。
        // 不保留日期的话，界面会把 09/30 的净值当成 10/08 的显示
        val qdii = FundEStatementParser.parse(realTable())
            .holdings.first { it.symbol == "fund_015299" }
        assertEquals("2026-09-30", qdii.navDate)
    }

    // ---------- 隐私 ----------

    @Test
    fun 头部的姓名与证件号码绝不能被读出来() {
        val r = FundEStatementParser.parse(realTable())
        val all = r.holdings.joinToString("|") { "${it.name}${it.symbol}${it.rawQuantity}" } +
            r.problems.joinToString("|")
        assertTrue("不该出现姓名: $all", !all.contains("梦阳"))
        assertTrue("不该出现证件类型: $all", !all.contains("居民身份证"))
        // 证件号要整段比，不能查 "371" 这种裸子串——003537 与份额拼在一起
        // 恰好会造出 "371"，那种断言会假阳性失败
        assertTrue("不该出现证件号: $all", !all.contains("371***********2673"))
    }

    // ---------- 合并前的数据 ----------

    @Test
    fun 跨销售机构的同一只基金解析成两条_交给落库服务合并() {
        val rows = FundEStatementParser.parse(realTable()).holdings.filter { it.symbol == "fund_007014" }
        assertEquals(2, rows.size)
        // 7767.33(同花顺) + 181.84(蚂蚁) = 7949.17
        assertEquals(7949.17, rows.sumOf { it.quantity }, 0.001)
    }

    @Test
    fun 表头与数据之间的空行不能把数据区截断() {
        // 官方只要在表头和数据之间加一个空行（很常见的排版），
        // 旧逻辑就会 break，一条都读不到，而提示是「没找到基金代码这一列」——指错方向
        val withGap: List<List<String>> = privacyHead + listOf(header) +
            listOf(emptyList<String>()) +
            listOf(row("1.0", "004502", "中银如意宝货币A", "10025.31"))

        assertEquals(1, FundEStatementParser.parse(withGap).holdings.size)
    }

    @Test
    fun 数据中间的空行也照常跳过() {
        val withHole: List<List<String>> = privacyHead + listOf(header) +
            listOf(
                row("1.0", "004502", "中银如意宝货币A", "10025.31"),
                emptyList<String>(),
                row("2.0", "003537", "招商招利宝货币A", "10020.68"),
                emptyList<String>(),
                listOf("总记录数： 2 笔"),
                listOf("免责声明：……")
            )

        val r = FundEStatementParser.parse(withHole)
        assertEquals(2, r.holdings.size)
        assertTrue(r.problems.isEmpty())
    }

    /** 造一条指定结算币种的数据行（导出里这一列给的是中文名） */
    private fun rowWithCurrency(
        no: String,
        code: String,
        name: String,
        share: String,
        currency: String,
        nav: String = "1.5000"
    ): List<String> = row(no, code, name, share, nav).toMutableList().also { it[13] = currency }

    @Test
    fun 结算币种要读出来() {
        // 实测这份全是人民币。但官方说明明确写了「不同结算币种对应的资产情况」，
        // QDII 完全可能美元结算——不读就会把美元净值按人民币数值汇总
        val r = FundEStatementParser.parse(
            table(rowWithCurrency("1.0", "000041", "华夏全球股票QDII", "1000.00", "美元"))
        )
        assertEquals(1, r.holdings.size)
        assertEquals("USD", r.holdings[0].currency)
        assertEquals(1.5, r.holdings[0].nav ?: 0.0, 1e-9)
    }

    @Test
    fun 币种认不出来时报问题_不静默当成人民币() {
        val r = FundEStatementParser.parse(
            table(rowWithCurrency("1.0", "000041", "某离岸基金", "1000.00", "离岸人民币"))
        )
        assertTrue("认不出的币种不该进持仓", r.holdings.isEmpty())
        assertEquals(1, r.problems.size)
        assertTrue(r.problems[0].contains("离岸人民币"))
    }

    @Test
    fun 结算币种列缺失时按人民币处理且不打扰用户() {
        // 老版本导出可能没有这一列。缺列是「没有信息」，不是「信息异常」，
        // 按 CNY 走即可，报一条问题只会让用户以为导入失败了
        val noCurrency = header.filter { it.trim() != "结算币种" }
        val rowNoCurrency = row("1.0", "004502", "中银如意宝货币A", "10025.31").dropLast(1)

        val r = FundEStatementParser.parse(privacyHead + listOf(noCurrency) + listOf(rowNoCurrency))

        assertEquals(1, r.holdings.size)
        assertEquals("CNY", r.holdings[0].currency)
        assertTrue(r.problems.isEmpty())
    }

    @Test
    fun 同一代码多渠道币种一致时才能合并() {
        // 币种不一致还合并，市值就会把美元和人民币加在一起
        val holdings = FundEStatementParser.parse(
            table(
                rowWithCurrency("1.0", "000041", "华夏全球股票QDII", "600.00", "美元"),
                rowWithCurrency("2.0", "000041", "华夏全球股票QDII", "400.00", "美元")
            )
        ).holdings

        assertEquals(2, holdings.size)
        assertEquals(setOf("USD"), holdings.map { it.currency }.toSet())
    }

    // ---------- 数值与日期 ----------

    @Test
    fun 千分位份额要能解析() {
        assertEquals(10025.31, FundEStatementParser.parseNumber("10,025.31") ?: 0.0, 1e-9)
        // 中文逗号也要剥：有些导出会把千分位写成全角
        assertEquals(10025.31, FundEStatementParser.parseNumber("10，025.31") ?: 0.0, 1e-9)
        assertNull(FundEStatementParser.parseNumber(""))
        assertNull(FundEStatementParser.parseNumber("读不出"))
    }

    @Test
    fun 各种日期写法统一成横杠() {
        assertEquals("2026-10-08", FundEStatementParser.normalizeDate("2026/10/08"))
        assertEquals("2026-10-08", FundEStatementParser.normalizeDate("2026年10月8日"))
        assertNull(FundEStatementParser.normalizeDate(""))
    }

    // ---------- 异常输入 ----------

    @Test
    fun 没有基金代码列时报错而不是返回空() {
        val r = FundEStatementParser.parse(listOf(listOf("序号", "名称"), listOf("1", "某基金")))
        assertTrue(r.holdings.isEmpty())
        assertEquals(1, r.problems.size)
    }

    @Test
    fun 代码不是六位时进问题列表() {
        val r = FundEStatementParser.parse(table(row("1.0", "4502", "代码短了", "100.00")))
        assertTrue(r.holdings.isEmpty())
        assertEquals(1, r.problems.size)
        assertTrue(r.problems[0].contains("4502"))
    }

    @Test
    fun 份额读不出来时进问题列表() {
        val r = FundEStatementParser.parse(table(row("1.0", "004502", "份额坏了", "读不出")))
        assertTrue(r.holdings.isEmpty())
        assertEquals(1, r.problems.size)
    }

    @Test
    fun 负份额会被挡下_不让它在落库时才炸() {
        // Asset 构造器有 require(balance >= 0)，负数走到那里会把整次导入打断
        val r = FundEStatementParser.parse(table(row("1.0", "004502", "负份额", "-100.00")))
        assertTrue(r.holdings.isEmpty())
        assertEquals(1, r.problems.size)
    }

    @Test
    fun 表头改到别的位置也能认() {
        // 按「哪个是表头」定位而不是写死行号：官方往上加一行说明也不会崩
        val moved: List<List<String>> = privacyHead + listOf(emptyList()) +
            listOf(header) + listOf(row("1.0", "004502", "中银如意宝货币A", "10025.31"))
        assertEquals(1, FundEStatementParser.parse(moved).holdings.size)
    }
}
