package com.example.stockdiary.data

import com.example.stockdiary.database.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OCR 噪声容错回归测试。
 *
 * 这些形态全部来自「识别少一行」的排查结论：每一种在修复前都会让整笔交易**静默消失**，
 * 或者更糟——配错行却看不出来。这里逐个钉死行为。
 */
class GalaxyParserNoiseTest {

    private fun parse(lines: List<String>) = GalaxyStatementParser.parse(lines, 2026)

    // ---------- 详情行形态：任一列被 OCR 认变形，都不能整笔消失 ----------

    @Test
    fun `详情行日期后多一个句点仍能解析`() {
        val result = parse(
            listOf(
                "证券买入-中证红利 1.546 -1,082.30",
                "买 09-23. 10:48 700 0.10"
            )
        )
        assertEquals(1, result.size)
        assertEquals("2026-09-23", result[0].date)
        assertEquals(700.0, result[0].quantity, 0.001)
    }

    @Test
    fun `详情行数量是小数仍能解析`() {
        val result = parse(
            listOf(
                "证券买入-某基金 1.546 -1,082.30",
                "买 09-23 10:48 700.00 0.10"
            )
        )
        assertEquals(1, result.size)
        assertEquals(700.0, result[0].quantity, 0.001)
        assertEquals(0.10, result[0].fee, 0.001)
    }

    @Test
    fun `详情行时间用全角冒号仍能解析`() {
        val result = parse(
            listOf(
                "证券买入-中证红利 1.546 -1,082.30",
                "买 09-23 10：48 700 0.10"
            )
        )
        assertEquals(1, result.size)
        assertEquals(700.0, result[0].quantity, 0.001)
    }

    @Test
    fun `详情行缺费用列时按0处理并提示`() {
        val result = parse(
            listOf(
                "证券买入-中证红利 1.546 -1,082.30",
                "买 09-23 10:48 700"
            )
        )
        assertEquals(1, result.size)
        assertEquals(700.0, result[0].quantity, 0.001)
        assertEquals(0.0, result[0].fee, 0.001)
        assertTrue(
            "费用缺失必须说出来，不能静默当 0: ${result[0].errors}",
            result[0].errors.any { it.contains("费用未识别") }
        )
    }

    @Test
    fun `详情行缺时间列仍能解析`() {
        val result = parse(
            listOf(
                "证券买入-中证红利 1.546 -1,082.30",
                "买 09-23 700 0.10"
            )
        )
        assertEquals(1, result.size)
        assertEquals("2026-09-23", result[0].date)
    }

    @Test
    fun `首行与详情行被并成一行时仍能正确解析`() {
        // 修复前：详情段会被当成名称的一部分，名称变成 "中证红利1.546-1,082.30买09-2310:48"、
        // 价格被认成 700 —— 数字全都看着正常，属于最危险的静默错数。
        val result = parse(
            listOf("证券买入-中证红利 1.546 -1,082.30 买 09-23 10:48 700 0.10")
        )
        assertEquals(1, result.size)
        assertEquals("中证红利", result[0].name)
        assertEquals(1.546, result[0].price, 0.001)
        assertEquals(700.0, result[0].quantity, 0.001)
        assertEquals(0.10, result[0].fee, 0.001)
        assertEquals("2026-09-23", result[0].date)
    }

    // ---------- 行序形态 ----------

    @Test
    fun `列被拆开时按出现顺序配对而不是就近抢行`() {
        // 两个首行相邻、明细行被整体推到后面（ML Kit 按列切块没合并回来的典型排版）。
        // 修复前：甲被丢弃，乙抢走甲的明细行（数量变成 100），结果是「少一笔 + 一笔错数」。
        val result = parse(
            listOf(
                "证券买入-甲 1.000 -100.0",
                "证券买入-乙 2.000 -200.0",
                "买 09-23 10:00 100 0.10",
                "买 09-23 11:00 200 0.20"
            )
        )
        assertEquals(2, result.size)
        assertEquals("甲", result[0].name)
        assertEquals(100.0, result[0].quantity, 0.001)
        assertEquals(0.10, result[0].fee, 0.001)
        assertEquals("乙", result[1].name)
        assertEquals(200.0, result[1].quantity, 0.001)
        assertEquals(0.20, result[1].fee, 0.001)
    }

