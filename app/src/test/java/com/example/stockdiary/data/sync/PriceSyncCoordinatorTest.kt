package com.example.stockdiary.data.sync

import android.content.Context
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.data.repository.AssetRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * [PriceSyncCoordinator] 归一化 + 委托的回归（G4）。
 *
 * 核心不变量：协调器**只**做 `scenario → forceRefresh / syncTrigger` 归一化，然后原样
 * 委托 [AssetSyncManager.syncAllAssets]（9 参逐一对齐，签名与已有 7 个桩完全一致，门禁不假绿）。
 * 不接管任何收尾逻辑。
 *
 * 参数值断言用 `slot` 捕获（而非 `coVerify` 精确匹配，后者对 lambda / mock 实例脆弱）。
 */
class PriceSyncCoordinatorTest {

    @Before
    fun setUp() {
        mockkObject(AssetSyncManager)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun makeAssets(n: Int): List<Asset> = List(n) { index ->
        Asset(
            id = index.toLong(),
            name = "资产$index",
            type = AssetType.STOCK,
            symbol = "sh60000$index",
            account = "测试账户"
        )
    }

    /**
     * 断言 9 参委托：assets / onProgress / forceRefresh / startTime / timeLimitMs /
     * correctNames / syncTrigger 都逐一对齐到 [AssetSyncManager.syncAllAssets]。
     */
    private fun verifyDelegation(
        scenario: SyncScenario,
        expectedForceRefresh: Boolean,
        expectedTrigger: String,
        expectedStartTime: Long = 0L,
        expectedTimeLimitMs: Long = 0L,
        expectedCorrectNames: Boolean = false
    ) = runTest {
        val context = mockk<Context>(relaxed = true)
        val repository = mockk<AssetRepository>()
        val assets = makeAssets(2)
        val progress: (SyncProgress) -> Unit = { _ -> }

        val assetsSlot = slot<List<Asset>>()
        val progressSlot = slot<(SyncProgress) -> Unit>()
        val forceRefreshSlot = slot<Boolean>()
        val startTimeSlot = slot<Long>()
        val timeLimitSlot = slot<Long>()
        val correctNamesSlot = slot<Boolean>()
        val syncTriggerSlot = slot<String>()

        coEvery {
            AssetSyncManager.syncAllAssets(
                any(), any(), capture(assetsSlot), capture(progressSlot),
                capture(forceRefreshSlot), capture(startTimeSlot), capture(timeLimitSlot),
                capture(correctNamesSlot), capture(syncTriggerSlot)
            )
        } returns SyncResult(1, 0)

        val request = PriceSyncRequest(
            scenario = scenario,
            assets = assets,
            onProgress = progress,
            startTime = expectedStartTime,
            timeLimitMs = expectedTimeLimitMs,
            correctNames = expectedCorrectNames
        )

        val result = PriceSyncCoordinator.sync(context, repository, request)

        assertEquals(SyncResult(1, 0), result)
        // 原样委托：调用次数对齐（9 参签名与已有 7 个桩一致，门禁不假绿）
        coVerify(exactly = 1) {
            AssetSyncManager.syncAllAssets(any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
        // 参数值逐一对齐
        assertEquals("assets 应透传", assets, assetsSlot.captured)
        assertEquals("onProgress 应透传同一引用", progress, progressSlot.captured)
        assertEquals("forceRefresh 应由 scenario 推导", expectedForceRefresh, forceRefreshSlot.captured)
        assertEquals("startTime 应透传", expectedStartTime, startTimeSlot.captured)
        assertEquals("timeLimitMs 应透传", expectedTimeLimitMs, timeLimitSlot.captured)
        assertEquals("correctNames 应透传", expectedCorrectNames, correctNamesSlot.captured)
        assertEquals("syncTrigger 应取 scenario.triggerLabel", expectedTrigger, syncTriggerSlot.captured)
    }

    @Test
    fun `BACKGROUND_PERIODIC_forceRefresh为false_trigger为后台周期同步`() = verifyDelegation(
        scenario = SyncScenario.BACKGROUND_PERIODIC,
        expectedForceRefresh = false,
        expectedTrigger = "后台周期同步"
    )

    @Test
    fun `BINANCE_IMPORT_BACKFILL_forceRefresh为false_trigger为币安导入保存`() = verifyDelegation(
        scenario = SyncScenario.BINANCE_IMPORT_BACKFILL,
        expectedForceRefresh = false,
        expectedTrigger = "币安导入保存"
    )

    @Test
    fun `ASSET_DETAIL_REFRESH_forceRefresh为true_trigger为资产详情页刷新`() = verifyDelegation(
        scenario = SyncScenario.ASSET_DETAIL_REFRESH,
        expectedForceRefresh = true,
        expectedTrigger = "资产详情页刷新"
    )

    @Test
    fun `DASHBOARD_FULL_REFRESH_forceRefresh为true_trigger为仪表盘刷新`() = verifyDelegation(
        scenario = SyncScenario.DASHBOARD_FULL_REFRESH,
        expectedForceRefresh = true,
        expectedTrigger = "仪表盘刷新"
    )

    @Test
    fun `PORTFOLIO_FULL_REFRESH_forceRefresh为true_trigger为持仓页刷新`() = verifyDelegation(
        scenario = SyncScenario.PORTFOLIO_FULL_REFRESH,
        expectedForceRefresh = true,
        expectedTrigger = "持仓页刷新"
    )

    @Test
    fun `PORTFOLIO_BATCH_REFRESH_forceRefresh为true_trigger为持仓页批量刷新`() = verifyDelegation(
        scenario = SyncScenario.PORTFOLIO_BATCH_REFRESH,
        expectedForceRefresh = true,
        expectedTrigger = "持仓页批量刷新"
    )

    @Test
    fun `HOLDING_SNAPSHOT_IMPORT_forceRefresh为true且correctNames为true_trigger为持仓快照保存`() = verifyDelegation(
        scenario = SyncScenario.HOLDING_SNAPSHOT_IMPORT,
        expectedForceRefresh = true,
        expectedTrigger = "持仓快照保存",
        expectedCorrectNames = true
    )

    @Test
    fun `透传startTime与timeLimitMs_后台周期同步带预算`() = verifyDelegation(
        scenario = SyncScenario.BACKGROUND_PERIODIC,
        expectedForceRefresh = false,
        expectedTrigger = "后台周期同步",
        expectedStartTime = 123456L,
        expectedTimeLimitMs = 9 * 60 * 1000L
    )

    @Test
    fun `deriveForceRefresh_真值表_仅两个场景为false`() {
        assertEquals(false, PriceSyncCoordinator.deriveForceRefresh(SyncScenario.BACKGROUND_PERIODIC))
        assertEquals(false, PriceSyncCoordinator.deriveForceRefresh(SyncScenario.BINANCE_IMPORT_BACKFILL))
        assertEquals(true, PriceSyncCoordinator.deriveForceRefresh(SyncScenario.ASSET_DETAIL_REFRESH))
        assertEquals(true, PriceSyncCoordinator.deriveForceRefresh(SyncScenario.DASHBOARD_FULL_REFRESH))
        assertEquals(true, PriceSyncCoordinator.deriveForceRefresh(SyncScenario.PORTFOLIO_FULL_REFRESH))
        assertEquals(true, PriceSyncCoordinator.deriveForceRefresh(SyncScenario.PORTFOLIO_BATCH_REFRESH))
        assertEquals(true, PriceSyncCoordinator.deriveForceRefresh(SyncScenario.HOLDING_SNAPSHOT_IMPORT))
    }
}
