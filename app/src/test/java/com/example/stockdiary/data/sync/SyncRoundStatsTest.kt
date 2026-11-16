package com.example.stockdiary.data.sync

import com.example.stockdiary.data.contract.SyncErrorType
import com.example.stockdiary.data.price.SourceStats
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SyncRoundStats] 的口径契约。
 *
 * 从 [AssetSyncManager] 外提时最容易出错的是「哪些标的进哪个集合」：
 * 取消级联不能把已结算/已主动跳过的标的算进去，否则轮次摘要会出现
 * 「总数 106，成功+失败+跳过 > 106」。这里把集合语义钉死。
 */
class SyncRoundStatsTest {

    private fun stats(total: Int = 4) = SyncRoundStats(roundId = 7, trigger = "manual", totalCount = total)

    @Test
    fun `成功失败与完成计数各走各的`() {
        val stats = stats()

        stats.recordSuccess()
        stats.recordSuccess()
        stats.recordFailure(SyncErrorType.NETWORK)
        stats.recordCompleted()
        stats.recordCompleted()

        assertEquals(2, stats.successCount)
        assertEquals(1, stats.failedCount)
        assertEquals(2, stats.completedCount)
    }

    @Test
    fun `截断标记只有第一次置位返回 true`() {
        val stats = stats()

        assertTrue(stats.markTruncated())
        assertFalse(stats.markTruncated())
        assertTrue(stats.truncated)
    }

    @Test
    fun `取消级联只统计从未结算过的标的`() {
        val stats = stats(total = 6)
        stats.markSettled(1)          // 正常走完（成功/失败/显式跳过都算）
        stats.markSucceeded(2)
        stats.markFailed(3)
        stats.markBudgetSkipped(4)    // 已单独计入「超时截断」

        // 5、6 没走完 → 只有它们算取消级联
        assertEquals(2, stats.countCancelledCascade(listOf(1, 2, 3, 4, 5, 6)))
    }

    @Test
    fun `跳过原因按类累加_批量补记非正数不记账`() {
        val stats = stats()
        stats.countSkip("停牌")
        stats.countSkip("停牌")
        stats.countSkip("退市")
        stats.addSkipCount("取消级联", 3)
        stats.addSkipCount("取消级联", 0)
        stats.addSkipCount("取消级联", -5)

        assertEquals(mapOf("停牌" to 2, "退市" to 1, "取消级联" to 3), stats.skipReasonsSnapshot())
    }

    @Test
    fun `取消收尾文本包含进度与可选原因`() {
        val stats = stats(total = 10)
        stats.recordSuccess()
        stats.recordCompleted()
        stats.recordCompleted()

        val plain = stats.cancellationSummary(CancellationException())
        assertTrue(plain.contains("已完成 2/10"))
        assertTrue(plain.contains("成功 1"))
        assertTrue(plain.contains("失败 0"))
        assertFalse(plain.contains("取消原因"))

        val withReason = stats.cancellationSummary(CancellationException("用户手动取消"))
        assertTrue(withReason.contains("（取消原因：用户手动取消）"))
    }

    @Test
    fun `结果快照汇总错误类型与跳过原因`() {
        val stats = stats()
        stats.recordSuccess()
        repeat(3) { stats.recordFailure(SyncErrorType.NETWORK) }
        stats.recordFailure(SyncErrorType.DATA)
        stats.countSkip("退市")
        stats.markTruncated()

        val result = stats.toResult(elapsedMs = 1500, sourceStats = emptyList<SourceStats>())

        assertEquals(1, result.successCount)
        assertEquals(4, result.failedCount)
        assertEquals(3, result.networkErrorCount)
        assertEquals(1, result.dataErrorCount)
        assertEquals(0, result.serverErrorCount)
        assertEquals(0, result.unknownErrorCount)
        assertEquals(SyncErrorType.NETWORK, result.dominantErrorType)
        assertEquals(1, result.skippedCount)
        assertEquals(mapOf("退市" to 1), result.skippedByReason)
        assertTrue(result.truncatedByTimeLimit)
        assertEquals(1500, result.elapsedMs)
    }

    @Test
    fun `没有错误时主导错误类型为空`() {
        val stats = stats()
        stats.recordSuccess()

        val result = stats.toResult(elapsedMs = 10, sourceStats = emptyList<SourceStats>())

        assertNull(result.dominantErrorType)
        assertEquals(0, result.failedCount)
    }
}
