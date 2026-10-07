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
