package com.example.stockdiary.data

import com.example.stockdiary.database.AssetSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssetGrowthCalculatorTest {

    private fun snapshot(date: String, netAssets: Double) = AssetSnapshot(
        date = date,
        netAssets = netAssets,
        totalAssets = netAssets,
        totalLiabilities = 0.0
    )

    @Test
    fun `没有快照时两个增量都是空`() {
        val growth = AssetGrowthCalculator.fromSnapshots(
            snapshots = emptyList(),
            currentNetAssets = 100_000.0,
            today = "2026-09-30"
        )
        assertNull(growth.monthDelta)
        assertNull(growth.yearDelta)
        assertEquals(false, growth.hasBaseline)
    }

    @Test
    fun `本月新增取本月之前最后一条快照做基准`() {
        val snapshots = listOf(
            snapshot("2026-08-20", 90_000.0),
            snapshot("2026-08-31", 95_000.0),
            snapshot("2026-09-05", 101_000.0),
            snapshot("2026-09-28", 108_000.0)
        )
        val growth = AssetGrowthCalculator.fromSnapshots(
            snapshots = snapshots,
            currentNetAssets = 110_000.0,
            today = "2026-09-30"
        )
        // 基准是 8 月最后一条（09-05、09-28 都在本月内，不能拿来当基准）
        assertEquals(15_000.0, growth.monthDelta!!, 0.001)
    }

    @Test
    fun `今年新增取今年之前最后一条快照做基准`() {
        val snapshots = listOf(
            snapshot("2025-12-31", 80_000.0),
            snapshot("2026-03-31", 91_000.0),
            snapshot("2026-09-28", 108_000.0)
        )
        val growth = AssetGrowthCalculator.fromSnapshots(
            snapshots = snapshots,
            currentNetAssets = 100_000.0,
            today = "2026-09-30"
        )
        assertEquals(20_000.0, growth.yearDelta!!, 0.001)
        assertEquals(9_000.0, growth.monthDelta!!, 0.001)
        assertTrue(growth.hasBaseline)
    }

    @Test
    fun `只有本年内快照时今年新增为空但本月新增可用`() {
        val snapshots = listOf(
            snapshot("2026-01-15", 50_000.0),
            snapshot("2026-08-31", 70_000.0)
        )
        val growth = AssetGrowthCalculator.fromSnapshots(
            snapshots = snapshots,
            currentNetAssets = 72_000.0,
            today = "2026-09-30"
        )
        assertNull(growth.yearDelta)
        assertEquals(2_000.0, growth.monthDelta!!, 0.001)
        assertTrue(growth.hasBaseline)
    }

    @Test
    fun `净资产缩水时增量为负`() {
        val snapshots = listOf(snapshot("2026-08-31", 100_000.0))
        val growth = AssetGrowthCalculator.fromSnapshots(
            snapshots = snapshots,
            currentNetAssets = 88_000.0,
            today = "2026-09-30"
        )
        assertEquals(-12_000.0, growth.monthDelta!!, 0.001)
    }
}
