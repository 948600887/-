package com.example.stockdiary.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分红预取收尾汇总的纯函数回归（[formatDividendPrefetchSummary] / [formatSkippedCodes]）。
 *
 * 这条日志是排障时唯一能一眼看清「一轮多少只成功 / 多少只未取到 / 其中多少只
 * 数据源未应答（含 514/429 限流）」的地方，而 app.log 是环形缓冲：逐标的 WARN 会被冲掉大半。
 * 所以两件事必须用单测钉死：
 *
 * 1. **计数口径**：未取到数取去重后的独立标的数，「退避/预算/缓存复用等」= 未取到 - 未应答，
 *    无需重抓 = 参与 - 发起；异常入参一律钳零，不能让日志出现负数或自相矛盾的数字。
 * 2. **清单截断**：规范化去重、条数与字符双层上限、超出部分用 `…+N` 交代余数，
 *    保证这一行永远是短行、不会撑爆环形缓冲。
 *
 * 纯 JVM 单测，不需要 Robolectric / Context / Room。
 */
class DividendPrefetchSummaryTest {

    // ---- 代码清单：去重、排序、两层截断 ----

    @Test
    fun `代码清单_去重后按字典序排序_输出稳定`() {
        // 并发收尾顺序不确定，排序保证日志可复现、便于按代码 grep
        assertEquals(
            "a,b,c",
            formatSkippedCodes(listOf("c", "a", "b", "a", "c", "b"))
        )
    }

    @Test
    fun `代码清单_空清单显示无且不出现省略号`() {
        assertEquals("无", formatSkippedCodes(emptyList()))
        assertEquals("无", formatSkippedCodes(emptyList(), maxCodes = 1, maxChars = 1))
    }

    @Test
    fun `代码清单_恰好等于条数上限时不省略`() {
        assertEquals(
            "a,b,c",
            formatSkippedCodes(listOf("c", "a", "b"), maxCodes = 3)
        )
    }

    @Test
    fun `代码清单_超出条数上限用省略号交代余数`() {
        assertEquals(
            "a,b,c…+2",
            formatSkippedCodes(listOf("e", "c", "a", "d", "b"), maxCodes = 3)
        )
    }

    @Test
    fun `代码清单_字符上限也能截断且余数含被字符砍掉的`() {
        // AAAA(4) 纳入后 usedChars=4，BBBB 再加 1 个逗号 + 4 = 9 > 6，截断；
        // 被省略的是 BBBB、CCCC 共 2 条，不能只报条数上限之外的那部分
        assertEquals(
            "AAAA…+2",
            formatSkippedCodes(listOf("CCCC", "AAAA", "BBBB"), maxCodes = 12, maxChars = 6)
        )
    }

    @Test
    fun `代码清单_单个超长代码仍至少列出一条_不会变成空串`() {
        // 第一条无条件纳入；后续即使字符预算只有 1 也必须把其余的计进 …+N
        assertEquals(
            "ABCDEFGHIJ…+1",
            formatSkippedCodes(listOf("ABCDEFGHIJ", "ZZ"), maxCodes = 12, maxChars = 1)
        )
    }

    // ---- 汇总行：计数口径、单行、截断 ----

    @Test
    fun `汇总_20时29分那轮的计数口径_成功加未取到等于发起数`() {
        // 示意：151 只参与 / 151 只发起 / 101 只拿到新数据 / 50 只未取到
        // （其中 40 只归入「数据源未应答（含 514/429 限流）」、10 只归入「退避/预算/缓存复用等」），
        // 本轮真实 514/429 响应 1 次。
        val codes = (1..50).map { "fund_%06d".format(it) }
        val line = formatDividendPrefetchSummary(
            roundId = 7,
            totalSymbols = 151,
            attempted = 151,
            success = 101,
            unavailable = 40,
            rateLimitDelta = 1,
            skippedCodes = codes
        )

        assertTrue(line.startsWith("[分红预取轮次#7] 汇总：参与 151 只，"))
        assertTrue(line.contains("本轮发起抓取 151 只，成功拿到新数据 101 只"))
        // 101 + 50 = 151：成功与未取到必须恰好覆盖本轮发起数
        assertTrue(line.contains("未取到 50 只（数据源未应答（含 514/429 限流） 40 / 退避/预算/缓存复用等 10）"))
        assertTrue(line.contains("本轮真实 514/429 响应 1 次（退避窗口内被短路的标的计入上一档）"))
        assertTrue(line.contains("缓存或TTL有效无需重抓 0 只"))
        assertTrue(line.contains("未取到代码[50]："))
        // 50 只清单按条数上限只列 12 只，余下 38 只用 …+N 交代
        assertTrue("清单必须以省略号交代余数：$line", line.endsWith("…+38"))
        assertFalse("汇总必须是单行 INFO", line.contains("\n"))
        assertFalse("不得打印账户等敏感字段", line.contains("账户"))
        assertFalse("不得打印密钥等敏感字段", line.contains("apiKey"))
    }

    @Test
    fun `汇总_无需重抓数取参与减发起_限流信号负数钳零`() {
        val line = formatDividendPrefetchSummary(
            roundId = 3,
            totalSymbols = 20,
            attempted = 8,
            success = 5,
            unavailable = 2,
            rateLimitDelta = -3,
            skippedCodes = listOf("sz000001", "sh600519", "fund_010001")
        )

        assertTrue(line.contains("缓存或TTL有效无需重抓 12 只"))
        assertTrue(line.contains("未取到 3 只（数据源未应答（含 514/429 限流） 2 / 退避/预算/缓存复用等 1）"))
        assertTrue(line.contains("本轮真实 514/429 响应 0 次（退避窗口内被短路的标的计入上一档）"))
        assertTrue(line.contains("未取到代码[3]：fund_010001,sh600519,sz000001"))
        assertFalse("钳零后不得出现负数", line.contains("-3"))
    }

    @Test
    fun `汇总_清单重复代码只算一只_避免计数虚高`() {
        // 兜住「规范化去重后是 50 只独立标的」这个口径：同一代码被记两次不能算两只
        val line = formatDividendPrefetchSummary(
            roundId = 9,
            totalSymbols = 3,
            attempted = 3,
            success = 1,
            unavailable = 0,
            rateLimitDelta = 0,
            skippedCodes = listOf("fund_010001", "fund_010001", "sh600519")
        )

        assertTrue(line.contains("未取到 2 只（数据源未应答（含 514/429 限流） 0 / 退避/预算/缓存复用等 2）"))
        assertTrue(line.contains("未取到代码[2]：fund_010001,sh600519"))
        assertFalse(line.contains("…+"))
    }

    @Test
    fun `汇总_异常入参一律钳零_不出现自相矛盾的负数`() {
        val line = formatDividendPrefetchSummary(
            roundId = 1,
            totalSymbols = 5,
            attempted = 10,
            success = 0,
            unavailable = 99,
            rateLimitDelta = 0,
            skippedCodes = listOf("a")
        )

        assertTrue(line.contains("未取到 1 只（数据源未应答（含 514/429 限流） 1 / 退避/预算/缓存复用等 0）"))
        assertTrue(line.contains("缓存或TTL有效无需重抓 0 只"))
        assertFalse(line.contains(" -"))
        assertFalse(line.contains("\n"))
    }
}
