package com.example.stockdiary.ui.screens.history.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 月度柱状图 x 轴标签抽稀（[pickMonthlyLabelIndices]）。
 *
 * 背景：改造前每根柱子都无条件 drawText——Canvas 上画文字不会换行也不会省略，
 * 相邻标签直接叠在一起。数据一多（≥6 根）整条时间轴糊成一条黑带，
 * 用户读不出任何一个月份（「月度柱状图下面一团黑」）。
 */
class MonthlyChartLabelThinningTest {

    /** 抽稀结果必须满足的硬约束：升序、首尾存在、相邻中心距不小于「标签宽 + 空隙」。 */
    private fun assertInvariants(
        indices: List<Int>,
        count: Int,
        chartWidth: Float,
        widestLabel: Float
    ) {
        val slot = chartWidth / count
        val need = widestLabel + 8f // 与 MONTHLY_LABEL_GAP 一致
        assertTrue("结果必须非空", indices.isNotEmpty())
        assertEquals("必须升序且无重复", indices.sorted().distinct(), indices)
        assertEquals("首根必须画：否则不知道数据从哪个开始", 0, indices.first())
        assertEquals("末根必须画：否则看着像数据提前结束", count - 1, indices.last())
        indices.zipWithNext { a, b ->
            assertTrue(
                "下标 $a 与 $b 的标签会叠字（中心距 ${(b - a) * slot}px < ${need}px）",
                (b - a) * slot >= need
            )
        }
    }

    @Test
    fun 宽度够时全部标签都画() {
        // 3 根柱子、共 300px → 每根 100px，而标签只有 40px，没有理由抽稀
        assertEquals(
            listOf(0, 1, 2),
            pickMonthlyLabelIndices(count = 3, chartWidth = 300f, widestLabel = 40f)
        )
    }

    @Test
    fun 装不下时抽稀且满足首尾与不叠字约束() {
        // 12 根柱子挤在 300px 里 → 每根只分到 25px，而标签 40px：逐根画必然重叠
        val indices = pickMonthlyLabelIndices(count = 12, chartWidth = 300f, widestLabel = 40f)
        assertTrue("抽稀后必须真的变少，实际 ${indices.size}", indices.size < 12)
        assertTrue("至少要有 2 个刻度", indices.size >= 2)
        assertInvariants(indices, count = 12, chartWidth = 300f, widestLabel = 40f)
    }

    @Test
    fun 抽稀后仍能读出第一个月和最后一个月() {
        // 24 根（两年）是真实场景：卡片一屏放不下全部标签，但用户至少要看到
        // 「从 24年1月 到 25年12月」这个跨度
        val indices = pickMonthlyLabelIndices(count = 24, chartWidth = 320f, widestLabel = 44f)
        assertEquals(0, indices.first())
        assertEquals(23, indices.last())
        assertInvariants(indices, count = 24, chartWidth = 320f, widestLabel = 44f)
    }

    @Test
    fun 单根柱子只有一个标签() {
        assertEquals(listOf(0), pickMonthlyLabelIndices(count = 1, chartWidth = 100f, widestLabel = 40f))
    }

    @Test
    fun 空数据与未测量完成时不画标签() {
        assertEquals(
            emptyList<Int>(),
            pickMonthlyLabelIndices(count = 0, chartWidth = 300f, widestLabel = 40f)
        )
        // 首帧 canvasSize 还是 0：此时 geometry 也是 null、什么都不画，
        // 返回空表比按 0 宽算出「只画一根」的假结论更诚实
        assertEquals(
            emptyList<Int>(),
            pickMonthlyLabelIndices(count = 12, chartWidth = 0f, widestLabel = 40f)
        )
    }
}
