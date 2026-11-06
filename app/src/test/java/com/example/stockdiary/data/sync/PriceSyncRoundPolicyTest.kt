package com.example.stockdiary.data.sync

import com.example.stockdiary.utils.buildPriceSyncMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 后台 [com.example.stockdiary.data.sync.PriceSyncWorker] 一轮收尾的纯判定回归。
 *
 * 旧判定 `successCount > 0 || failedCount == 0` 把「0 成功 / 0 失败 / N 跳过」也当成
 * 成功轮，于是全跳过也刷新全局 last_price_sync_time、清零连续失败、写当日快照、
 * 弹「成功同步 0 个资产」。这里把副作用收进 [PriceSyncRoundPlan]，逐项钉死：
 * 全跳过时哪些**必须不发生**、哪些仍要发生（如实通知 / 分红 / 续期）。
 *
 * 「跳过不算失败」同样在这里保证：全跳过归 NO_EFFECTIVE_UPDATE，Worker 照旧
 * Result.success()，绝不落到 FAILURE / handleFailure / 指数退避。
 */
class PriceSyncRoundPolicyTest {

    private val interval = 6L * 60L * 60L * 1000L

    private fun allSkipped(): SyncResult = SyncResult(
        successCount = 0,
        failedCount = 0,
        skippedByReason = mapOf("币安熔断" to 130, "退市" to 1)
    )

    @Test
    fun `skippedCount派生属性_等于各原因之和`() {
        assertEquals(131, allSkipped().skippedCount)
        assertEquals(0, SyncResult(successCount = 0, failedCount = 0).skippedCount)
        assertTrue(allSkipped().hasNoEffectiveUpdate)
        assertFalse(SyncResult(successCount = 0, failedCount = 0).hasNoEffectiveUpdate)
        assertFalse(SyncResult(successCount = 1, failedCount = 0, skippedByReason = mapOf("退市" to 1)).hasNoEffectiveUpdate)
    }

    @Test
    fun `全跳过_不刷新全局时间戳_不清零连续失败_不写快照_不发误导通知`() {
        val result = allSkipped()
        val plan = planPriceSyncRound(result, elapsedSinceLastSuccessNotifyMillis = 0L, interval)

        assertEquals(PriceSyncRoundOutcome.NO_EFFECTIVE_UPDATE, plan.outcome)
        assertFalse("全跳过不得刷新 last_price_sync_time", plan.refreshLastSyncTime)
        assertFalse("全跳过不得清零连续失败", plan.clearConsecutiveFailures)
        assertFalse("全跳过不得清掉上次错误类型", plan.clearLastErrorType)
        assertFalse("全跳过不得写当日快照", plan.saveTodaySnapshot)
        assertTrue("全跳过必须如实通知", plan.notifyResult)
        // 全跳过分支的文案是「N 个资产被跳过」（见 buildPriceSyncMessage 与
        // PriceSyncMessagesTest），不是成功/失败分支的「，跳过 N 个」后缀；
        // 断言只需验证「跳过总数如实出现」，别把文案格式钉成另一套。
        assertTrue("通知/日志文案要带跳过总数", plan.message.contains("131 个资产被跳过"))
        assertFalse("不得再报『成功同步 0 个资产』", plan.message.contains("成功同步 0 个资产"))
        assertFalse("不得谎报同步失败", plan.message.contains("失败"))
        assertTrue("分红是独立提醒，照常判", plan.notifyTodayDividends)
        assertTrue("周期任务仍要续期", plan.reschedule)
    }

    @Test
    fun `全跳过_不算失败_不触发handleFailure`() {
        val result = allSkipped()
        assertEquals(0, result.failedCount)
        assertEquals(PriceSyncRoundOutcome.NO_EFFECTIVE_UPDATE, classifyPriceSyncRound(result))
        assertFalse(classifyPriceSyncRound(result) == PriceSyncRoundOutcome.FAILURE)
        // 即使跳过量只有 1 个，也必须走「无有效更新」分支
        val one = SyncResult(successCount = 0, failedCount = 0, skippedByReason = mapOf("停牌" to 1))
        assertEquals(PriceSyncRoundOutcome.NO_EFFECTIVE_UPDATE, classifyPriceSyncRound(one))
    }

