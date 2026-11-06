package com.example.stockdiary.data.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [SyncScenario] 7 个触发场景的 [SyncScenario.triggerLabel] 逐行断言（G1 契约骨架）。
 */
class PriceSyncRequestTest {

    @Test
    fun `SyncScenario_triggerLabel_逐场景断言`() {
        assertEquals("后台周期同步", SyncScenario.BACKGROUND_PERIODIC.triggerLabel)
        assertEquals("币安导入保存", SyncScenario.BINANCE_IMPORT_BACKFILL.triggerLabel)
        assertEquals("资产详情页刷新", SyncScenario.ASSET_DETAIL_REFRESH.triggerLabel)
        assertEquals("仪表盘刷新", SyncScenario.DASHBOARD_FULL_REFRESH.triggerLabel)
        assertEquals("持仓页刷新", SyncScenario.PORTFOLIO_FULL_REFRESH.triggerLabel)
        assertEquals("持仓页批量刷新", SyncScenario.PORTFOLIO_BATCH_REFRESH.triggerLabel)
        assertEquals("持仓快照保存", SyncScenario.HOLDING_SNAPSHOT_IMPORT.triggerLabel)
    }

    @Test
    fun `PriceSyncRequest_默认值与参数透传`() {
        val req = PriceSyncRequest(
            scenario = SyncScenario.DASHBOARD_FULL_REFRESH,
            assets = emptyList()
        )
        // 默认值：空进度回调、startTime=0、timeLimitMs=0、correctNames=false
        assertEquals(SyncScenario.DASHBOARD_FULL_REFRESH, req.scenario)
        assertEquals(0L, req.startTime)
        assertEquals(0L, req.timeLimitMs)
        assertEquals(false, req.correctNames)
        // 自定义参数透传
        val req2 = PriceSyncRequest(
            scenario = SyncScenario.PORTFOLIO_BATCH_REFRESH,
            assets = emptyList(),
            startTime = 123L,
            timeLimitMs = 456L,
            correctNames = true
        )
        assertEquals(123L, req2.startTime)
        assertEquals(456L, req2.timeLimitMs)
        assertEquals(true, req2.correctNames)
    }
}
