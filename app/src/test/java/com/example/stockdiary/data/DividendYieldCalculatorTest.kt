package com.example.stockdiary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class DividendYieldCalculatorTest {

    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val now = LocalDate.now()

    // 今年的分红（在 TTM 内）
    private val thisYearDate = LocalDate.of(now.year, 6, 15).format(formatter)
    // 去年的分红（不在 TTM 内，但属于去年）
    private val lastYearDate = LocalDate.of(now.year - 1, 6, 15).format(formatter)
    // 前年的分红
    private val twoYearsAgoDate = LocalDate.of(now.year - 2, 6, 15).format(formatter)
    // 三年前的分红
    private val threeYearsAgoDate = LocalDate.of(now.year - 3, 6, 15).format(formatter)

    private val sampleDividends = listOf(
        DividendRecord(thisYearDate, "15.0", "2026-04-20", now.year.toString()),
        DividendRecord(lastYearDate, "12.0", "2025-04-20", (now.year - 1).toString()),
        DividendRecord(twoYearsAgoDate, "10.0", "2024-04-20", (now.year - 2).toString()),
        DividendRecord(threeYearsAgoDate, "8.0", "2023-04-20", (now.year - 3).toString())
    )

    // ========== calculateTTMDividendYield ==========

    @Test
    fun `calculateTTMDividendYield returns zero for empty dividends`() {
        val result = DividendYieldCalculator.calculateTTMDividendYield(emptyList(), 100.0)
        assertEquals(0.0, result, 0.01)
    }

    @Test
    fun `calculateTTMDividendYield returns zero for zero price`() {
        val result = DividendYieldCalculator.calculateTTMDividendYield(sampleDividends, 0.0)
        assertEquals(0.0, result, 0.01)
    }

    @Test
    fun `calculateTTMDividendYield returns zero for negative price`() {
        val result = DividendYieldCalculator.calculateTTMDividendYield(sampleDividends, -10.0)
        assertEquals(0.0, result, 0.01)
    }

    @Test
    fun `calculateTTMDividendYield calculates correctly with recent dividends`() {
        // Only this year's dividend (15.0) is in TTM range (June 15 is within 12 months)
        // 15 per 10 shares → 1.5 per share → 1.5/100 * 100% = 1.5%
        val result = DividendYieldCalculator.calculateTTMDividendYield(sampleDividends, 100.0)
        assertEquals(1.5, result, 0.01)
    }

    @Test
    fun `calculateTTMDividendYield only includes last 12 months`() {
        val oldDividends = listOf(
            DividendRecord(threeYearsAgoDate, "20.0", "2023-01-01", "2023")
        )
        val result = DividendYieldCalculator.calculateTTMDividendYield(oldDividends, 100.0)
        assertEquals(0.0, result, 0.01)
    }

    // ========== calculateStaticDividendYield ==========

    @Test
    fun `calculateStaticDividendYield returns zero for zero price`() {
        val result = DividendYieldCalculator.calculateStaticDividendYield(15.0, 0.0)
        assertEquals(0.0, result, 0.01)
    }

    @Test
    fun `calculateStaticDividendYield returns zero for zero dividend`() {
        val result = DividendYieldCalculator.calculateStaticDividendYield(0.0, 100.0)
        assertEquals(0.0, result, 0.01)
    }

    @Test
    fun `calculateStaticDividendYield calculates correctly`() {
        // 15 per 10 shares → 1.5 per share → 1.5/100 * 100% = 1.5%
        val result = DividendYieldCalculator.calculateStaticDividendYield(15.0, 100.0)
        assertEquals(1.5, result, 0.01)
    }

    @Test
    fun `calculateStaticDividendYield with typical values`() {
        // 茅台: 308.76 per 10 shares, price 1800
        val result = DividendYieldCalculator.calculateStaticDividendYield(308.76, 1800.0)
        assertEquals(1.71, result, 0.1)
    }

    // ========== getTTMDividend ==========

    @Test
    fun `getTTMDividend returns zero for empty list`() {
        assertEquals(0.0, DividendYieldCalculator.getTTMDividend(emptyList()), 0.01)
    }

    @Test
    fun `getTTMDividend sums recent dividends`() {
        // Only this year's dividend (15.0) is in TTM range
        val result = DividendYieldCalculator.getTTMDividend(sampleDividends)
        assertEquals(15.0, result, 0.01)
    }

    @Test
    fun `getTTMDividend 12个月边界那天要算进去`() {
        // 窗口是闭区间 [今天-12个月, 今天]，用 isAfter 严格大于会漏掉边界那一天
        val today = LocalDate.of(2026, 10, 9)
        val edge = today.minusMonths(12)          // 2025-10-09，正好在边界上
        val justBefore = edge.minusDays(1)        // 早一天，该被排除
        val records = listOf(
            DividendRecord(edge.toString(), "10.0", edge.toString(), "2025"),
            DividendRecord(justBefore.toString(), "20.0", justBefore.toString(), "2025")
        )
        assertEquals(10.0, DividendYieldCalculator.getTTMDividend(records, today), 0.01)
    }

    @Test
    fun `getTTMDividend ignores old dividends`() {
        val oldDividends = listOf(
            DividendRecord("2020-01-01", "100.0", "2020-01-01", "2020")
        )
        assertEquals(0.0, DividendYieldCalculator.getTTMDividend(oldDividends), 0.01)
    }

    // ========== getLastYearDividend ==========

    @Test
    fun `getLastYearDividend returns zero for empty list`() {
        assertEquals(0.0, DividendYieldCalculator.getLastYearDividend(emptyList()), 0.01)
    }

    @Test
    fun `getLastYearDividend returns last year dividend`() {
        // Last year's dividend is 12.0 (from lastYearDate = June of previous year)
        val result = DividendYieldCalculator.getLastYearDividend(sampleDividends)
        assertEquals(12.0, result, 0.01)
    }

    @Test
    fun `getLastYearDividend falls back to most recent year`() {
        // All old dividends, should pick the most recent
        val oldDividends = listOf(
            DividendRecord("2022-06-15", "10.0", "2022-04-20", "2022"),
            DividendRecord("2021-06-15", "8.0", "2021-04-20", "2021")
        )
        val result = DividendYieldCalculator.getLastYearDividend(oldDividends)
        assertEquals(10.0, result, 0.01)
    }

    // ========== calculateAvgDividendYield ==========

    @Test
    fun `calculateAvgDividendYield returns zero for empty list`() {
        assertEquals(0.0, DividendYieldCalculator.calculateAvgDividendYield(emptyList(), 100.0), 0.01)
    }

    @Test
    fun `calculateAvgDividendYield returns zero for zero price`() {
        assertEquals(0.0, DividendYieldCalculator.calculateAvgDividendYield(sampleDividends, 0.0), 0.01)
    }

    @Test
    fun `calculateAvgDividendYield calculates average over years`() {
        // 4 years: 15, 12, 10, 8 → average 11.25 per 10 shares → 1.125 per share → 1.125%
        val result = DividendYieldCalculator.calculateAvgDividendYield(sampleDividends, 100.0)
        assertEquals(1.125, result, 0.1)
    }

    @Test
    fun `calculateAvgDividendYield with multiple dividends per year`() {
        val multiDividends = listOf(
            DividendRecord(thisYearDate, "10.0", "2026-04-20", now.year.toString()),
            DividendRecord(thisYearDate, "5.0", "2026-07-20", now.year.toString()),  // same year
            DividendRecord(lastYearDate, "15.0", "2025-04-20", (now.year - 1).toString())
        )
        // Year N: 15 total, Year N-1: 15 total → average 15 per 10 shares → 1.5%
        val result = DividendYieldCalculator.calculateAvgDividendYield(multiDividends, 100.0)
        assertEquals(1.5, result, 0.1)
    }

    // ========== Edge Cases ==========

    @Test
    fun `calculations handle invalid dividend amount strings`() {
        val badDividends = listOf(
            DividendRecord(thisYearDate, "invalid", "2026-04-20", now.year.toString()),
            DividendRecord(thisYearDate, "15.0", "2026-04-20", now.year.toString())
        )
        val result = DividendYieldCalculator.calculateTTMDividendYield(badDividends, 100.0)
        assertEquals(1.5, result, 0.01)
    }

    @Test
    fun `calculations handle invalid date strings`() {
        val badDateDividends = listOf(
            DividendRecord("not-a-date", "15.0", "2026-04-20", now.year.toString()),
            DividendRecord(thisYearDate, "10.0", "2026-04-20", now.year.toString())
        )
        // Only the valid date dividend counted
        val result = DividendYieldCalculator.calculateTTMDividendYield(badDateDividends, 100.0)
        assertEquals(1.0, result, 0.01)
    }
}