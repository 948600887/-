package com.example.stockdiary.data.sync

import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetSnapshot
import com.example.stockdiary.database.AssetType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [shouldCreateAutoSnapshot] 与 [planSnapshotDedup] 纯函数单测。
 *
 * 钉死两件容易在日后重构中被悄悄改坏的行为：
 * 1. 自动快照的新鲜度门槛是「严格过半」而不是「至少一半」，且负债不参与判定
 *    （负债 lastSyncTime 恒 0，算进去会稀释分子，把「该跳过」误判成「该建」）；
 * 2. 同日多行去重保留 timestamp 最大者（最后写入覆盖语义），且删除 id 升序
 *    （调用方按 id 顺序逐条删，升序让失败重试幂等）。
 */
class AutoSnapshotPolicyTest {

    private val now = 1_000_000_000_000L

    private fun asset(
        type: AssetType = AssetType.STOCK,
        lastSyncTime: Long = 0L
    ) = Asset(
        name = "标的-${type.name}-$lastSyncTime",
        type = type,
        symbol = "sh600${lastSyncTime % 1000}",
        account = "默认",
        balance = 100.0,
        lastSyncTime = lastSyncTime
    )

    // ---------- shouldCreateAutoSnapshot ----------

    @Test
    fun `空组合不建自动快照`() {
        assertFalse(shouldCreateAutoSnapshot(emptyList(), now))
    }

    @Test
    fun `只有负债不建自动快照`() {
        val assets = listOf(
            asset(AssetType.LOAN, lastSyncTime = now),
            asset(AssetType.CREDIT_CARD, lastSyncTime = now)
        )
        assertFalse(shouldCreateAutoSnapshot(assets, now))
    }

    @Test
    fun `全部新鲜时建自动快照`() {
        val assets = List(5) { asset(lastSyncTime = now - 60_000) }
        assertTrue(shouldCreateAutoSnapshot(assets, now))
    }

    @Test
    fun `全部陈旧时不建自动快照`() {
        val assets = List(5) { asset(lastSyncTime = now - 25 * 60 * 60_000) }
        assertFalse(shouldCreateAutoSnapshot(assets, now))
    }

    @Test
    fun `从没同步过不建自动快照`() {
        val assets = List(5) { asset(lastSyncTime = 0L) }
        assertFalse(shouldCreateAutoSnapshot(assets, now))
    }

    @Test
    fun `恰好半数新鲜不建自动快照`() {
        // 4 个持仓 2 个新鲜：2*2 > 4 为 false —— 「过半」是严格多于一半
        val assets = listOf(
            asset(lastSyncTime = now),
            asset(lastSyncTime = now),
            asset(lastSyncTime = now - 25 * 60 * 60_000),
            asset(lastSyncTime = now - 25 * 60 * 60_000)
        )
        assertFalse(shouldCreateAutoSnapshot(assets, now))
    }

    @Test
    fun `超过半数新鲜才建自动快照`() {
        // 4 个持仓 3 个新鲜：3*2 > 4 为 true
        val assets = listOf(
            asset(lastSyncTime = now),
            asset(lastSyncTime = now),
            asset(lastSyncTime = now),
            asset(lastSyncTime = now - 25 * 60 * 60_000)
        )
        assertTrue(shouldCreateAutoSnapshot(assets, now))
    }

    @Test
    fun `恰好卡在新鲜度阈值内算新鲜`() {
        val threshold = AUTO_SNAPSHOT_STALE_THRESHOLD_MS
        val assets = listOf(
            asset(lastSyncTime = now - threshold),
            asset(lastSyncTime = now - threshold)
        )
        assertTrue(shouldCreateAutoSnapshot(assets, now, threshold))
    }

    @Test
    fun `超过新鲜度阈值一毫秒算陈旧`() {
        val threshold = AUTO_SNAPSHOT_STALE_THRESHOLD_MS
        val assets = listOf(
            asset(lastSyncTime = now - threshold - 1),
            asset(lastSyncTime = now - threshold - 1)
        )
        assertFalse(shouldCreateAutoSnapshot(assets, now, threshold))
    }

