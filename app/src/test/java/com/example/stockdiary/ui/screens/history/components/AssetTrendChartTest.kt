package com.example.stockdiary.ui.screens.history.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssetTrendChartTest {

    @Test
    fun `trendXPositions等间隔快照映射为等距x`() {
        // 时间戳等差 → 按下标等距等价
        val positions = trendXPositions(listOf(0L, 10L, 20L, 30L), left = 40f, width = 300f)
        assertEquals(40f, positions[0], 1e-3f)
        assertEquals(140f, positions[1], 1e-3f)
        assertEquals(240f, positions[2], 1e-3f)
        assertEquals(340f, positions[3], 1e-3f)
    }

    @Test
    fun `trendXPositions有缺口时x按时间轴疏密`() {
        // 第 2→3 个点隔了 20，是其它间隔的两倍，横向距离也应翻倍
        val positions = trendXPositions(listOf(0L, 10L, 30L, 40L), left = 0f, width = 40f)
        assertEquals(0f, positions[0], 1e-3f)
        assertEquals(10f, positions[1], 1e-3f)
        assertEquals(30f, positions[2], 1e-3f)
        assertEquals(40f, positions[3], 1e-3f)
    }

    @Test
    fun `trendXPositions时间戳全同时退化为下标等距`() {
        // 同一天多份快照：时间跨度 0 会除零，必须退化为按下标等距
        val positions = trendXPositions(listOf(5L, 5L, 5L), left = 0f, width = 20f)
        assertEquals(3, positions.size)
        assertEquals(0f, positions[0], 1e-3f)
        assertEquals(10f, positions[1], 1e-3f)
        assertEquals(20f, positions[2], 1e-3f)
    }

    @Test
    fun `trendXPositions空列表返回空单点返回left`() {
        assertEquals(emptyList<Float>(), trendXPositions(emptyList(), left = 40f, width = 300f))
        assertEquals(listOf(7f), trendXPositions(listOf(99L), left = 7f, width = 300f))
    }

    @Test
    fun `nearestPointIndex点击边界与正中间`() {
        val positions = listOf(0f, 10f, 20f)
        // 最左
        assertEquals(0, nearestPointIndex(-100f, positions))
        assertEquals(0, nearestPointIndex(0f, positions))
        // 最右
        assertEquals(2, nearestPointIndex(100f, positions))
        assertEquals(2, nearestPointIndex(20f, positions))
        // 两个点正中间：距离相等取下标较小者
        assertEquals(0, nearestPointIndex(5f, positions))
        assertEquals(1, nearestPointIndex(15f, positions))
    }

    @Test
    fun `nearestPointIndex缺口稀疏位置吸附到最近点`() {
        val positions = trendXPositions(listOf(0L, 10L, 40L), left = 0f, width = 40f)
        // 25 距 10 和 40 都是 15 → 取下标较小者 1；26 距 40 更近 → 2
        assertEquals(1, nearestPointIndex(25f, positions))
        assertEquals(2, nearestPointIndex(26f, positions))
    }

    @Test
    fun `nearestPointIndex空positions返回负一`() {
        assertEquals(-1, nearestPointIndex(5f, emptyList()))
    }

    @Test
    fun `trendXLabelIndices稀疏时每份快照的日期都画`() {
        // 4 个点间距 100px、标签宽 50px：互不重叠，全部保留
        val positions = listOf(0f, 100f, 200f, 300f)
        val widths = List(4) { 50f }
        assertEquals(listOf(0, 1, 2, 3), trendXLabelIndices(positions, widths, minGap = 8f))
    }

    @Test
    fun `trendXLabelIndices密集时间隔不足的跳过但首末保留`() {
        // 4 个点间距只有 10px、标签宽 50px：中间的必然重叠，只留首末
        val positions = listOf(0f, 10f, 20f, 30f)
        val widths = List(4) { 50f }
        val kept = trendXLabelIndices(positions, widths, minGap = 8f)
        assertTrue(kept.contains(0))
        assertTrue(kept.contains(3))
        assertTrue("中间的应被稀疏化", kept.size < 4)
    }

    @Test
    fun `trendXLabelIndices恰好贴边时保留`() {
        // 首标签末端 25，中标签起端 33，间隔恰好 8 = minGap：>= 判定，保留（不重叠）
        val positions = listOf(0f, 58f, 200f)
        val widths = List(3) { 50f }
        assertEquals(listOf(0, 1, 2), trendXLabelIndices(positions, widths, minGap = 8f))
    }

    @Test
    fun `trendXLabelIndices差一点贴边就跳过`() {
        // 首标签末端 25，中标签起端 32，间隔 7 < minGap：跳过中间的
        val positions = listOf(0f, 57f, 200f)
        val widths = List(3) { 50f }
        assertEquals(listOf(0, 2), trendXLabelIndices(positions, widths, minGap = 8f))
    }

    @Test
    fun `trendXLabelIndices空输入返回空`() {
        assertEquals(emptyList<Int>(), trendXLabelIndices(emptyList(), emptyList(), minGap = 8f))
    }
}
