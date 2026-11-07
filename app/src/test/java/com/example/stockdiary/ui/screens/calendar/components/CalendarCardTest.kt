package com.example.stockdiary.ui.screens.calendar.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/**
 * 日历格子是整页最热的组合区域（一屏 42 个）。这里锁住两条：
 * 1) 格子参数全部是稳定类型（epochDay/dayOfMonth/...），不再握 java.time.LocalDate；
 * 2) 「今天」只在当月格子里成立——看别的月份时，今天可能落在上下月填充格里，不能描边。
 */
class CalendarCardTest {

    @Test
    fun `当月格子使用 epochDay 且只有一天是今天`() {
        val month = YearMonth.of(2026, 11)
        val today = LocalDate.of(2026, 11, 7)
        val data = mapOf(LocalDate.of(2026, 11, 3) to 123.0)

        val days = buildCalendarDays(month, data, today)

        assertEquals(35, days.size)  // 30 天 + 5 个下月填充（11/1 是周日，无上月填充）
        assertEquals(LocalDate.of(2026, 11, 1).toEpochDay(), days.first().epochDay)
        assertEquals(1, days.first().dayOfMonth)
        assertEquals(123.0, days.first { it.dayOfMonth == 3 && it.isCurrentMonth }.value, 0.0)
        assertEquals(
            listOf(LocalDate.of(2026, 11, 7).toEpochDay()),
            days.filter { it.isToday }.map { it.epochDay }
        )
        // 周末标记：11/7 是周六
        assertTrue(days.first { it.epochDay == today.toEpochDay() }.isWeekend)
        assertEquals(7, days.first { it.epochDay == today.toEpochDay() }.dayOfMonth)
    }

    @Test
    fun `今天落在相邻月的填充格时不标记为今天`() {
        val month = YearMonth.of(2026, 12)
        // 12/1 是周二 → 前面有 2 个 11 月填充格：11/29、11/30
        val today = LocalDate.of(2026, 11, 30)

        val days = buildCalendarDays(month, emptyMap(), today)
        val padding = days.first { it.epochDay == today.toEpochDay() }

        assertFalse("填充格不算今天", padding.isCurrentMonth)
        assertFalse("否则看 12 月时 11-30 会被画上今天描边", padding.isToday)
        assertTrue("12 月里没有任何一天是今天", days.none { it.isToday })
    }

    @Test
    fun `格子点击适配器身份稳定且总调用最新回调`() {
        // 回归：上层每次重组新建 onDateSelected，remember(onDateSelected) 会被击穿 →
        // 所有格子的 onDayClick 身份变化，跳过重组失效。适配器必须「身份不变、回调最新」。
        var received: LocalDate? = null
        val adapter = StableDateClickAdapter { received = it }
        val stableClick = adapter.onEpochDay

        // 模拟上层重组时传入新的 lambda
        adapter.update { received = it?.plusDays(1) }

        assertSame("身份必须稳定，否则格子无法跳过重组", stableClick, adapter.onEpochDay)
        adapter.onEpochDay(LocalDate.of(2026, 11, 7).toEpochDay())
        assertEquals("必须调用最新传入的回调", LocalDate.of(2026, 11, 8), received)
    }
}
