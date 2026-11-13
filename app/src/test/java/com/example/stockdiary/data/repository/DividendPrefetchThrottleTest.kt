package com.example.stockdiary.data.repository

import com.example.stockdiary.network.FundDividendApiService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-13 天天基金 514 优化（P1 匀速闸门 + P2 未取到优先）的纯函数回归。
 *
 * 背景：真机诊断里 2.2 秒打出 32 个 fhsp 请求（约 14.5 QPS 且成对突发），
 * 第 32 个左右开始 514；把外层并发 8→2 无效（真实并发本就被内层闸门限死在 2）。
 * 因此改为「真实请求前按全局间隔匀速排队」+「按最久没抓过排序让每轮推进不同批次」。
 *
 * 这两个纯函数不碰 Context / Room / 网络，可独立 JVM 单测钉死边界。
 */
class DividendPrefetchThrottleTest {

    // ── computePoliteDelay ────────────────────────────────────────────────

    @Test
    fun `首次请求_没有上次时间戳_不等`() {
        assertEquals(0L, FundDividendApiService.computePoliteDelay(0L, 10_000L, 1000L))
        assertEquals(0L, FundDividendApiService.computePoliteDelay(-1L, 10_000L, 1000L))
    }

    @Test
    fun `间隔内_补足剩余毫秒`() {
        // 上次 10:00:00.000，现在 .300 → 还要等 700ms 才满 1s
        assertEquals(700L, FundDividendApiService.computePoliteDelay(10_000L, 10_300L, 1000L))
        assertEquals(999L, FundDividendApiService.computePoliteDelay(10_000L, 10_001L, 1000L))
    }

    @Test
    fun `恰好到达或超过间隔_不等`() {
        assertEquals(0L, FundDividendApiService.computePoliteDelay(10_000L, 11_000L, 1000L))
        assertEquals(0L, FundDividendApiService.computePoliteDelay(10_000L, 99_999L, 1000L))
    }

    @Test
    fun `系统时钟回拨_按满一个间隔保守等待`() {
        assertEquals(1000L, FundDividendApiService.computePoliteDelay(20_000L, 10_000L, 1000L))
    }

    @Test
    fun `间隔配置非正_闸门关闭_永不等待`() {
        assertEquals(0L, FundDividendApiService.computePoliteDelay(10_000L, 10_001L, 0L))
        assertEquals(0L, FundDividendApiService.computePoliteDelay(10_000L, 10_001L, -5L))
    }

    @Test
    fun `结果永不为负`() {
        val delays = listOf(
            FundDividendApiService.computePoliteDelay(0L, 0L, 1000L),
            FundDividendApiService.computePoliteDelay(1L, Long.MAX_VALUE, 1000L),
            FundDividendApiService.computePoliteDelay(Long.MAX_VALUE, 1L, 1000L)
        )
        assertTrue(delays.all { it >= 0L })
    }

    // ── prioritizeDividendSymbols ─────────────────────────────────────────

    @Test
    fun `从未成功抓过的排最前_已抓过的按时间升序`() {
        val symbols = listOf("fund_000032", "fund_000186", "fund_000191", "fund_000306")
        // 000032 三天前抓过、000186 昨天抓过、000191 从未抓过、000306 刚刚抓过
        val fetched = mapOf<String, Long?>(
            "fund_000032" to 300L,
            "fund_000186" to 800L,
            "fund_000191" to null,
            "fund_000306" to 900L
        )
        assertEquals(
            listOf("fund_000191", "fund_000032", "fund_000186", "fund_000306"),
            prioritizeDividendSymbols(symbols, fetched)
        )
    }

    @Test
    fun `时间戳缺失的键也视为从未抓过`() {
        val symbols = listOf("a", "b", "c")
        val fetched = mapOf<String, Long?>("b" to 500L)
        assertEquals(listOf("a", "c", "b"), prioritizeDividendSymbols(symbols, fetched))
    }

    @Test
    fun `时间戳相同_按symbol字典序兜底_顺序稳定`() {
        val symbols = listOf("fund_000306", "fund_000032", "fund_000186")
        val fetched = symbols.associateWith { 100L }
        assertEquals(
            listOf("fund_000032", "fund_000186", "fund_000306"),
            prioritizeDividendSymbols(symbols, fetched)
        )
        // 同一输入重复调用结果一致（稳定排序，可与旧行为对照）
        assertEquals(
            prioritizeDividendSymbols(symbols, fetched),
            prioritizeDividendSymbols(symbols, fetched)
        )
    }

    @Test
    fun `刚成功的排到队尾_下一轮推进的是另一批`() {
        // 第一轮：a/b/c/d 都从未抓过 → 按字典序，预算只够跑 a、b
        val first = listOf("a", "b", "c", "d")
        assertEquals(listOf("a", "b", "c", "d"), prioritizeDividendSymbols(first, emptyMap()))
        // 第二轮：a、b 刚成功（时间戳 900），c、d 仍为 null
        val fetched = mapOf<String, Long?>("a" to 900L, "b" to 900L)
        assertEquals(
            listOf("c", "d", "a", "b"),
            prioritizeDividendSymbols(first, fetched)
        )
    }

    @Test
    fun `空列表与单元素_不抛且原样返回`() {
        assertTrue(prioritizeDividendSymbols(emptyList(), emptyMap()).isEmpty())
        assertEquals(listOf("a"), prioritizeDividendSymbols(listOf("a"), emptyMap()))
    }
}
