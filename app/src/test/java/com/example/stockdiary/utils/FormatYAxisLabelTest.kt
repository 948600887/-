package com.example.stockdiary.utils

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [formatYAxisLabel] 的回归：Y 轴标签精度必须随相邻网格线步长自适应。
 *
 * 背景（用户实测）：净值在 18.9 万附近波动时，原来五条网格线全取整成"18万"，
 * Y 轴参照系失效——用户看到五个一样的标签，等于没有刻度。
 */
class FormatYAxisLabelTest {

    @Test
    fun `步长万级时按整万紧凑显示`() {
        assertEquals("18万", 189_630.67.formatYAxisLabel(step = 50_000.0))
        assertEquals("19万", 196_000.0.formatYAxisLabel(step = 50_000.0))
    }

    @Test
    fun `步长千元级显示一位小数万`() {
        assertEquals("18.5万", 184_630.67.formatYAxisLabel(step = 1_000.0))
    }

    @Test
    fun `步长百元级显示两位小数万`() {
        assertEquals("18.96万", 189_630.67.formatYAxisLabel(step = 100.0))
    }

    @Test
    fun `步长不足百元直接显示原值`() {
        // 万元小数位到两位仍不够（步长 60.7 元 < 100 元），退原值
        assertEquals("189630", 189_630.67.formatYAxisLabel(step = 60.7))
        assertEquals("189387", 189_387.73.formatYAxisLabel(step = 60.7))
    }

    @Test
    fun `用户实测场景五条网格线标签互不相同`() {
        // 净值 189387.73 ~ 189630.67：修前五条全是"18万"
        val step = (189_630.67 - 189_387.73) / 4
        val labels = (0..4).map { (189_630.67 - it * step).formatYAxisLabel(step = step) }
        assertEquals(5, labels.distinct().size)
    }

    @Test
    fun `默认步长保持历史行为`() {
        // 不传 step = 旧行为：整万/K/原值三档
        assertEquals("18万", 189_630.67.formatYAxisLabel())
        assertEquals("9K", 9_500.0.formatYAxisLabel())
        assertEquals("999", 999.6.formatYAxisLabel())
    }
}
