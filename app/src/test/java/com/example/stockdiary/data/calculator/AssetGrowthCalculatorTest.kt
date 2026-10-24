package com.example.stockdiary.data.calculator

import com.example.stockdiary.database.AssetSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [AssetGrowthCalculator] 单测：重点是脏日期容错——
 * 传入非法 today 时退回"真实今天"，绝不拿 take(7) 截出的垃圾串当基准。
 */
class AssetGrowthCalculatorTest {

    private fun snap(date: String, net: Double) = AssetSnapshot(
        date = date,
        totalAssets = net,
        totalLiabilities = 0.0,
        netAssets = net
    )

    @Test
    fun `正常日期按本月与今年基准计算增值`() {
        val today = "2026-10-24"
        // 算法以"本月/今年 1 号之前"的最后一条快照为基准（严格小于 1 号），
        // 故落在 1 号当天或当月内的快照不参与基准。
        val snapshots = listOf(
            snap("2026-09-30", 100.0), // 本月基准：10/1 之前最后一条
            snap("2026-10-05", 130.0), // 本月内：不应作为基准
            snap("2025-12-31", 80.0)   // 今年基准：今年 1/1 之前最后一条
        )
        val result = AssetGrowthCalculator.fromSnapshots(snapshots, currentNetAssets = 150.0, today = today)
        // 本月：150 - 100 = 50；今年：150 - 80 = 70
        assertEquals(50.0, result.monthDelta ?: Double.NaN, 0.0001)
        assertEquals(70.0, result.yearDelta ?: Double.NaN, 0.0001)
        assertEquals(true, result.hasBaseline)
    }

    @Test
    fun `脏日期退回真实今天且不崩溃`() {
        // 2000-01-01 一定早于任何真实"今天"，用来验证退回路径确实算出了可用基准，
        // 而不是用 take(7) 截出的垃圾串（如 "not-a-d" + "-01"）导致基准错乱
        val snapshots = listOf(snap("2000-01-01", 80.0))
        val result = AssetGrowthCalculator.fromSnapshots(snapshots, currentNetAssets = 150.0, today = "not-a-date")
        assertEquals(true, result.hasBaseline)
        assertEquals(70.0, result.yearDelta ?: Double.NaN, 0.0001)
    }

    @Test
    fun `无快照返回 EMPTY 口径`() {
        val result = AssetGrowthCalculator.fromSnapshots(emptyList(), currentNetAssets = 150.0, today = "2026-10-24")
        assertEquals(false, result.hasBaseline)
        assertNull(result.monthDelta)
        assertNull(result.yearDelta)
    }
}