    @Test
    fun `首行与详情行之间夹噪声仍能配对`() {
        val result = parse(
            listOf(
                "证券买入-甲 1.000 -100.0",
                "成交明细",
                "共 2 笔",
                "买 09-23 10:00 100 0.10"
            )
        )
        assertEquals(1, result.size)
        assertEquals(100.0, result[0].quantity, 0.001)
    }

    @Test
    fun `详情行被截图截断时保留待补全记录`() {
        // 最后一笔只剩首行。修复前整笔消失，用户只会觉得「怎么少了一笔」。
        val result = parse(
            listOf(
                "证券买入-甲 1.000 -100.0",
                "买 09-23 10:00 100 0.10",
                "证券买入-乙 2.000 -200.0"
            )
        )
        assertEquals(2, result.size)
        assertEquals("甲", result[0].name)
        assertEquals("乙", result[1].name)
        assertTrue(
            "缺明细的一笔必须标成待补全，不能静默丢弃: ${result[1].errors}",
            result[1].errors.contains(GalaxyStatementParser.ERR_DETAIL_MISSING)
        )
    }

    @Test
    fun `待补全记录不会混进提交队列`() {
        // 兜底记录带 error，commit 只处理 errors.isEmpty() 的行，因此不会写脏数据进库
        val result = parse(listOf("证券买入-乙 2.000 -200.0"))
        assertEquals(1, result.size)
        assertTrue(result[0].errors.isNotEmpty())
        assertTrue(result[0].symbol.isEmpty())
    }

    // ---------- 关键字 OCR 误字 ----------

    @Test
    fun `券被认成卷或劵仍能解析`() {
        listOf("证卷买入", "证劵买入").forEach { op ->
            val result = parse(
                listOf(
                    "$op-中证红利 1.546 -1,082.30",
                    "买 09-23 10:48 700 0.10"
                )
            )
            assertEquals("$op 应被识别", 1, result.size)
            assertEquals("中证红利", result[0].name)
            assertEquals(1.546, result[0].price, 0.001)
        }
    }

    @Test
    fun `入被认成人仍能解析`() {
        val result = parse(
            listOf(
                "证券买人-中证红利 1.546 -1,082.30",
                "买 09-23 10:48 700 0.10"
            )
        )
        assertEquals(1, result.size)
        assertEquals(TransactionType.BUY.value, result[0].type)
        assertEquals("中证红利", result[0].name)
    }

    @Test
    fun `关键字中间被插空格仍能解析`() {
        val result = parse(
            listOf(
                "证券买 入-中证红利 1.546 -1,082.30",
                "买 09-23 10:48 700 0.10"
            )
        )
        assertEquals(1, result.size)
        assertEquals("中证红利", result[0].name)
        assertEquals(1.546, result[0].price, 0.001)
    }

    @Test
    fun `关键字独占一行时并上下一行解析`() {
        val result = parse(
            listOf(
                "证券买入",
                "中证红利 1.546 -1,082.30",
                "买 09-23 10:48 700 0.10"
            )
        )
        assertEquals(1, result.size)
        assertEquals("中证红利", result[0].name)
        assertEquals(1.546, result[0].price, 0.001)
        assertEquals(700.0, result[0].quantity, 0.001)
    }

    // ---------- 真实截图形态：操作列没有「证券」二字 ----------

