package com.example.stockdiary.data.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [shouldSaveSnapshotAfterSync] 纯判定回归。
 *
 * 这条门槛决定「全量手动刷新成功后到底覆不覆盖当日快照」，是边界型判断，
 * 且直接关系到历史净值曲线会不会被「部分新价 + 部分旧价」污染：
 * - 截断（truncatedByTimeLimit）绝不能存——那一轮只同步了一部分标的；
 * - 整轮全失败没什么可存；
 * - 有成功 / 无失败才存；全 0（全退市组合的极端）无害覆盖也放行。
 *
 * 纯函数不碰 Context / 数据库 / 时钟，直接 JVM 单测钉死每条边界。
 */
class SyncSnapshotPolicyTest {

    private fun result(success: Int, failed: Int, truncated: Boolean = false) =
        SyncResult(successCount = success, failedCount = failed, truncatedByTimeLimit = truncated)

    @Test
    fun `截断不存_优先级最高`() {
        // 即便有成功、无失败，只要被时间预算截断就不存：那一轮只更新了一部分标的
        assertFalse(shouldSaveSnapshotAfterSync(result(success = 5, failed = 0, truncated = true)))
    }

    @Test
    fun `截断且全0也不存`() {
        // 截断 + 全 0（极端）：仍然不存，截断一票否决
        assertFalse(shouldSaveSnapshotAfterSync(result(success = 0, failed = 0, truncated = true)))
    }

    @Test
    fun `整轮全失败不存`() {
        // successCount=0 且 failedCount>0：没什么可存
        assertFalse(shouldSaveSnapshotAfterSync(result(success = 0, failed = 3)))
    }

    @Test
    fun `有成功即存_即便伴随失败`() {
        // 有标的更新成功，值得固化成当日快照
        assertTrue(shouldSaveSnapshotAfterSync(result(success = 5, failed = 2)))
    }

    @Test
    fun `无失败即存_含部分成功`() {
        // failedCount=0（有成功、无失败）
        assertTrue(shouldSaveSnapshotAfterSync(result(success = 5, failed = 0)))
    }

    @Test
    fun `全0极端也存_无害覆盖`() {
        // successCount=0 且 failedCount=0：全退市组合一个都没同步、但也没失败，
        // 覆盖一次同日快照无害（本来就是同一批价）——与后台 Worker 门槛一致
        assertTrue(shouldSaveSnapshotAfterSync(result(success = 0, failed = 0)))
    }
}
