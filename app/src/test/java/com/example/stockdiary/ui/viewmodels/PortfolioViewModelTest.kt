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
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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

    /**
     * 模拟 Manager 持有的**全应用唯一**偏好流。
     * VM 直接暴露这份流，不再自建副本，因此测试里改它等同「别的页面写入偏好」。
     */
    private lateinit var prefsFlow: MutableStateFlow<DashboardPreferences>

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

    private fun buildVm(
        initialPrefs: DashboardPreferences = DashboardPreferences(),
        initialAssets: List<Asset> = emptyList()
    ): PortfolioViewModel {
        every { container.assetRepository } returns assetRepo
        every { container.dividendIncomeUseCase } returns useCase
        every { container.dashboardPreferencesManager } returns prefsMgr
        every { container.applicationContext } returns mockk(relaxed = true)
        prefsFlow = MutableStateFlow(initialPrefs)
        every { prefsMgr.loadPreferences() } returns initialPrefs
        every { prefsMgr.preferences } returns prefsFlow
        // 模拟 Manager 的真实契约：拉磁盘最新值 → 套用 mutate → 存盘 → 同步唯一流 → 返回合并结果。
        // 必须这么打桩：prefsMgr 是 mock，其 updatePreferences 是桩、真实方法体不会执行，
        // 内部唯一的偏好流也不会自己更新；且缺省返回值是 MockK 造的假对象，
        // 会让 vm.preferences 被读成假值（表现为「SortOrder 是个陌生实例」）。
        every { prefsMgr.updatePreferences(any()) } answers {
            val mutate = firstArg<(DashboardPreferences) -> DashboardPreferences>()
            val updated = mutate(prefsFlow.value)
            prefsFlow.value = updated
            updated
        }
        every { assetRepo.getAllAssets() } returns flowOf(initialAssets)
        coEvery { assetRepo.getAllAccounts() } returns emptyList()
        coEvery { useCase.load(any(), any(), any(), any()) } returns DividendIncomeLoader.IncomeResult(
            dividendsBySymbol = emptyMap(),
            recordsBySymbol = emptyMap(),
            totalAnnualIncome = 888.0,
            hasDividendData = true
        )
        coEvery { assetRepo.addOrUpdateStockAsset(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns stockAsset
        coEvery { assetRepo.upsertCryptoAsset(any()) } returns 1L
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
        verify { prefsMgr.updatePreferences(any()) }
    }

    @Test
    fun `偏好由 Manager 唯一流驱动_其他页面写入立即可见`() {
        val vm = buildVm()
        // 模拟设置页 / 日历页写入：改动直接落到同一份流上
        prefsFlow.value = prefsFlow.value.copy(showLiabilities = false, sortOrder = SortOrder.BY_VALUE_ASC)

        assertFalse("其他页面的写入必须立即反映到本页", vm.preferences.value.showLiabilities)
        assertEquals(SortOrder.BY_VALUE_ASC, vm.preferences.value.sortOrder)
    }

    @Test
    fun `updateSortOrder 更新排序偏好并持久化对应值`() = runBlocking {
        val vm = buildVm()
        vm.updateSortOrder(SortOrder.BY_NAME)
        assertEquals(SortOrder.BY_NAME, vm.preferences.value.sortOrder)
        assertEquals(SortOrder.BY_NAME, prefsFlow.value.sortOrder)
    }

    @Test
    fun `addAsset 股票走 addOrUpdateStockAsset 且不记交易流水`() = runBlocking {
        val vm = buildVm()
        vm.addAsset(stockAsset)
        coVerify {
            assetRepo.addOrUpdateStockAsset(
                any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(),
                // 与首页一致：手动添加是补录持仓，不能凭空写一笔今日买入
                recordTransaction = false
            )
        }
        coVerify(exactly = 0) { assetRepo.insertAsset(any()) }
    }

    @Test
    fun `addAsset 加密货币走 upsertCryptoAsset 去重`() = runBlocking {
        val cm = buildVm()
        val crypto = Asset(
            name = "Bitcoin", type = AssetType.CRYPTO, symbol = "BTCUSDT",
            account = "币安", balance = 0.1, currency = "USD"
        )
        cm.addAsset(crypto)
        coVerify { assetRepo.upsertCryptoAsset(crypto) }
        coVerify(exactly = 0) { assetRepo.insertAsset(any()) }
        coVerify(exactly = 0) {
            assetRepo.addOrUpdateStockAsset(
                any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any()
            )
        }
    }

    @Test
    fun `addAsset 非股票基金走 insertAsset`() = runBlocking {
        val vm = buildVm()
        vm.addAsset(depositAsset)
        coVerify { assetRepo.insertAsset(depositAsset) }
        coVerify(exactly = 0) {
            assetRepo.addOrUpdateStockAsset(
                any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any()
            )
        }
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

            val vm = buildVm(initialAssets = listOf(stockAsset))
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
    fun `refresh 成功时结果写入 pendingSyncResult，消费后清空`() = runTest {
        // 回归：结果放 UiState 而不是 SharedFlow：切走标签页不丢，消费后清空不重复弹
        mockkObject(AssetSyncManager)
        try {
            val expected = SyncResult(successCount = 7, failedCount = 2, truncatedByTimeLimit = false)
            coEvery {
                AssetSyncManager.syncAllAssets(any(), any(), any(), any(), any(), any(), any(), any(), any())
            } returns expected

            val vm = buildVm(initialAssets = listOf(stockAsset))
            // 必须先订阅：uiState 是 WhileSubscribed 的 stateIn，refresh 里的
            // uiState.value.assets 在无人订阅时还是空初值，会被当成「首帧未到」而短路
            val (trackJob, latest) = vm.uiState.trackIn(this)
            vm.refresh()
            val state = vm.uiState.first { it.pendingSyncResult != null }
            assertEquals(expected, state.pendingSyncResult)
            assertEquals(null, state.errorMessage)

            vm.clearPendingSyncResult()
            assertEquals(null, latest().pendingSyncResult)
            trackJob.cancel()
        } finally {
            unmockkAll()
        }
    }

    @Test
    fun `refresh 同步抛异常时只写 errorMessage，不写 pendingSyncResult`() = runTest {
        // 同步阶段抛异常走 errorMessage 反馈，不应再弹「刷新完成」（避免与失败提示打架）
        mockkObject(AssetSyncManager)
        try {
            coEvery {
                AssetSyncManager.syncAllAssets(any(), any(), any(), any(), any(), any(), any(), any(), any())
            } throws RuntimeException("网络挂了")

            val vm = buildVm(initialAssets = listOf(stockAsset))
            // 同上：先订阅让 assets 真正发射，否则 refresh 会短路成「加载中」
            val (trackJob, latest) = vm.uiState.trackIn(this)
            vm.refresh()
            val state = vm.uiState.first { it.errorMessage != null }
            assertEquals("刷新失败：网络挂了", state.errorMessage)
            assertEquals(null, state.pendingSyncResult)
            trackJob.cancel()
        } finally {
            unmockkAll()
        }
    }

    @Test
    fun `收起市场再展开，不会重新展开手动收起的账户`() = runTest {
        // 回归 #12：收起账户 → 收起市场 → 展开市场时，账户 key 会从 currentKeys 消失。
        // 若 knownGroups 跟着缩小，它会被当成新分组自动展开，用户手动收起的状态就丢了。
        val vm = buildVm()

        vm.syncGroups(setOf("market:A", "acct:A"), setOf("market:A"))
        // 初始账户是展开的（首屏只收起一级市场头），先手动收起它
        assertTrue("首屏账户应展开", "acct:A" in vm.expandedGroups.value)
        vm.toggleGroup("acct:A")
        assertFalse("手动收起后应折叠", "acct:A" in vm.expandedGroups.value)

        // 收起市场：该市场的账户 key 从可见分组里消失
        vm.syncGroups(setOf("market:A"), setOf("market:A"))
        // 展开市场：账户 key 回来，不能被当成「新分组」自动展开
        vm.syncGroups(setOf("market:A", "acct:A"), setOf("market:A"))

        assertFalse(
            "重新展开市场不应把用户手动收起的账户重新展开",
            "acct:A" in vm.expandedGroups.value
        )
    }
}