    /**
     * 取自真实长截图（1080×5888）：银河的成交明细页里，操作列只有「买入-科创50」
     * 「卖出-中证红利」，没有「证券」二字，而且和另一个页面「证券买入-…」的格式
     * 在同一张截图里并存。费用列则整列显示「--」（该页不展示费用）。
     */
    @Test
    fun `操作列没有证券二字时也能解析`() {
        val result = parse(
            listOf(
                "盈亏 (2026/07/01-至今)",
                "10,282.60 7.35% 详情",
                "上证 -3.47%",
                "平均仓位 52.28% 交易股票数 6",
                "成功率 64.38% 交易笔数 1021",
                "盈利股票数 8 亏损股票数 4",
                "持股天数 60 税费总额 142.09",
                "操作 价格/数量 金额/费用",
                "2026-09 -978.57 -0.56% 上证 -0.86%",
                "通用回购逆回购-GC001 1.450 90,000.00",
                "其 09-22 14:51 900 --",
                "卖出-中证红利 1.556 1,089.20",
                "卖 09-22 13:00 700 --",
                "买入-科创50 1.770 531.00",
                "买 09-22 13:00 300 --",
                "卖出-中证红利 1.551 1,085.70",
                "卖 09-22 09:41 700 --"
            )
        )
        assertEquals(3, result.size)

        assertEquals("中证红利", result[0].name)
        assertEquals(TransactionType.SELL.value, result[0].type)
        assertEquals(1.556, result[0].price, 0.001)
        assertEquals(700.0, result[0].quantity, 0.001)
        assertEquals("2026-09-22", result[0].date)

        assertEquals("科创50", result[1].name)
        assertEquals(TransactionType.BUY.value, result[1].type)
        assertEquals(531.00, result[1].price * result[1].quantity, 0.01)

        // 费用列整列是「--」：按 0 处理但必须提示，不能静默当 0
        assertTrue(
            "费用列为 -- 时要提示: ${result[0].errors}",
            result[0].errors.any { it.contains("费用未识别") }
        )
    }

    @Test
    fun `同一张截图里两种操作列格式混用也能全部解析`() {
        // 长截图前半是「卖出-中证红利」，后半切换成「证券买入-中证红利」
        val result = parse(
            listOf(
                "卖出-中证红利 1.556 1,089.20",
                "卖 09-22 13:00 700 --",
                "证券买入-中证红利 1.546 -1,082.30",
                "买 09-22 09:34 700 0.10"
            )
        )
        assertEquals(2, result.size)
        assertEquals("中证红利", result[0].name)
        assertEquals("中证红利", result[1].name)
        assertEquals(TransactionType.BUY.value, result[1].type)
    }

    /**
     * 图 1（中国银河对账单 09-24 那一屏）的逐行还原。
     * 三列是「操作 / 价格数量 / 金额费用」，价格与金额在独立列里。
     * 其中：买入金额为负、卖出金额为正；有 3 行逆回购要跳过。
     */
    private val realScreenshot0924 = listOf(
        "2026-09 -1,435.50 -0.83% 上证 -1.25%",
        "通用回购逆回购-GC001 1.445 -80,000.80",
        "其 09-24 14:51 800 0.80",
        "证券买入-科创50 1.721 -516.40",
        "买 09-24 14:02 300 0.10",
        "证券卖出-中国电信 6.120 1,223.28",
        "卖 09-24 13:08 200 0.72",
        "证券买入-综指ETF 1.001 -500.60",
        "买 09-24 11:27 500 0.10",
        "证券买入-中证红利 1.541 -1,078.80",
        "买 09-24 11:09 700 0.10",
        "证券买入-综指ETF 1.006 -503.10",
        "买 09-24 10:28 500 0.10",
        "证券买入-中国电信 6.060 -2,424.23",
        "买 09-24 09:47 400 0.23",
        "证券买入-中证红利 1.546 -1,082.30",
        "买 09-24 09:47 700 0.10",
        "证券卖出-中证红利 1.551 1,085.60",
        "卖 09-24 09:39 700 0.10",
        "证券买入-科创50 1.744 -523.30",
        "买 09-24 09:30 300 0.10",
        "通用回购逆回购购回-GC001 1.345 83,012.23",
        "其 09-24 830 0.00",
        "通用回购逆回购-GC001 1.345 -83,000.83",
        "其 09-23 14:51 830 0.83",
        "证券买入-中证红利 1.546 -1,082.30",
        "买 09-23 10:48 700 0.10",
        "证券买入-综指ETF 1.011 -505.60",
        "买 09-23 10:01 500 0.10"
    )

