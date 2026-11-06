package com.example.stockdiary.data.price

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PriceLineageRecorder] 基础行为回归（G4）。
 *
 * 验证「上报 → 快照可见 → clear 清空 → resetForTesting 跨用例隔离 → 同 code 覆盖」这一最小契约。
 * 真正的血缘上报接线在 G5（StockDataManager / UnifiedPriceService 的取价成功路径），
 * 这里只钉死 recorder 自身的内存语义，不依赖任何网络 / Context。
 */
class ClearCacheLineageTest {

    @After
    fun tearDown() {
        // 用例间隔离：object 单例跨用例持久，必须手动清
        PriceLineageRecorder.resetForTesting()
    }

    @Test
    fun `report后snapshot可见_clear后为空`() {
        PriceLineageRecorder.report(
            "sh600519",
            PriceLineage(
                code = "sh600519",
                sourceId = SourceIds.SINA,
                origin = LineageOrigin.FILE_CACHE,
                asOf = 1000L,
                fetchedAt = 1000L,
                roundId = 0
            )
        )
        val snapshot = PriceLineageRecorder.snapshot()
        assertEquals(1, snapshot.size)
        assertTrue(snapshot.containsKey("sh600519"))

        PriceLineageRecorder.clear()
        assertTrue("clear 后快照应为空", PriceLineageRecorder.snapshot().isEmpty())
    }

    @Test
    fun `resetForTesting跨用例不串`() {
        PriceLineageRecorder.report(
            "sz000001",
            PriceLineage(
                code = "sz000001",
                sourceId = SourceIds.TENCENT,
                origin = LineageOrigin.FILE_CACHE,
                asOf = 2000L,
                fetchedAt = 2000L,
                roundId = 0
            )
        )
        assertFalse(PriceLineageRecorder.snapshot().isEmpty())

        // 模拟「上一个用例结束」复位
        PriceLineageRecorder.resetForTesting()
        assertTrue("resetForTesting 后快照应为空，跨用例不串", PriceLineageRecorder.snapshot().isEmpty())
    }

    @Test
    fun `report覆盖同code只保留最新`() {
        PriceLineageRecorder.report(
            "sh600519",
            PriceLineage("sh600519", SourceIds.SINA, LineageOrigin.FILE_CACHE, 1000L, 1000L, 0)
        )
        PriceLineageRecorder.report(
            "sh600519",
            PriceLineage("sh600519", SourceIds.TENCENT, LineageOrigin.FILE_CACHE, 2000L, 2000L, 0)
        )
        val snapshot = PriceLineageRecorder.snapshot()
        assertEquals(1, snapshot.size)
        assertEquals(SourceIds.TENCENT, snapshot["sh600519"]?.sourceId)
    }
}
