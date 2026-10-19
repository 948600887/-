package com.example.stockdiary.data.dividend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * [cashDateOrNull] 的回归：日历「分」标记与当日分红明细都按这个日期显示，
 * 口径必须与 DividendProgressCalculator 一致——**派息日优先，拿不到才除息日**。
 *
 * 背景（用户实测 bug）：日历原先按除息日打「分」，而钱通常晚 1~4 周才到账，
 * 于是除息日那天就显示分红，用户看到「明明没有到日期就显示分红了」。
 */
class DividendCashDateTest {

    private fun record(
        ex: String,
        pay: String? = null
    ) = DividendRecord(
        exDividendDate = ex,
        dividendPerShare = "1.0",
        announceDate = "2026-10-01",
        fiscalYear = "2026",
        paymentDate = pay
    )

    @Test
    fun `有派息日_按派息日`() {
        assertEquals(LocalDate.of(2026, 11, 5), record("2026-10-16", "2026-11-05").cashDateOrNull())
    }

    @Test
    fun `无派息日_退回除息日`() {
        assertEquals(LocalDate.of(2026, 10, 16), record("2026-10-16", null).cashDateOrNull())
    }

    @Test
    fun `派息日带时间戳_取前十位`() {
        assertEquals(LocalDate.of(2026, 11, 5), record("2026-10-16", "2026-11-05T00:00:00").cashDateOrNull())
    }

    @Test
    fun `派息日脏数据_退回除息日`() {
        // 空串 / 被截断的日期一律不采信，与 DividendProgressCalculator 同风格
        assertEquals(LocalDate.of(2026, 10, 16), record("2026-10-16", "").cashDateOrNull())
        assertEquals(LocalDate.of(2026, 10, 16), record("2026-10-16", "2026-11").cashDateOrNull())
    }

    @Test
    fun `两个日期都不可解析_返回null`() {
        assertNull(record("not-a-date", null).cashDateOrNull())
        assertNull(record("not-a-date", "also-bad").cashDateOrNull())
    }
}