    @Test
    fun `真实截图 09-24 那一屏 11 笔一笔不少`() {
        val result = parse(realScreenshot0924)

        assertEquals("11 笔证券买卖，一笔都不能少", 11, result.size)
        assertEquals(9, result.count { it.type == TransactionType.BUY.value })
        assertEquals(2, result.count { it.type == TransactionType.SELL.value })

        // 逆回购（含带「购回」的那笔）不能混进来
        assertTrue(
            "逆回购不该入库: ${result.map { it.name }}",
            result.none { it.name.contains("GC001") }
        )
    }

    @Test
    fun `真实截图里每笔都能配上正确的日期与数量`() {
        val result = parse(realScreenshot0924)

        val first = result[0]
        assertEquals("科创50", first.name)
        assertEquals("2026-09-24", first.date)
        assertEquals(300.0, first.quantity, 0.001)
        assertEquals(1.721, first.price, 0.001)
        assertEquals(0.10, first.fee, 0.001)

        // 卖出中国电信：金额为正，方向靠「证券卖出」判定而不是靠符号
        val telecom = result[1]
        assertEquals("中国电信", telecom.name)
        assertEquals(TransactionType.SELL.value, telecom.type)
        assertEquals(200.0, telecom.quantity, 0.001)
        assertEquals(6.120, telecom.price, 0.001)
        assertEquals(0.72, telecom.fee, 0.001)

        // 所有笔都应有真实日期（没有一笔是靠兜底记录混进来的）
        result.forEach { tx ->
            assertTrue("第 ${tx.rowNumber} 笔日期异常: ${tx.date}", tx.date.startsWith("2026-09-"))
        }
    }

    @Test
    fun `真实截图里的手续费不会被交叉校验误判成列错位`() {
        val result = parse(realScreenshot0924)
        result.forEach { tx ->
            assertTrue(
                "第 ${tx.rowNumber} 笔被误判列错位: ${tx.errors}",
                tx.errors.none { it.contains("列错位") }
            )
        }
    }

    // ---------- 真实 OCR 输出：09-30 那一屏 ----------

    /**
     * 用户从预览页「查看识别原文」复制出来的真实数据，一字未改。
     *
     * 两个要命的形态：
     * - 明细行的方向前缀「卖」被 OCR 认成了「更」（字形极近）。28 行里 6 行中招，
     *   只有第 17 行认对了「卖」——这就是「除了第一行，其他都是日期未识别」的根因。
     * - 买入金额 `-486.40` 被认成 `一486.40`，减号成了中文「一」。
     */
    private val realOcrLines0930 = listOf(
        "23:14 63",
        "人 对账单",
        "中国银河证券**4625",
        "|本月 近三月 近半年 今年 全部 自定义",
        "操作 价格/数量 金额/费用0)",
        "通用回购逆回购-GC001 1.210 -82,000.82",
        "其09-30 14:51 820 0.82",
        "证券卖出-中证红利 1.551 1,085.60",
        "更 09-30 14:39 700 0.10",
        "证券卖出-综指ETF 0.991 495.40",
        "更09-30 13:35 500 0.10",
        "证券卖出-中证红利 1.546 1,082.10",
        "更 09-30 11:12 700 0.10",
        "证券买入科创50 1.621 一486.40",
        "更09-30 10:33 300 0.10",
        "证券卖出-中证红利 1.541 1,078.60",
        "卖09-30 09:53 700 0.10",
        "证券卖出-中证红利 1.536 1,075.10",
        "更09-30 09:39 700 0.10",
        "通用回购逆回购购回-GC001 1.180 78,020.17",
        "其09-30 780 0.00",
        "通用回购逆回购-GCO01 1.180 -78,000.78",
        "其09-29 14:51 780 0.78",
        "证券卖出-中证红利 1.531 1,071.60",
        "更09-29 14:15 700 0.10",
        "证券卖出-综指ETF 0.986 492.90",
        "n0n1フ·E4",
        "△"
    )

