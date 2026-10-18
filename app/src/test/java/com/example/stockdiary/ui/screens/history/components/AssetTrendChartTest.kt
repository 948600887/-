package com.example.stockdiary.ui.screens.history.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `midTimestampIndex取时间中点对应的帧`() {
        // 0/10/20/30/40 天，中点 20 → 下标 2
        assertEquals(2, midTimestampIndex((0..4).map { it * 10L }))
        // 缺口：0/1/2/3/40 天，中点 20，最近的是 3（差 17）
        assertEquals(3, midTimestampIndex(listOf(0L, 1L, 2L, 3L, 40L)))
        // 快照数不足 3 个时补标签只会和首尾重复
        assertNull(midTimestampIndex(listOf(0L, 10L)))
    }
}