    @Test
    fun `自定义阈值可注入`() {
        val assets = listOf(
            asset(lastSyncTime = now - 30 * 60_000),
            asset(lastSyncTime = now - 30 * 60_000)
        )
        // 30 分钟前的同步价：默认 24h 门槛算新鲜，10 分钟门槛算陈旧
        assertTrue(shouldCreateAutoSnapshot(assets, now, staleThresholdMs = 24 * 60 * 60_000))
        assertFalse(shouldCreateAutoSnapshot(assets, now, staleThresholdMs = 10 * 60_000))
    }

    @Test
    fun `负债不参与新鲜度判定`() {
        // 1 只新鲜持仓 + 3 条负债（lastSyncTime=0）：若把负债算进分母，
        // 1*2 > 4 为 false 会误判成「不建」——负债没有行情价，不该稀释分子
        val assets = listOf(
            asset(AssetType.STOCK, lastSyncTime = now),
            asset(AssetType.LOAN, lastSyncTime = 0L),
            asset(AssetType.MORTGAGE, lastSyncTime = 0L),
            asset(AssetType.OTHER_LIABILITY, lastSyncTime = 0L)
        )
        assertTrue(shouldCreateAutoSnapshot(assets, now))
    }

    // ---------- planSnapshotDedup ----------

    private fun snapshot(id: Long, date: String, timestamp: Long, net: Double = 100.0) =
        AssetSnapshot(
            id = id,
            timestamp = timestamp,
            date = date,
            totalAssets = net,
            totalLiabilities = 0.0,
            netAssets = net
        )

    @Test
    fun `空快照表无删除计划`() {
        assertTrue(planSnapshotDedup(emptyList()).isEmpty())
    }

    @Test
    fun `无同日多行时无删除计划`() {
        val snapshots = listOf(
            snapshot(1, "2026-10-20", 1_000),
            snapshot(2, "2026-10-21", 2_000),
            snapshot(3, "2026-10-22", 3_000)
        )
        assertTrue(planSnapshotDedup(snapshots).isEmpty())
    }

    @Test
    fun `同日多行保留时间戳最大的一条`() {
        val snapshots = listOf(
            snapshot(10, "2026-10-22", 1_000, net = 111.0),
            snapshot(11, "2026-10-22", 3_000, net = 333.0), // 保留这条
            snapshot(12, "2026-10-22", 2_000, net = 222.0)
        )
        assertEquals(listOf(10L, 12L), planSnapshotDedup(snapshots))
    }

    @Test
    fun `时间戳并列时保留 id 较大者`() {
        val snapshots = listOf(
            snapshot(20, "2026-10-22", 5_000, net = 111.0),
            snapshot(21, "2026-10-22", 5_000, net = 222.0) // 同时写入，后插入的保留
        )
        assertEquals(listOf(20L), planSnapshotDedup(snapshots))
    }

    @Test
    fun `多个日期有重复时全部规划且 id 升序`() {
        val snapshots = listOf(
            snapshot(7, "2026-10-21", 100),
            snapshot(3, "2026-10-21", 300),
            snapshot(9, "2026-10-22", 200),
            snapshot(4, "2026-10-22", 400),
            snapshot(5, "2026-10-22", 250)
        )
        // 10-21 删 id=7；10-22 删 id=9、5 → 合并后升序
        assertEquals(listOf(5L, 7L, 9L), planSnapshotDedup(snapshots))
    }

    @Test
    fun `干净行与重复行混在一起只删重复行`() {
        val snapshots = listOf(
            snapshot(1, "2026-10-20", 1_000),          // 干净，保留
            snapshot(2, "2026-10-21", 2_000),          // 删
            snapshot(3, "2026-10-21", 2_500),          // 保留
            snapshot(4, "2026-10-22", 3_000)           // 干净，保留
        )
        assertEquals(listOf(2L), planSnapshotDedup(snapshots))
    }
}