    @Test
    fun `真实识别原文里卖被认成更也能配上明细`() {
        val result = parse(realOcrLines0930)

        // 8 笔证券买卖（3 行逆回购跳过）
        assertEquals(8, result.size)
        // 只有明细被截图截断的最后一笔可以没有日期，其余 7 笔必须都有
        assertEquals(
            "除截断的那一笔外都应配上日期: ${result.map { it.date }}",
            7,
            result.count { it.date.isNotEmpty() }
        )
        // 逆回购（含字母 O 被认错的 GCO01）不能混进来
        assertTrue("逆回购不该入库: ${result.map { it.name }}", result.none { it.name.contains("GC") })
        // 顶部状态栏、tab 栏、表头都不能变成交易
        assertTrue(result.none { it.name.contains("对账单") })
    }

    @Test
    fun `真实识别原文里每笔的日期与数量都对`() {
        val result = parse(realOcrLines0930)
        val dated = result.filter { it.date.isNotEmpty() }

        assertEquals("中证红利", dated[0].name)
        assertEquals("2026-09-30", dated[0].date)
        assertEquals(700.0, dated[0].quantity, 0.001)
        assertEquals(1.551, dated[0].price, 0.001)
        assertEquals(0.10, dated[0].fee, 0.001)
        assertEquals(TransactionType.SELL.value, dated[0].type)

        assertEquals("综指ETF", dated[1].name)
        assertEquals(500.0, dated[1].quantity, 0.001)
        assertEquals(0.991, dated[1].price, 0.001)

        // 6 笔卖出的明细前缀都是「更」，方向仍要靠首行的「证券卖出」判定
        assertEquals(6, dated.count { it.type == TransactionType.SELL.value })
        assertTrue("方向不能靠明细行的前缀决定", dated.all { it.date.isNotEmpty() })
    }

    @Test
    fun `减号被认成中文一也能解析成负数金额`() {
        val result = parse(realOcrLines0930)
        val kc50 = result.first { it.name == "科创50" }

        assertEquals(1.621, kc50.price, 0.001)
        assertEquals(300.0, kc50.quantity, 0.001)
        assertEquals(TransactionType.BUY.value, kc50.type)
        assertTrue(
            "「一486.40」应还原成负数金额，不该被判列错位: ${kc50.errors}",
            kc50.errors.none { it.contains("列错位") }
        )
    }

    @Test
    fun `明细被截图截断的那一笔留成待补全而不是消失`() {
        val result = parse(realOcrLines0930)
        val incomplete = result.filter { it.errors.contains(GalaxyStatementParser.ERR_DETAIL_MISSING) }

        assertEquals(1, incomplete.size)
        assertEquals("综指ETF", incomplete[0].name)
        assertEquals(0.986, incomplete[0].price, 0.001)
    }

    // ---------- 相邻两行被 OCR 粘成一行 ----------

    /**
     * 真实现象：预览里除了第一笔，其余全是「日期未识别」。
     *
     * 原因是 OCR 按列切块时把「上一笔的明细段」和「下一笔的首行段」拼成了一行，
     * 于是每一笔的首行都能认出来（名称/价格/金额都在），但明细段配不上任何一笔。
     */
    @Test
    fun `相邻两行被粘成一行时明细仍能配到每一笔`() {
        val result = parse(
            listOf(
                "通用回购逆回购-GC001 1.210 -82,000.82",
                "其 09-30 14:51 820 0.82 证券卖出-中证红利 1.551 1,085.60",
                "卖 09-30 14:39 700 0.10 证券卖出-综指ETF 0.991 495.40",
                "卖 09-30 13:35 500 0.10 证券卖出-中证红利 1.546 1,082.10",
                "卖 09-30 11:12 700 0.10"
            )
        )
        assertEquals(3, result.size)

        // 关键：每一笔都要配上自己的日期与数量，一笔都不能落到「日期未识别」
        result.forEach { tx ->
            assertTrue(
                "第 ${tx.rowNumber} 笔没配上明细: ${tx.errors}",
                tx.date.startsWith("2026-09-30")
            )
            assertTrue(tx.quantity > 0)
            assertTrue(tx.errors.none { it.contains(GalaxyStatementParser.ERR_DETAIL_MISSING) })
        }

        assertEquals("中证红利", result[0].name)
        assertEquals(700.0, result[0].quantity, 0.001)
        assertEquals(1.551, result[0].price, 0.001)

        assertEquals("综指ETF", result[1].name)
        assertEquals(500.0, result[1].quantity, 0.001)
        assertEquals(TransactionType.SELL.value, result[1].type)

        assertEquals("中证红利", result[2].name)
        assertEquals(700.0, result[2].quantity, 0.001)
        assertEquals(1.546, result[2].price, 0.001)
        // 卖出方向靠「证券卖出」判定，不依赖金额正负
        result.forEach { assertEquals(TransactionType.SELL.value, it.type) }
    }

