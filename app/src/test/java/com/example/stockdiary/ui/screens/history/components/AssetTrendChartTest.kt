package com.example.stockdiary.ui.screens.history.components

import com.example.stockdiary.database.AssetSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

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
        // 4 个点间距 100px、标签宽 50px：放得下，全部保留（不掺日历步长）
        val positions = listOf(0f, 100f, 200f, 300f)
        val widths = List(4) { 50f }
        val dates = List(4) { LocalDate.of(2026, 10, 21).plusDays(it.toLong()) }
        assertEquals(listOf(0, 1, 2, 3), trendXLabelIndices(dates, positions, widths, minGap = 8f))
    }

    @Test
    fun `trendXLabelIndices一周八点全画`() {
        // 1周视图：8 个点、跨度 7 天、屏够宽——逐日全画在各自的点上
        val n = 8
        val positions = List(n) { it * 100f }
        val widths = List(n) { 50f }
        val dates = List(n) { LocalDate.of(2026, 10, 17).plusDays(it.toLong()) }
        assertEquals((0 until n).toList(), trendXLabelIndices(dates, positions, widths, minGap = 8f))
    }

    @Test
    fun `trendXLabelIndices密集时间隔不足的跳过但首末保留`() {
        // 4 个点间距只有 10px、标签宽 50px：放不下，稀疏化后只留首末
        val positions = listOf(0f, 10f, 20f, 30f)
        val widths = List(4) { 50f }
        val dates = List(4) { LocalDate.of(2026, 10, 21).plusDays(it.toLong()) }
        val kept = trendXLabelIndices(dates, positions, widths, minGap = 8f)
        assertTrue(kept.contains(0))
        assertTrue(kept.contains(3))
        assertTrue("中间的应被稀疏化", kept.size < 4)
    }

    @Test
    fun `trendXLabelIndices恰好贴边时保留`() {
        // 防重叠兜底：首标签末端 25，中标签起端 33，间隔恰好 8 = minGap：>= 判定，保留
        val positions = listOf(0f, 58f, 200f)
        val widths = List(3) { 50f }
        assertEquals(listOf(0, 1, 2), listOf(0, 1, 2).filterWithOverlapGuard(positions, widths, minGap = 8f))
    }

    @Test
    fun `trendXLabelIndices差一点贴边就跳过`() {
        // 防重叠兜底：首标签末端 25，中标签起端 32，间隔 7 < minGap：跳过中间的
        val positions = listOf(0f, 57f, 200f)
        val widths = List(3) { 50f }
        assertEquals(listOf(0, 2), listOf(0, 1, 2).filterWithOverlapGuard(positions, widths, minGap = 8f))
    }

    @Test
    fun `trendXLabelIndices空输入返回空`() {
        assertEquals(
            emptyList<Int>(),
            trendXLabelIndices(emptyList(), emptyList(), emptyList(), minGap = 8f)
        )
    }

    // ===== 同花顺规则：按跨度选自然日历步长，而不是能塞多少塞多少 =====

    /** 连续 days+1 天的日快照，第 i 个点距左边缘 i*spacingPx */
    private fun dailyChart(
        start: LocalDate,
        days: Int,
        spacingPx: Float = 10f,
        labelWidth: Float = 50f
    ): Triple<List<LocalDate>, List<Float>, List<Float>> {
        val dates = (0..days).map { start.plusDays(it.toLong()) }
        val positions = List(days + 1) { it * spacingPx }
        val widths = List(days + 1) { labelWidth }
        return Triple(dates, positions, widths)
    }

    @Test
    fun `一个月跨度按周稀疏_日期落在数据点上`() {
        // 10/01~10/31 逐日快照、点距 30px（一屏放不下 31 个）：每周留一个，
        // 且必须落在真实有数据的日期上（10/01、10/08、10/15、10/22、10/29）
        val (dates, positions, widths) = dailyChart(LocalDate.of(2026, 10, 1), days = 30, spacingPx = 30f)

        val kept = trendXLabelIndices(dates, positions, widths, minGap = 8f)

        val expectedDates = kept.map { dates[it] }
        assertTrue("首个必须是 10/01", expectedDates.first() == LocalDate.of(2026, 10, 1))
        assertTrue("末个必须是 10/31", expectedDates.last() == LocalDate.of(2026, 10, 31))
        // 中间的按 7 天步长跳：10/08、10/15、10/22、10/29
        assertEquals(
            listOf(
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 8),
                LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 22),
                LocalDate.of(2026, 10, 29), LocalDate.of(2026, 10, 31)
            ),
            expectedDates
        )
    }

    @Test
    fun `半年跨度按月初稀疏_同花顺口径`() {
        // 2025-11-01 ~ 2026-05-01 逐日快照：同花顺截图就是只标 11-01、01-01、03-01、05-01。
        // 步长 = ceil(181/30.44/预算)：预算 = 屏宽估的 6 - 2 = 4 → ceil(1.49) = 2 → 每 2 个月初
        val (dates, positions, widths) = dailyChart(
            LocalDate.of(2025, 11, 1), days = 181, spacingPx = 8f
        )

        val kept = trendXLabelIndices(dates, positions, widths, minGap = 8f)

        val expectedDates = kept.map { dates[it] }
        assertEquals(LocalDate.of(2025, 11, 1), expectedDates.first())
        assertEquals(LocalDate.of(2026, 5, 1), expectedDates.last())
        // 中间只许出现月初，且最多 MAX_X_LABELS 个
        expectedDates.forEach { assertEquals(1, it.dayOfMonth) }
        assertTrue("标签过多：${expectedDates.size}", expectedDates.size <= MAX_X_LABELS)
    }

    @Test
    fun `一年跨度按季稀疏`() {
        val (dates, positions, widths) = dailyChart(
            LocalDate.of(2025, 10, 24), days = 364, spacingPx = 8f
        )

        val kept = trendXLabelIndices(dates, positions, widths, minGap = 8f)

        val expectedDates = kept.map { dates[it] }
        assertEquals(LocalDate.of(2025, 10, 24), expectedDates.first())
        assertEquals(LocalDate.of(2026, 10, 23), expectedDates.last())
        assertTrue("标签过多：${expectedDates.size}", expectedDates.size <= MAX_X_LABELS)
        // 季初：2026-01-01、04-01、07-01、10-01 都应出现
        listOf(
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1),
            LocalDate.of(2026, 7, 1), LocalDate.of(2026, 10, 1)
        ).forEach { assertTrue("缺季初 $it", expectedDates.contains(it)) }
    }

    @Test
    fun `缺口不糊弄_标签仍落在各自的数据点上`() {
        // 10/15、10/17、10/19 三天有数据、点少且放得下：全部画，
        // 每个标签都对应一个真实快照（不会画到没有数据的日期上）
        val dates = listOf(
            LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 17), LocalDate.of(2026, 10, 19)
        )
        val positions = listOf(0f, 200f, 400f)
        val widths = List(3) { 40f }
        assertEquals(listOf(0, 1, 2), trendXLabelIndices(dates, positions, widths, minGap = 8f))
    }

    @Test
    fun `日期全脏时退化成等距稀疏`() {
        val dates = List(20) { null }
        val positions = List(20) { it * 10f }
        val widths = List(20) { 50f }
        val kept = trendXLabelIndices(dates, positions, widths, minGap = 8f)
        assertTrue(kept.contains(0))
        assertTrue(kept.contains(19))
        assertTrue("应稀疏化：${kept.size}", kept.size <= MAX_X_LABELS)
    }

    // ===== 按日期排布（不等宽缺口） =====

    /** 显式指定创建时刻的快照，用于验证时刻抖动不影响 X 间距 */
    private fun snapshotAt(date: String, timestamp: Long, netAssets: Double) = AssetSnapshot(
        date = date,
        timestamp = timestamp,
        totalAssets = netAssets,
        totalLiabilities = 0.0,
        netAssets = netAssets
    )

    @Test
    fun `dayTimestamps按日期排布忽略创建时刻抖动`() {
        // 15 号 23:50 建、17 号 00:10 建、19 号 20:00 建：创建时刻差是 1.8/3.4 天的歪斜比例，
        // 日期差是整齐的 2 天/2 天——间距必须按后者，等日期差必须等宽
        val day = 86_400_000L
        val d15 = LocalDate.parse("2026-10-15").toEpochDay() * day
        val snaps = listOf(
            snapshotAt("2026-10-15", d15 + 23 * 3600_000L + 50 * 60_000L, 100.0),
            snapshotAt("2026-10-17", d15 + 2 * day + 10 * 60_000L, 110.0),
            snapshotAt("2026-10-19", d15 + 4 * day + 20 * 3600_000L, 120.0)
        )
        val positions = trendXPositions(snaps.dayTimestamps(), left = 0f, width = 100f)
        assertEquals(0f, positions[0], 1e-4f)
        assertEquals(50f, positions[1], 1e-4f)
        assertEquals(100f, positions[2], 1e-4f)
    }

    @Test
    fun `dayTimestamps不同日期差产出成正比间距`() {
        // 用户实测场景：10/15、10/17、10/18、10/19——15→17 跨 2 天应是 17→18（1 天）的两倍宽
        val day = 86_400_000L
        val d15 = LocalDate.parse("2026-10-15").toEpochDay() * day
        val snaps = listOf(
            snapshotAt("2026-10-15", d15 + 20 * 3600_000L, 100.0),
            snapshotAt("2026-10-17", d15 + 2 * day + 3600_000L, 100.0),
            snapshotAt("2026-10-18", d15 + 3 * day + 22 * 3600_000L, 130.0),
            snapshotAt("2026-10-19", d15 + 4 * day + 8 * 3600_000L, 130.0)
        )
        val positions = trendXPositions(snaps.dayTimestamps(), left = 0f, width = 100f)
        // 总跨度 4 天：0% / 50% / 75% / 100%
        assertEquals(0f, positions[0], 1e-4f)
        assertEquals(50f, positions[1], 1e-4f)
        assertEquals(75f, positions[2], 1e-4f)
        assertEquals(100f, positions[3], 1e-4f)
        // 15→17 的宽度（50%）正好是 17→18（25%）的两倍
        assertEquals(2f, (positions[1] - positions[0]) / (positions[2] - positions[1]), 1e-4f)
    }

    @Test
    fun `dayTimestamps脏日期退回创建时刻`() {
        val snaps = listOf(
            snapshotAt("not-a-date", 12345L, 100.0),
            snapshotAt("2026-10-19", 67890L, 110.0)
        )
        val dayTs = snaps.dayTimestamps()
        assertEquals(12345L, dayTs[0])
        assertTrue(dayTs[1] > 0L)
    }
}
