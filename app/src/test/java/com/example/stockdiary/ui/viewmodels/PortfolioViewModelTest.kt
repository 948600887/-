package com.example.stockdiary.ui.viewmodels

import com.example.stockdiary.data.repository.AssetRepository
import com.example.stockdiary.data.sync.AssetSyncManager
import com.example.stockdiary.data.prefs.DashboardPreferences
import com.example.stockdiary.data.prefs.DashboardPreferencesManager
import com.example.stockdiary.data.dividend.DividendIncomeLoader
import com.example.stockdiary.data.dividend.DividendIncomeUseCase
import com.example.stockdiary.data.prefs.SortOrder
import com.example.stockdiary.data.sync.SyncResult
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.di.AppContainer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * PortfolioViewModel 单测。与 Dashboard 同：AppContainer / Repository 需 Context 且为 final 类，
 * 用 MockK 伪造；Dispatchers.Main 在 @BeforeClass 设一次、@AfterClass 才 reset。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PortfolioViewModelTest {

    companion object {
        private val mainDispatcher = UnconfinedTestDispatcher()

        @BeforeClass
        @JvmStatic
        fun setupMain() {
            Dispatchers.setMain(mainDispatcher)
        }

        @AfterClass
        @JvmStatic
        fun resetMainDispatcher() {
            Dispatchers.resetMain()
        }
    }

    private val assetRepo = mockk<AssetRepository>(relaxed = true)
    private val prefsMgr = mockk<DashboardPreferencesManager>(relaxed = true)
    private val useCase = mockk<DividendIncomeUseCase>(relaxed = true)
    private val container = mockk<AppContainer>(relaxed = true)

    private val stockAsset = Asset(
        name = "长江电力", type = AssetType.STOCK, symbol = "600900",
        account = "默认", balance = 1000.0
    )
    private val depositAsset = Asset(
        name = "定期存款", type = AssetType.BANK_DEPOSIT, symbol = null,
        account = "银行", balance = 100_000.0, interestRate = 3.0
    )

    private fun waitUntil(timeoutMs: Long = 3000, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            Thread.sleep(5)
        }
        return cond()
    }

    private fun buildVm(initialPrefs: DashboardPreferences = DashboardPreferences()): PortfolioViewModel {
        every { container.assetRepository } returns assetRepo
        every { container.dividendIncomeUseCase } returns useCase
        every { container.dashboardPreferencesManager } returns prefsMgr
        every { container.context } returns mockk(relaxed = true)
        every { prefsMgr.loadPreferences() } returns initialPrefs
        // 模拟 Manager 的真实契约：拉磁盘最新值 → 套用 mutate → 存盘 → 返回合并结果。
        // 必须这么打桩：prefsMgr 是 mock，其 updatePreferences 是桩、真实方法体不会执行，
        // 于是内部的 load/save 都不会发生；且缺省返回值是 MockK 造的假对象，
        // 会让 vm.preferences 被赋成假对象（表现为「SortOrder 是个陌生实例」）。
        every { prefsMgr.updatePreferences(any()) } answers {
            val mutate = firstArg<(DashboardPreferences) -> DashboardPreferences>()
            val updated = mutate(prefsMgr.loadPreferences())
            prefsMgr.savePreferences(updated)
            updated
        }
        every { assetRepo.getAllAssets() } returns flowOf(emptyList())
        coEvery { assetRepo.getAllAccounts() } returns emptyList()
        coEvery { useCase.load(any(), any(), any(), any()) } returns DividendIncomeLoader.IncomeResult(
            dividendsBySymbol = emptyMap(),
            recordsBySymbol = emptyMap(),
            totalAnnualIncome = 888.0,
            hasDividendData = true
        )
        coEvery { assetRepo.addOrUpdateStockAsset(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns stockAsset
        coEvery { assetRepo.insertAsset(any()) } returns 1L
        return PortfolioViewModel(container)
    }

    @Test
    fun `loadDividendIncome 汇总年化分红收入并结束加载态`() = runBlocking {
        val vm = buildVm()
        // uiState 是 stateIn(WhileSubscribed) 派生流，无订阅者时 .value 恒为初始态，
        // 必须先起收集（见 UiStateCollectExt）
        val (trackJob, latest) = vm.uiState.trackIn(this)
        try {
            vm.loadDividendIncome(listOf(stockAsset))
            // 连 dividendLoading 一起等：它在 finally 里复位，比收入赋值晚一步
            waitUntil { latest().estimatedAnnualDividend == 888.0 && !latest().dividendLoading }
            assertEquals(888.0, latest().estimatedAnnualDividend, 0.001)
            assertFalse(latest().dividendLoading)
        } finally {
            trackJob.cancel()
        }
    }

    @Test
    fun `showAllAssetTypes 将所有显示开关复位为开并持久化`() = runBlocking {
        val initial = DashboardPreferences(showBankDeposits = false, showStocks = false, showFunds = false)
        val vm = buildVm(initial)
        vm.showAllAssetTypes()
        assertTrue(vm.preferences.value.showBankDeposits)
        assertTrue(vm.preferences.value.showStocks)
        assertTrue(vm.preferences.value.showFunds)
        // savePreferences 非挂起 → verify
        verify { prefsMgr.savePreferences(any()) }
    }

    @Test
    fun `updateSortOrder 更新排序偏好并持久化对应值`() = runBlocking {
        val slot = slot<DashboardPreferences>()
        every { prefsMgr.savePreferences(capture(slot)) } returns Unit
        val vm = buildVm()
        vm.updateSortOrder(SortOrder.BY_NAME)
        assertEquals(SortOrder.BY_NAME, vm.preferences.value.sortOrder)
        assertEquals(SortOrder.BY_NAME, slot.captured.sortOrder)
    }

    @Test
    fun `addAsset 股票走 addOrUpdateStockAsset`() = runBlocking {
        val vm = buildVm()
        vm.addAsset(stockAsset)
        coVerify { assetRepo.addOrUpdateStockAsset(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { assetRepo.insertAsset(any()) }
    }

    @Test
    fun `addAsset 非股票基金走 insertAsset`() = runBlocking {
        val vm = buildVm()
        vm.addAsset(depositAsset)
        coVerify { assetRepo.insertAsset(depositAsset) }
        coVerify(exactly = 0) { assetRepo.addOrUpdateStockAsset(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `刷新被时间预算截断时提示用户已保留上次数据`() = runBlocking {
        // 持仓页的刷新按钮同样要走「同步超时，已保留上次数据」，
        // 截断标记必须透传到 errorMessage，而不是只存在于日志里
        mockkObject(AssetSyncManager)
        try {
            coEvery {
                AssetSyncManager.syncAllAssets(any(), any(), any(), any(), any(), any(), any(), any(), any())
            } returns SyncResult(successCount = 2, failedCount = 0, truncatedByTimeLimit = true)

            val vm = buildVm()
            val (trackJob, latest) = vm.uiState.trackIn(this)
            try {
                vm.refresh()
                waitUntil { !latest().isRefreshing }

                assertEquals("同步超时，已保留上次数据", latest().errorMessage)
            } finally {
                trackJob.cancel()
            }
        } finally {
            unmockkAll()
        }
    }

    @Test
    fun `refresh 成功时通过 syncDone 一次性事件上报结果`() = runTest {
        // 回归：刷新完成改为 SharedFlow 一次性事件（替代原 UiState 持久字段 + clearSyncResult），
        // 连点刷新 / 离开页面再回来都不会重复弹或丢弹。这里断言成功时恰好发射一次且内容正确。
        mockkObject(AssetSyncManager)
        try {
            val expected = SyncResult(successCount = 7, failedCount = 2, truncatedByTimeLimit = false)
            coEvery {
                AssetSyncManager.syncAllAssets(any(), any(), any(), any(), any(), any(), any(), any(), any())
            } returns expected

            val vm = buildVm()
            val (trackJob, latest) = vm.uiState.trackIn(this)
            // UNDISPATCHED：让 first() 在 refresh() 前就同步完成订阅，确保一次性事件被捕获；
            // 事件经测试调度器确定性送达（见 UiStateCollectExt 同款 trackIn 机制）
            val event = async(start = CoroutineStart.UNDISPATCHED) { vm.syncDone.first() }
            vm.refresh()
            val result = withTimeout(5000) { event.await() }
            assertEquals(expected, result)
            assertEquals(null, latest().errorMessage)
            trackJob.cancel()
        } finally {
            unmockkAll()
        }
    }

    @Test
    fun `refresh 同步抛异常时不发 syncDone 事件`() = runTest {
        // 同步阶段抛异常走 errorMessage 反馈，不应再发「刷新完成」一次性事件（避免与失败提示打架）
        mockkObject(AssetSyncManager)
        try {
            coEvery {
                AssetSyncManager.syncAllAssets(any(), any(), any(), any(), any(), any(), any(), any(), any())
            } throws RuntimeException("网络挂了")

            val vm = buildVm()
            val (trackJob, latest) = vm.uiState.trackIn(this)
            val event = async(start = CoroutineStart.UNDISPATCHED) { vm.syncDone.first() }
            vm.refresh()
            // 同步抛异常路径下不应发射；first() 永不返回，用超时确认「没有事件」而非「还没收到」
            var received = false
            try {
                withTimeout(500) { event.await() }
                received = true
            } catch (_: TimeoutCancellationException) {
                // 期望超时：未发射任何事件
            }
            assertFalse("同步抛异常时不应发射 syncDone 事件", received)
            assertEquals("刷新失败：网络挂了", latest().errorMessage)
            event.cancel() // first() 未收到事件仍在挂起，显式取消避免 runTest 判协程泄漏
            trackJob.cancel()
        } finally {
            unmockkAll()
        }
    }
}