    @Test
    fun `首行与明细同在一行时价格不会取成数量`() {
        // 这种行如果只按「行末两个数字」硬认，价格会被认成 700（其实是数量）。
        val result = parse(
            listOf("证券卖出-中证红利 1.551 1,085.60 卖 09-30 14:39 700 0.10")
        )
        assertEquals(1, result.size)
        assertEquals("中证红利", result[0].name)
        assertEquals(1.551, result[0].price, 0.001)
        assertEquals(700.0, result[0].quantity, 0.001)
        assertEquals(0.10, result[0].fee, 0.001)
        assertEquals("2026-09-30", result[0].date)
    }

    @Test
    fun `正常的首行加明细同行形态不会被拆坏`() {
        // 明细在行尾，这是「两行并一行」的正常形态，不能当成混合行拆开
        val result = parse(
            listOf("证券买入-中证红利 1.546 -1,082.30 买 09-30 14:02 700 0.10")
        )
        assertEquals(1, result.size)
        assertEquals("中证红利", result[0].name)
        assertEquals("2026-09-30", result[0].date)
        assertEquals(700.0, result[0].quantity, 0.001)
        assertEquals(0.10, result[0].fee, 0.001)
    }

    @Test
    fun `纯明细行不会被拆出多余的行`() {
        val result = parse(
            listOf(
                "证券卖出-中证红利 1.551 1,085.60",
                "卖 09-30 14:39 700 0.10"
            )
        )
        assertEquals(1, result.size)
        assertEquals(700.0, result[0].quantity, 0.001)
    }

    // ---------- 裸方向词不能把界面元素当成交易 ----------

    @Test
    fun `App 的 tab 栏不会被当成一笔交易`() {
        // 真实截图里，滚动到底部会露出成交明细页的 tab 栏。
        // 它含「买入」「卖出」，若裸方向词不设门槛就会凭空多出一笔假交易。
        val result = parse(
            listOf(
                "证券卖出-中国银行 5.970 1,193.29",
                "卖 07-31 13:46 200 0.71",
                "明细 买入 卖出 条件单 看行情",
                "证券卖出-中证红利 1.566 1,096.10",
                "卖 07-31 13:45 700 0.10"
            )
        )
        assertEquals(2, result.size)
        assertEquals("中国银行", result[0].name)
        assertEquals(200.0, result[0].quantity, 0.001)
        assertEquals("中证红利", result[1].name)
        assertEquals(700.0, result[1].quantity, 0.001)
    }

    @Test
    fun `日期时间不会被当成裸方向交易的名称`() {
        // 「卖 09-22 13:00 700 0.10」能被裸正则啃成「名称=09-22 13:00、价格=700」，
        // 单独出现时绝不能凭空产出一笔。
        val result = parse(listOf("卖 09-22 13:00 700 0.10"))
        assertEquals(0, result.size)
    }

    // ---------- 逆回购 ----------

    @Test
    fun `证券买入不会用上逆回购的明细行`() {
        // 逆回购的明细行前缀是「其」。修复前证券买入会就近抓到它，数量变成 830。
        val result = parse(
            listOf(
                "证券买入-中证红利 1.546 -1,082.30",
                "通用回购逆回购-GC001 1.345 -83,000.83",
                "其 09-23 14:51 830 0.83",
                "买 09-23 10:48 700 0.10"
            )
        )
        assertEquals(1, result.size)
        assertEquals("中证红利", result[0].name)
        assertEquals(700.0, result[0].quantity, 0.001)
        assertEquals(0.10, result[0].fee, 0.001)
    }
}
