package com.example.stockdiary.data.price

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PriceLineage] / [PriceLineageRecorder] 回归（G3 零迁移血缘）。
 *
 * 覆盖：三层 origin 的 [PriceLineage.uiLabel] 文案、[LineageOrigin] 归属、
 * [PriceLineage.roundId] == 0 不显示轮次段、记录器 clear 后快照为空、
 * 以及基金 `millionCopiesIncome` 列完全不参与文案。
 */
class PriceLineageTest {

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val MIN = 60_000L
        const val HOUR = 60 * MIN
        const val DAY = 24 * HOUR
    }

    @After
    fun tearDown() {
        // object 单例跨用例持久，必须手动复位
        PriceLineageRecorder.resetForTesting()
    }

    // ---------- 三层 origin 的 uiLabel 文案 ----------
    // 注意：[PriceLineage.uiLabel] 取「数据年龄」时用真实系统时钟（与生产一致），
    // 故 asOf 必须相对 System.currentTimeMillis() 构造，才能得到确定可断言的年龄文案。

    @Test
    fun `股票FILE_CACHE_uiLabel为展示名加年龄_无轮次`() {
        val now = System.currentTimeMillis()
        val lineage = PriceLineage(
            code = "sh600519",
            sourceId = SourceIds.SINA,
            origin = LineageOrigin.FILE_CACHE,
            asOf = now - 2 * HOUR,
            fetchedAt = now,
            roundId = 0
        )
        assertEquals("新浪财经", lineage.displayName)
        assertEquals(LineageOrigin.FILE_CACHE, lineage.origin)
        // 2 小时前 → "新浪财经 · 2小时前更新"（roundId=0 不显示轮次段）
        assertEquals("新浪财经 · 2小时前更新", lineage.uiLabel)
        assertFalse(lineage.uiLabel.contains("轮次"))
    }

    @Test
    fun `基金ROOM_FUND_NAV带navDate_uiLabel用净值日口径_含轮次`() {
        val now = System.currentTimeMillis()
        val lineage = PriceLineage(
            code = "123456",
            sourceId = SourceIds.EASTMONEY_FUND,
            origin = LineageOrigin.ROOM_FUND_NAV,
            asOf = now - 3 * DAY,
            fetchedAt = now,
            roundId = 7,
            navDate = "2026-11-01"
        )
        assertEquals("东方财富", lineage.displayName)
        assertEquals(LineageOrigin.ROOM_FUND_NAV, lineage.origin)
        // 基金特例：净值日代替普通年龄段；roundId=7 → 末尾补轮次段
        assertEquals("东方财富 · 净值日2026-11-01（3天前） · 轮次#7", lineage.uiLabel)
    }

    @Test
    fun `加密IN_MEMORY_uiLabel为币安加年龄`() {
        val now = System.currentTimeMillis()
        val lineage = PriceLineage(
            code = "BTCUSDT",
            sourceId = SourceIds.BINANCE,
            origin = LineageOrigin.IN_MEMORY,
            asOf = now - 5 * MIN,
            fetchedAt = now,
            roundId = 0
        )
        assertEquals("币安", lineage.displayName)
        assertEquals(LineageOrigin.IN_MEMORY, lineage.origin)
        assertEquals("币安 · 5分钟前更新", lineage.uiLabel)
    }

    @Test
    fun `未知sourceId_displayName回退原串`() {
        val now = System.currentTimeMillis()
        val lineage = PriceLineage(
            code = "x",
            sourceId = "weird_src",
            origin = LineageOrigin.IN_MEMORY,
            asOf = now,
            fetchedAt = now,
            roundId = 0
        )
        assertEquals("weird_src", lineage.displayName)
        assertEquals("weird_src · 刚刚更新", lineage.uiLabel)
    }

    // ---------- millionCopiesIncome 不参与文案 ----------

    @Test
    fun `基金血缘uiLabel不含millionCopiesIncome列值`() {
        // 概念上该基金行来自 fund_nav_cache，带有 millionCopiesIncome（货币基金口径）列，
        // 但该列完全不参与血缘、一行不动；构造时「带入」该值仅用于反证 uiLabel 不含它。
        val millionCopiesIncome = 1.2345
        val lineage = PriceLineage(
            code = "000001",
            sourceId = SourceIds.EASTMONEY_FUND,
            origin = LineageOrigin.ROOM_FUND_NAV,
            asOf = NOW - 1 * DAY,
            fetchedAt = NOW,
            roundId = 0,
            navDate = "2026-11-02"
        )
        val label = lineage.uiLabel
        assertFalse("uiLabel 不应包含 millionCopiesIncome 列值", label.contains(millionCopiesIncome.toString()))
        assertFalse("uiLabel 不应包含 millionCopiesIncome 字样", label.contains("million", ignoreCase = true))
    }

    // ---------- PriceLineageRecorder ----------

    @Test
    fun `recorder_report后snapshot含该code`() {
        val l = PriceLineage("sh600519", SourceIds.SINA, LineageOrigin.FILE_CACHE, NOW, NOW, 0)
        PriceLineageRecorder.report("sh600519", l)
        assertEquals(1, PriceLineageRecorder.snapshot().size)
        assertEquals(l, PriceLineageRecorder.snapshot()["sh600519"])
    }

    @Test
    fun `recorder_clear后snapshot为空`() {
        PriceLineageRecorder.report(
            "sh600519",
            PriceLineage("sh600519", SourceIds.SINA, LineageOrigin.FILE_CACHE, NOW, NOW, 0)
        )
        PriceLineageRecorder.clear()
        assertTrue(PriceLineageRecorder.snapshot().isEmpty())
    }

    @Test
    fun `recorder_beginRound不清空_避免并发轮次互相抹掉血缘`() {
        PriceLineageRecorder.report(
            "a",
            PriceLineage("a", SourceIds.SINA, LineageOrigin.FILE_CACHE, NOW, NOW, 3)
        )
        // 并发的另一轮开始，不得把 #3 尚未汇总的记录清掉
        PriceLineageRecorder.beginRound(4, "手动刷新")
        val snap = PriceLineageRecorder.snapshot()
        assertEquals(1, snap.size)
        assertEquals(3, snap["a"]?.roundId)
    }

    @Test
    fun `recorder_endRound只清理本轮_不碰其它轮`() {
        PriceLineageRecorder.report(
            "a", PriceLineage("a", SourceIds.SINA, LineageOrigin.FILE_CACHE, NOW, NOW, 3)
        )
        PriceLineageRecorder.report(
            "b", PriceLineage("b", SourceIds.TENCENT, LineageOrigin.IN_MEMORY, NOW, NOW, 4)
        )

        PriceLineageRecorder.endRound(3)

        val snap = PriceLineageRecorder.snapshot()
        assertEquals(1, snap.size)
        assertEquals(4, snap["b"]?.roundId)
    }

    @Test
    fun `summary只统计本轮并汇总来源与口径`() {
        PriceLineageRecorder.clear()
        PriceLineageRecorder.report(
            "a", PriceLineage("a", SourceIds.SINA, LineageOrigin.IN_MEMORY, NOW, NOW, 3)
        )
        PriceLineageRecorder.report(
            "b", PriceLineage("b", SourceIds.TENCENT, LineageOrigin.FILE_CACHE, NOW - DAY, NOW, 3)
        )
        // 上一轮的记录（roundId=2）不得混进本轮摘要
        PriceLineageRecorder.report(
            "c", PriceLineage("c", SourceIds.SINA, LineageOrigin.IN_MEMORY, NOW, NOW, 2)
        )

        val summary = PriceLineageRecorder.summary(3)

        assertTrue(summary.contains("2 条"))
        assertTrue(summary.contains("新浪财经 1"))
        assertTrue(summary.contains("腾讯财经 1"))
        assertTrue(summary.contains("IN_MEMORY 1"))
        assertTrue(summary.contains("FILE_CACHE 1"))
        assertFalse(summary.contains("#2"))
    }
}
