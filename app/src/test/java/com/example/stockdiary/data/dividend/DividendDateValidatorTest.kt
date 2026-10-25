package com.example.stockdiary.data.dividend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [DividendDateValidator] 的回归：形态 + 真实日历双重校验。
 *
 * 重点盯「形态合法但日历不存在」的脏日期——正是它们曾让
 * [DividendProgressCalculator] 的 month 取到 13 炸 IndexOutOfBounds（commit `c65c1c6`）。
 */
class DividendDateValidatorTest {

    @Test
    fun `合法日期原样归一`() {
        assertEquals("2023-12-19", DividendDateValidator.normalizeOrNull("2023-12-19"))
        assertEquals("2024-02-29", DividendDateValidator.normalizeOrNull("2024-02-29")) // 闰日合法
        assertEquals("2026-01-01", DividendDateValidator.normalizeOrNull("2026-01-01"))
        assertEquals("2026-12-31", DividendDateValidator.normalizeOrNull("2026-12-31"))
    }

    @Test
    fun `首尾空白被 trim_带时间部分取前 10 字符`() {
        assertEquals("2023-12-19", DividendDateValidator.normalizeOrNull("  2023-12-19  "))
        // 东财 EX_DIVIDEND_DATE 的真实格式
        assertEquals("2023-12-19", DividendDateValidator.normalizeOrNull("2023-12-19 00:00:00"))
    }

    @Test
    fun `月份越界_13 到 99_判脏`() {
        assertNull(DividendDateValidator.normalizeOrNull("2026-13-01"))
        assertNull(DividendDateValidator.normalizeOrNull("2026-99-99"))
    }

    @Test
    fun `月份为 00 判脏`() {
        assertNull(DividendDateValidator.normalizeOrNull("2026-00-10"))
    }

    @Test
    fun `日历上不存在的日期判脏`() {
        assertNull(DividendDateValidator.normalizeOrNull("2026-02-30")) // 2 月无 30 日
        assertNull(DividendDateValidator.normalizeOrNull("2023-02-29")) // 2023 非闰年
        assertNull(DividendDateValidator.normalizeOrNull("2026-04-31")) // 4 月无 31 日
    }

    @Test
    fun `非补零写法判脏_与消费端 substring 假设保持一致`() {
        assertNull(DividendDateValidator.normalizeOrNull("2026-1-5"))
        assertNull(DividendDateValidator.normalizeOrNull("2026-03-9"))
    }

    @Test
    fun `形态不符判脏`() {
        assertNull(DividendDateValidator.normalizeOrNull("2026/03/09"))
        assertNull(DividendDateValidator.normalizeOrNull("20260309"))
        assertNull(DividendDateValidator.normalizeOrNull("实施中"))
        assertNull(DividendDateValidator.normalizeOrNull(""))
    }

    @Test
    fun `null 与纯空白判脏`() {
        assertNull(DividendDateValidator.normalizeOrNull(null))
        assertNull(DividendDateValidator.normalizeOrNull("   "))
        assertNull(DividendDateValidator.normalizeOrNull("\t\n"))
    }
}