    @Test
    fun `全0极端且无跳过_保持原语义_刷新时间戳并写快照`() {
        val result = SyncResult(successCount = 0, failedCount = 0)
        val plan = planPriceSyncRound(result, 0L, interval)
        assertEquals(PriceSyncRoundOutcome.EFFECTIVE_UPDATE, plan.outcome)
        assertTrue(plan.refreshLastSyncTime)
        assertTrue(plan.clearConsecutiveFailures)
        assertTrue(plan.clearLastErrorType)
        assertTrue(plan.saveTodaySnapshot)
        assertTrue(plan.notifyTodayDividends)
        assertTrue(plan.reschedule)
        // 既有「全0极端也存_无害覆盖」语义不变
        assertTrue(shouldSaveSnapshotAfterSync(result))
    }

    @Test
    fun `有成功且伴随跳过_按正常成功轮处理_文案带跳过量`() {
        val result = SyncResult(
            successCount = 5,
            failedCount = 0,
            skippedByReason = mapOf("退市" to 3)
        )
        val plan = planPriceSyncRound(result, 0L, interval)
        assertEquals(PriceSyncRoundOutcome.EFFECTIVE_UPDATE, plan.outcome)
        assertTrue(plan.refreshLastSyncTime)
        assertTrue(plan.saveTodaySnapshot)
        assertTrue(plan.message.contains("跳过 3"))
        assertTrue(shouldSaveSnapshotAfterSync(result))
    }

    @Test
    fun `有失败且无成功_走失败处理_不做任何成功态副作用`() {
        val result = SyncResult(successCount = 0, failedCount = 2)
        val plan = planPriceSyncRound(result, 0L, interval)
        assertEquals(PriceSyncRoundOutcome.FAILURE, plan.outcome)
        assertFalse(plan.refreshLastSyncTime)
        assertFalse(plan.clearConsecutiveFailures)
        assertFalse(plan.saveTodaySnapshot)
        assertFalse("失败轮由 handleFailure 自行续期，这里不重复排", plan.reschedule)
        assertTrue("有失败一定发通知", plan.notifyResult)
        assertFalse(shouldSaveSnapshotAfterSync(result))
    }

    @Test
    fun `成功通知降频_纯成功在间隔内不发_超过间隔发`() {
        val result = SyncResult(successCount = 5, failedCount = 0)
        assertFalse(planPriceSyncRound(result, interval - 1L, interval).notifyResult)
        assertTrue(planPriceSyncRound(result, interval, interval).notifyResult)
    }

    @Test
    fun `全跳过不受成功通知降频影响_降频窗口内也照常如实通知`() {
        assertTrue(planPriceSyncRound(allSkipped(), 0L, interval).notifyResult)
        assertTrue(planPriceSyncRound(allSkipped(), interval - 1L, interval).notifyResult)
    }

    @Test
    fun `通知与日志共用同一份文案_不会两处口径漂移`() {
        val result = allSkipped()
        assertEquals(
            buildPriceSyncMessage(result.successCount, result.failedCount, result.skippedCount),
            planPriceSyncRound(result, 0L, interval).message
        )
    }

    @Test
    fun `成功通知时间戳_只有有效更新且确实发通知才推进`() {
        // 全跳过：照常如实通知，但不是成功通知，绝不占用纯成功降频窗口
        val skippedPlan = planPriceSyncRound(allSkipped(), 0L, interval)
        assertTrue(skippedPlan.notifyResult)
        assertFalse("全跳过不得写 last_success_notify_time", skippedPlan.updateSuccessNotifyTimestamp)
        assertFalse(
            "全跳过每轮必发、不受降频影响，同样不写成功时间戳",
            planPriceSyncRound(allSkipped(), interval - 1L, interval).updateSuccessNotifyTimestamp
        )
        // 纯成功：降频窗口内没发通知 → 不推进；超过间隔发了通知 → 推进
        val success = SyncResult(successCount = 5, failedCount = 0)
        assertFalse(planPriceSyncRound(success, interval - 1L, interval).updateSuccessNotifyTimestamp)
        assertTrue(planPriceSyncRound(success, interval, interval).updateSuccessNotifyTimestamp)
        // 失败轮次同样不是成功通知
        assertFalse(
            planPriceSyncRound(SyncResult(successCount = 0, failedCount = 2), 0L, interval)
                .updateSuccessNotifyTimestamp
        )
    }
}
