package com.example.stockdiary.ui.viewmodels

import com.example.stockdiary.data.repository.AssetRepository
import com.example.stockdiary.data.sync.AssetSyncManager
import com.example.stockdiary.data.prefs.DashboardPreferences
import com.example.stockdiary.data.prefs.DashboardPreferencesManager
import com.example.stockdiary.data.dividend.DividendIncomeLoader
import com.example.stockdiary.data.dividend.DividendIncomeUseCase
import com.example.stockdiary.data.dividend.DividendRecord
import com.example.stockdiary.data.dividend.dividendInputFingerprint
import com.example.stockdiary.data.dividend.daysInCurrentYear
import com.example.stockdiary.data.dividend.recordsDigest
import com.example.stockdiary.data.sync.SyncResult
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.di.AppContainer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.verify
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
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
 * DashboardViewModel 单测。AppContainer 与各 Repository 都是需 Context 的 final 类
 * （private 构造函数），JVM 单测无法直接构造，统一用 MockK 伪造。
 *
 * Main dispatcher 在 @BeforeClass 设一次、@AfterClass 才 reset：
 * VM 内部有 withContext(Dispatchers.Default)，续体切回 Main 的时间点晚于用例结束，
 * 逐用例 resetMain 会让它撞上 "Main dispatcher had failed to initialize"。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {

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

    /** Manager 持有的全应用唯一偏好流；VM 直接订阅它，不再自建副本 */
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

    private fun buildVm(initialAssets: List<Asset> = emptyList()): DashboardViewModel {
        every { container.assetRepository } returns assetRepo
        every { container.dividendIncomeUseCase } returns useCase
        every { container.dashboardPreferencesManager } returns prefsMgr
        every { container.applicationContext } returns mockk(relaxed = true)
        val initialPrefs = DashboardPreferences()
        prefsFlow = MutableStateFlow(initialPrefs)
        // 非挂起函数用 every / 挂起函数用 coEvery
        every { prefsMgr.loadPreferences() } returns initialPrefs
        every { prefsMgr.preferences } returns prefsFlow
        // 模拟 Manager 的真实契约：拉磁盘最新值 → 套用 mutate → 存盘 → 同步唯一流 → 返回合并结果。
        // prefsMgr 是 mock，其 updatePreferences 是桩、真实方法体不会执行，唯一流不会自己更新；
        // 缺省返回值还会是 MockK 造的假对象。
        every { prefsMgr.updatePreferences(any()) } answers {
            val mutate = firstArg<(DashboardPreferences) -> DashboardPreferences>()
            val updated = mutate(prefsFlow.value)
            prefsFlow.value = updated
            updated
        }
        // prefsMgr 是 relaxed mock：MockK 会把 persistAnnualDividendIncome / invalidateAnnualDividendIncome
        // 也一起桩掉（直接返回默认对象、不会执行真实方法体），于是真实方法体里那次
        // updatePreferences 调用根本不会发生、prefsFlow 也不会被更新。这里把这两个门面显式桩成
        // 「经唯一入口 updatePreferences 套用同样的 copy」，既让 prefsFlow 真正反映落盘值，
        // 也让 verify(exactly=1){updatePreferences} 这类断言能数到这次写入。
        every { prefsMgr.persistAnnualDividendIncome(any(), any(), any()) } answers {
            val assets = firstArg<List<Asset>>()
            val recordsBySymbol = secondArg<Map<String, List<DividendRecord>>>()
            val total = thirdArg<Double>()
            prefsMgr.updatePreferences { prefs ->
                prefs.copy(
                    annualDividendCache = total,
                    annualDividendSymbols = assets.mapNotNull { it.symbol }.toSet(),
                    annualDividendFingerprint = dividendInputFingerprint(assets),
                    annualDividendRecordsVersion = recordsDigest(recordsBySymbol)
                )
            }
        }
        every { prefsMgr.invalidateAnnualDividendIncome() } answers {
            prefsMgr.updatePreferences { prefs ->
                prefs.copy(annualDividendFingerprint = "", annualDividendRecordsVersion = "")
            }
        }
        every { assetRepo.getAllAssets() } returns flowOf(initialAssets)
        every { assetRepo.getAllSnapshots() } returns flowOf(emptyList())
        coEvery { assetRepo.getAllAccounts() } returns emptyList()
        coEvery { useCase.load(any(), any(), any(), any()) } returns DividendIncomeLoader.IncomeResult(
            dividendsBySymbol = emptyMap(),
            recordsBySymbol = emptyMap(),
            totalAnnualIncome = 1234.5,
            hasDividendData = true
        )
        coEvery { assetRepo.addOrUpdateStockAsset(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns stockAsset
        coEvery { assetRepo.insertAsset(any()) } returns 1L
        return DashboardViewModel(container)
    }

    @Test
    fun `loadDividendIncome 汇总年化收入与每日预估并结束加载态`() = runBlocking {
        val vm = buildVm()
        // uiState 是 stateIn(WhileSubscribed) 派生流，无订阅者时 .value 恒为初始态，
        // 必须先起收集（见 UiStateCollectExt）
        val (trackJob, latest) = vm.uiState.trackIn(this)
        try {
            vm.loadDividendIncome(listOf(stockAsset))
            // 必须连 dividendLoading 一起等：它在 finally 里复位，比收入赋值晚一步
            waitUntil { latest().realAnnualIncome == 1234.5 && !latest().dividendLoading }
            assertEquals(1234.5, latest().realAnnualIncome, 0.001)
            // 修复点：ViewModel 的每日预估改用 daysInCurrentYear()（闰年 366 / 平年 365），
            // 断言必须同步动态化，否则闰年会假失败（原 hardcoded /365.0）。
            assertEquals(1234.5 / daysInCurrentYear().toDouble(), latest().estimatedDailyDividend, 1e-6)
            assertFalse(latest().dividendLoading)
        } finally {
            trackJob.cancel()
        }
    }

    @Test
    fun `loadDividendIncome 空资产列表直接返回不报错`() = runBlocking {
        val vm = buildVm()
        val (trackJob, latest) = vm.uiState.trackIn(this)
        try {
            vm.loadDividendIncome(emptyList())
            Thread.sleep(100)
            // 直接返回：收入保持 0、加载态不置位（订阅后验证，而非碰巧读到初始值）
            assertEquals(0.0, latest().realAnnualIncome, 0.001)
            assertFalse(latest().dividendLoading)
        } finally {
            trackJob.cancel()
        }
    }

    @Test
    fun `资产为空时刷新直接提示且不触发同步与收入计算`() = runBlocking {
        // 冷启动首帧点刷新：空 priceAssets 会让 SyncResult(0,0) 被当成可写快照，
        // 用库里的旧价覆盖当日快照，并把年度分红缓存写成 0。必须在入口短路。
        mockkObject(AssetSyncManager)
        try {
            val vm = buildVm()
            val (trackJob, latest) = vm.uiState.trackIn(this)
            try {
                waitUntil { latest().assetsFrameReady }
                vm.refresh()
                waitUntil { !latest().isRefreshing && latest().errorMessage != null }
                assertTrue(
                    latest().errorMessage == "暂无资产可同步" ||
                        latest().errorMessage == "资产数据加载中，请稍后再试"
                )
                coVerify(exactly = 0) {
                    AssetSyncManager.syncAllAssets(
                        any(), any(), any(), any(), any(), any(), any(), any(), any()
                    )
                }
                coVerify(exactly = 0) { useCase.load(any(), any(), any(), any()) }
            } finally {
                trackJob.cancel()
            }
        } finally {
            unmockkAll()
        }
    }

    @Test
    fun `addAsset 股票走 addOrUpdateStockAsset 而非 insertAsset`() = runBlocking {
        val vm = buildVm()
        vm.addAsset(stockAsset)
        coVerify { assetRepo.addOrUpdateStockAsset(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { assetRepo.insertAsset(any()) }
    }

    @Test
    fun `addAsset 非股票基金走 insertAsset 而非 addOrUpdateStockAsset`() = runBlocking {
        val vm = buildVm()
        vm.addAsset(depositAsset)
        coVerify { assetRepo.insertAsset(depositAsset) }
        coVerify(exactly = 0) { assetRepo.addOrUpdateStockAsset(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `addAsset 外币股票的币种与到期日透传给 addOrUpdateStockAsset 不被重置成 CNY`() = runBlocking {
        // 回归：此前新建美股（USD）会经 addOrUpdateStockAsset 把币种静默重置成 CNY、
        // 到期日丢了。这里断言 ViewModel 把表单的 currency/dueDate 原样透传下去。
        val vm = buildVm()
        val usdAsset = Asset(
            name = "Apple", type = AssetType.STOCK, symbol = "AAPL",
            account = "美股", balance = 10.0, currency = "USD", dueDate = "2026-12-31"
        )
        vm.addAsset(usdAsset)
        coVerify {
            assetRepo.addOrUpdateStockAsset(
                symbol = "AAPL",
                name = "Apple",
                balance = 10.0,
                price = any(),
                account = "美股",
                assetType = AssetType.STOCK,
                fee = any(),
                currency = "USD",
                dueDate = "2026-12-31",
                notes = any(),
                interestRate = any(),
                currentPrice = any(),
                // 手动「添加」路径显式关闭交易记录（#5）
                recordTransaction = false
            )
        }
        // 股票/基金仍走合并专用路径，不该走 insertAsset
        coVerify(exactly = 0) { assetRepo.insertAsset(any()) }
    }

    @Test
    fun `同步被时间预算截断时提示用户已保留上次数据`() = runBlocking {
        // 90 秒预算用尽不是失败：不转圈到天荒地老，但必须让用户知道
        // 「这次没跑完、界面是上次的数据」。截断标记要一路透传到 errorMessage。
        mockkObject(AssetSyncManager)
        try {
            coEvery {
                AssetSyncManager.syncAllAssets(any(), any(), any(), any(), any(), any(), any(), any(), any())
            } returns SyncResult(successCount = 3, failedCount = 1, truncatedByTimeLimit = true)

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
    fun `同步未超时时不弹截断提示`() = runBlocking {
        mockkObject(AssetSyncManager)
        try {
            coEvery {
                AssetSyncManager.syncAllAssets(any(), any(), any(), any(), any(), any(), any(), any(), any())
            } returns SyncResult(successCount = 5, failedCount = 0, truncatedByTimeLimit = false)

            val vm = buildVm(initialAssets = listOf(stockAsset))
            val (trackJob, latest) = vm.uiState.trackIn(this)
            try {
                vm.refresh()
                waitUntil { !latest().isRefreshing }

                assertEquals(null, latest().errorMessage)
            } finally {
                trackJob.cancel()
            }
        } finally {
            unmockkAll()
        }
    }

    @Test
    fun `refresh 成功时结果写入 pendingSyncResult，消费后清空`() = runTest {
        // 回归：结果放 UiState（pendingSyncResult），而不是 SharedFlow——
        // replay=0 切走 Tab 会丢、replay=1 回页会重复弹。写状态 + 消费后清空两者都避开。
        mockkObject(AssetSyncManager)
        try {
            val expected = SyncResult(successCount = 7, failedCount = 2, truncatedByTimeLimit = false)
            coEvery {
                AssetSyncManager.syncAllAssets(any(), any(), any(), any(), any(), any(), any(), any(), any())
            } returns expected

            val vm = buildVm(initialAssets = listOf(stockAsset))
            // uiState 是 WhileSubscribed 派生流：不订阅时 .value 恒为空初值，
            // refresh 会命中“资产加载中”守卫而不是真正的同步路径。
            val (trackJob, _) = vm.uiState.trackIn(this)
            try {
                vm.refresh()
                // refresh 走真实 Dispatchers.IO：直接等状态里出现结果，runTest 自身超时兜底
                val state = vm.uiState.first { it.pendingSyncResult != null }
                assertEquals(expected, state.pendingSyncResult)
                assertEquals(null, state.errorMessage)

                // 消费后必须清掉，否则回到首页会再弹一次旧结果
                vm.clearPendingSyncResult()
                assertEquals(null, vm.uiState.value.pendingSyncResult)
            } finally {
                trackJob.cancel()
            }
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
            val (trackJob, _) = vm.uiState.trackIn(this)
            try {
                vm.refresh()
                val state = vm.uiState.first { it.errorMessage != null }
                assertEquals("刷新失败：网络挂了", state.errorMessage)
                assertEquals(null, state.pendingSyncResult)
            } finally {
                trackJob.cancel()
            }
        } finally {
            unmockkAll()
        }
    }

    @Test
    fun `刷新算出的年度分红写入缓存_回首页重进不再回落到旧值`() = runBlocking {
        // A1a（阻断）：刷新 phase-2 此前不写年度分红缓存，用户在首页点刷新算出新值后切走再回，
        // 进场读到的还是旧的 annualDividendCache → 刷新后停在旧值。修复后刷新统一经唯一写入点
        // [DashboardPreferencesManager.persistAnnualDividendIncome] 落盘（含记录版本摘要），
        // 回首页进场命中新值。这里断言：刷新恰好落盘一次（修复前为 0），且落盘值就是刷新算出的
        // 200.0；随后重进首页显示 200.0，不再回落到旧缓存 / 兜底值。
        mockkObject(AssetSyncManager)
        try {
            coEvery {
                AssetSyncManager.syncAllAssets(any(), any(), any(), any(), any(), any(), any(), any(), any())
            } returns SyncResult(successCount = 1, failedCount = 0, truncatedByTimeLimit = false)

            val vm = buildVm(initialAssets = listOf(stockAsset))
            val (trackJob, latest) = vm.uiState.trackIn(this)
            try {
                waitUntil { latest().assetsFrameReady }

                // 刷新 phase-2 用 200.0 算出的新收入必须落盘
                coEvery { useCase.load(any(), any(), any(), any()) } returns DividendIncomeLoader.IncomeResult(
                    dividendsBySymbol = emptyMap(),
                    recordsBySymbol = emptyMap(),
                    totalAnnualIncome = 200.0,
                    hasDividendData = true
                )
                vm.refresh()
                waitUntil { !latest().isRefreshing }

                // 刷新必须恰好落盘一次（修复前刷新不写缓存 → 0 次）
                verify(exactly = 1) { prefsMgr.updatePreferences(any()) }
                // 落盘值就是刷新算出的 200.0（而非旧缓存 0.0 / 兜底 100.0）
                assertEquals(200.0, prefsFlow.value.annualDividendCache, 0.001)
                assertTrue(
                    "记录版本摘要已写入，进场命中判据闭合（A1b：手动改分红后缓存立刻失效）",
                    prefsFlow.value.annualDividendRecordsVersion.isNotEmpty()
                )

                // 回首页重进：进场命中所写缓存，显示刷新算出的 200.0，不再回落到旧/兜底值
                vm.loadDividendIncome(listOf(stockAsset))
                waitUntil { latest().realAnnualIncome == 200.0 }
                assertEquals(200.0, latest().realAnnualIncome, 0.001)
            } finally {
                trackJob.cancel()
            }
        } finally {
            unmockkAll()
        }
    }
}
