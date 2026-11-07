package com.example.stockdiary.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SourceStatsCounter] 的口径回归：基金 / 加密共用的统计底座。
 *
 * 这里钉死三件事：
 * 1. 缺省字段一律 null（不用 0 冒充「没发生」），尤其批量分片；
 * 2. 降级只记事实、不增加失败计数（避免「已熔断未请求」被算成源故障）；
 * 3. 没观测过的源不出现在快照里（[hasActivity] 为 false）。
 */
class SourceStatsCounterTest {

    @Test
    fun `初始状态没有任何观测`() {
        val counter = SourceStatsCounter("测试源", host = "example.com")

        assertFalse(counter.hasActivity())
        val snapshot = counter.snapshot()
        assertEquals("测试源", snapshot.source)
        assertEquals("example.com", snapshot.host)
        assertEquals(0, snapshot.success)
        assertEquals(0, snapshot.sourceLevelFailure)
        assertEquals(0, snapshot.symbolLevelFailure)
        assertNull(snapshot.batchShards)
        assertNull(snapshot.batchFailedShards)
        assertNull(snapshot.lastFailureReason)
        assertNull(snapshot.degradedReason)
    }

    @Test
    fun `成功与失败分别累计_失败带最近原因`() {
        val counter = SourceStatsCounter("测试源")

        counter.recordSuccess(3)
        counter.recordSourceFailure("HTTP 514 限流", count = 2)
        counter.recordSymbolFailure(count = 1)
        counter.addDuration(120)

        val snapshot = counter.snapshot()
        assertEquals(3, snapshot.success)
        assertEquals(2, snapshot.sourceLevelFailure)
        assertEquals(1, snapshot.symbolLevelFailure)
        assertEquals(120L, snapshot.durationMs)
        assertEquals("HTTP 514 限流", snapshot.lastFailureReason)
        assertTrue(counter.hasActivity())
    }

    @Test
    fun `降级只记原因_不增加失败计数`() {
        val counter = SourceStatsCounter("测试源")

        counter.markDegraded("已熔断，本轮未请求")

        val snapshot = counter.snapshot()
        assertEquals(0, snapshot.sourceLevelFailure)
        assertEquals(0, snapshot.symbolLevelFailure)
        assertEquals("已熔断，本轮未请求", snapshot.degradedReason)
        assertTrue("降级原因本身就是观测", counter.hasActivity())
    }

    @Test
    fun `批量分片为0时保持null_不回填0占位`() {
        val counter = SourceStatsCounter("测试源")

        counter.setBatchShards(total = 0, failed = 0)
        assertNull(counter.snapshot().batchShards)
        assertNull(counter.snapshot().batchFailedShards)

        counter.setBatchShards(total = 3, failed = 5)
        val snapshot = counter.snapshot()
        assertEquals(3, snapshot.batchShards)
        assertEquals("失败分片数收敛在 [0, total]", 3, snapshot.batchFailedShards)
    }

    @Test
    fun `reset后回到零观测`() {
        val counter = SourceStatsCounter("测试源")
        counter.recordSuccess(1)
        counter.recordSourceFailure("HTTP 500")
        counter.markDegraded("降级")
        counter.addDuration(50)
        counter.setBatchShards(2, 1)

        counter.reset()

        assertFalse(counter.hasActivity())
        val snapshot = counter.snapshot()
        assertEquals(0, snapshot.success)
        assertEquals(0, snapshot.sourceLevelFailure)
        assertEquals(0L, snapshot.durationMs)
        assertNull(snapshot.lastFailureReason)
        assertNull(snapshot.degradedReason)
        assertNull(snapshot.batchShards)
        assertNull(snapshot.batchFailedShards)
    }
}
