package com.example.stockdiary.ui.viewmodels

import com.example.stockdiary.database.AssetSnapshot
import com.example.stockdiary.utils.toTimestamp
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * 历史页周期过滤的口径单测。
 *
 * 核心不变量（2026-10-24 用户实测复现的 bug）：过滤按 **date 字段**，不按创建
 * 时刻 timestamp。回填补出来的幽灵快照（date 是被补的那天、timestamp 是补的
 * 时刻）不得让「1周」放出窗口外的日期。
 */
class HistoryPeriodFilterTest {

    private val today = LocalDate.of(2026, 10, 24)

    /** date 与 timestamp 一致（正常当日快照） */
    private fun aligned(date: String, netAssets: Double = 100.0) = AssetSnapshot(
        date = date,
        timestamp = date.toTimestamp(),
        totalAssets = netAssets,
        totalLiabilities = 0.0,
        netAssets = netAssets
    )

    /** date 与 timestamp 故意错位（回填补出来的幽灵快照） */
    private fun phantom(date: String, writtenOn: String, netAssets: Double = 100.0) = AssetSnapshot(
        date = date,
        timestamp = writtenOn.toTimestamp(),
        totalAssets = netAssets,
        totalLiabilities = 0.0,
        netAssets = netAssets
    )

    private fun dates(snapshots: List<AssetSnapshot>) = snapshots.map { it.date }

    @Test
    fun `一周窗口排除幽灵快照_用户实测场景`() {
        // 用户库里就是这样：10/15、10/16 是回填补的，timestamp 晚到 10/17 之后
        val snapshots = listOf(
            phantom("2026-10-15", "2026-10-17"),
            phantom("2026-10-16", "2026-10-17"),
            aligned("2026-10-17"),
            aligned("2026-10-18"),
            aligned("2026-10-19"),
            aligned("2026-10-20"),
            aligned("2026-10-21"),
            aligned("2026-10-22"),
            aligned("2026-10-23"),
            aligned("2026-10-24")
        )

        val filtered = filterSnapshots(snapshots, "1W", today)

        // 修前：10 天全放出来（用户截图实况）；修后：只剩窗口内 8 天
        assertEquals(
            listOf(
                "2026-10-17", "2026-10-18", "2026-10-19", "2026-10-20",
                "2026-10-21", "2026-10-22", "2026-10-23", "2026-10-24"
            ),
            dates(filtered)
        )
    }

    @Test
    fun `一周窗口边界_当天与七天内含_八天前不含`() {
        val snapshots = (0..9).map { i -> aligned(today.minusDays(i.toLong()).toString()) }

        val filtered = filterSnapshots(snapshots, "1W", today)

        assertEquals(8, filtered.size)
        assertEquals(today.minusDays(7).toString(), filtered.first().date)
        assertEquals(today.toString(), filtered.last().date)
    }

    @Test
    fun `结果按日期升序_幽灵快照不靠后`() {
        // 10/15 的创建时刻比 10/16 还晚：按 timestamp 排会插到 10/16 后面
        val snapshots = listOf(
            aligned("2026-10-16"),
            phantom("2026-10-15", "2026-10-17"),
            aligned("2026-10-17"),
            aligned("2026-10-24")
        )

        val filtered = filterSnapshots(snapshots, "1M", today)

        assertEquals(listOf("2026-10-15", "2026-10-16", "2026-10-17", "2026-10-24"), dates(filtered))
    }

    @Test
    fun `一月窗口按日期算_整月前不含`() {
        val snapshots = listOf(
            aligned("2026-09-23"),
            aligned("2026-09-24"),
            aligned("2026-09-25"),
            aligned("2026-10-01")
        )

        val filtered = filterSnapshots(snapshots, "1M", today)

        // startDate = 10-24 减一个月 = 09-24，当天含、前一天不含
        assertEquals(listOf("2026-09-24", "2026-09-25", "2026-10-01"), dates(filtered))
    }

    @Test
    fun `全部周期原样保留`() {
        val snapshots = listOf(aligned("2025-01-01"), aligned("2026-10-24"))

        assertEquals(listOf("2025-01-01", "2026-10-24"), dates(filterSnapshots(snapshots, "ALL", today)))
    }

    @Test
    fun `同日多份快照保留创建时刻最新的一条`() {
        val early = aligned("2026-10-20", netAssets = 100.0).copy(timestamp = 1000L)
        val late = aligned("2026-10-20", netAssets = 200.0).copy(timestamp = 2000L)
        val snapshots = listOf(early, late, aligned("2026-10-24", netAssets = 300.0))

        val filtered = filterSnapshots(snapshots, "1W", today)

        assertEquals(listOf("2026-10-20", "2026-10-24"), dates(filtered))
        assertEquals(200.0, filtered.first().netAssets, 1e-9)
    }

    @Test
    fun `date脏数据退回创建时刻判断_不静默丢弃`() {
        val inside = AssetSnapshot(
            date = "坏数据",
            timestamp = today.toString().toTimestamp(),
            totalAssets = 100.0, totalLiabilities = 0.0, netAssets = 100.0
        )
        val outside = AssetSnapshot(
            date = "",
            timestamp = today.minusDays(30).toString().toTimestamp(),
            totalAssets = 100.0, totalLiabilities = 0.0, netAssets = 100.0
        )

        val filtered = filterSnapshots(listOf(inside, outside), "1W", today)

        assertEquals(1, filtered.size)
        assertEquals("坏数据", filtered.single().date)
    }

    @Test
    fun `开头的零资产空快照仍被剔除`() {
        val empty = aligned("2026-10-17", netAssets = 0.0).copy(totalAssets = 0.0)
        val snapshots = listOf(empty, aligned("2026-10-18"), aligned("2026-10-24"))

        val filtered = filterSnapshots(snapshots, "1W", today)

        assertEquals(listOf("2026-10-18", "2026-10-24"), dates(filtered))
    }

    @Test
    fun `空输入返回空`() {
        assertEquals(emptyList<AssetSnapshot>(), filterSnapshots(emptyList(), "1W", today))
    }
}
