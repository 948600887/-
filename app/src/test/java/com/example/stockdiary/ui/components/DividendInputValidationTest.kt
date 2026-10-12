package com.example.stockdiary.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DividendInputValidationTest {

    private fun errors(
        date: String = "2026-03-15",
        amountText: String = "10",
        unitWord: String = "股"
    ) = validateDividendInput(date, amountText, unitWord)

    // ---------- 合法输入 ----------

    @Test
    fun 日期与金额都合法时没有任何错误() {
        val e = errors()
        assertTrue(e.isValid)
        assertNull(e.summary)
        assertNull(e.firstField)
    }

    @Test
    fun 金额可以是小数() {
        assertTrue(errors(amountText = "3.576").isValid)
    }

    @Test
    fun 金额为零是合法的() {
        assertTrue(errors(amountText = "0").isValid)
    }

    // ---------- 高分红不再被误伤 ----------

    @Test
    fun 茅台这类高价股的高分红可以正常保存() {
        // 改造前这里硬编码了 `parsedAmount > 100` 直接判非法，
        // 而贵州茅台 2023 年度每 10 股派 308.76 元——真实数据被当成错误挡掉了。
        val e = errors(amountText = "308.76")
        assertTrue(e.isValid)
        assertNull(e.amount)
    }

    @Test
    fun 极端大的金额才判为单位填错() {
        val e = errors(amountText = "99999")
        assertFalse(e.isValid)
        assertNotNull(e.amount)
        assertTrue(e.amount!!.message.contains("请检查是否填成了每股分红"))
    }

    @Test
    fun 金额偏大时给提醒但不算错误() {
        val e = errors(amountText = "308.76")
        assertTrue(e.isValid)
        val hint = dividendAmountHint("308.76", "股")
        assertNotNull("超过提醒阈值就该提醒一下单位", hint)
        assertTrue(hint!!.contains("每10股"))
    }

    @Test
    fun 金额不大时没有提醒() {
        assertNull(dividendAmountHint("10", "股"))
    }

    @Test
    fun 金额已经大到判错时不再重复提醒() {
        // 错误提示已经说清楚了，再挂一条提醒就是噪音
        assertNull(dividendAmountHint("99999", "股"))
    }

    @Test
    fun 提醒文案跟随份额单位() {
        val hint = dividendAmountHint("200", "份")
        assertTrue(hint!!.contains("每10份"))
        assertTrue(hint.contains("而不是每份"))
    }

    // ---------- 日期 ----------

    @Test
    fun 日期为空报错() {
        val e = errors(date = "")
        assertEquals("除息日", e.date?.label)
        assertEquals("必填", e.date?.message)
        assertEquals("date", e.firstField)
    }

    @Test
    fun 日期格式不对报错() {
        assertEquals("格式应为 yyyy-MM-dd", errors(date = "2026/03/15").date?.message)
        assertEquals("格式应为 yyyy-MM-dd", errors(date = "2026-3-5").date?.message)
    }

    @Test
    fun 格式对但不是真实存在的日期也要报错() {
        // 光看正则，"2026-02-30" 长得完全合规，但它不是一天。
        // 存进去之后排序和到账推算都会歪。
        assertEquals("不是有效的日期", errors(date = "2026-02-30").date?.message)
        assertEquals("不是有效的日期", errors(date = "2026-13-01").date?.message)
    }

    @Test
    fun 闰年二月二十九是合法日期() {
        assertTrue(errors(date = "2024-02-29").isValid)
    }

    @Test
    fun 平年二月二十九不是合法日期() {
        assertFalse(errors(date = "2026-02-29").isValid)
    }

    // ---------- 金额 ----------

    @Test
    fun 金额不是数字时报错在金额字段上() {
        val e = errors(amountText = "abc")
        assertNull("日期没毛病", e.date)
        assertEquals("分红金额", e.amount?.label)
        assertEquals("请输入有效数字", e.amount?.message)
        assertEquals("amount", e.firstField)
    }

    @Test
    fun 金额为负数报错() {
        assertEquals("不能为负数", errors(amountText = "-1").amount?.message)
    }

    @Test
    fun 金额为空报错() {
        assertEquals("必填", errors(amountText = "   ").amount?.message)
    }

    // ---------- 汇总 ----------

    @Test
    fun 两个字段都错时汇总列出两项_firstField指向日期() {
        val e = errors(date = "", amountText = "abc")
        assertEquals("date", e.firstField)
        assertEquals("请检查：除息日、分红金额", e.summary)
    }
}
