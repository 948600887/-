package com.example.stockdiary.ui.viewmodels

import com.example.stockdiary.data.repository.AssetRepository
import com.example.stockdiary.data.sync.AssetSyncManager
import com.example.stockdiary.data.prefs.DashboardPreferences
import com.example.stockdiary.data.prefs.DashboardPreferencesManager
import com.example.stockdiary.data.dividend.DividendIncomeLoader
import com.example.stockdiary.data.dividend.DividendIncomeUseCase
import com.example.stockdiary.data.dividend.daysInCurrentYear
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

    private fun buildVm(): DashboardViewModel {
        every { container.assetRepository } returns assetRepo
        every { container.dividendIncomeUseCase } returns useCase
        every { container.dashboardPreferencesManager } returns prefsMgr
        every { container.context } returns mockk(relaxed = true)
        // 非挂起函数用 every / 挂起函数用 coEvery
        every { prefsMgr.loadPreferences() } returns DashboardPreferences()
        // 模拟 Manager 的真实契约：拉磁盘最新值 → 套用 mutate → 存盘 → 返回合并结果。
        // prefsMgr 是 mock，其 updatePreferences 是桩、真实方法体不会执行，缺省返回值会是
        // MockK 造的假对象，导致 _preferences 被污染。
        every { prefsMgr.updatePreferences(any()) } answers {
            val mutate = firstArg<(DashboardPreferences) -> DashboardPreferences>()
            val updated = mutate(prefsMgr.loadPreferences())
            prefsMgr.savePreferences(updated)
            updated
        }
        every { assetRepo.getAllAssets() } returns flowOf(emptyList())
        every { assetRepo.getAllSnapshots() } returns flowOf(emptyList())
        coEvery { assetRepo.getAllAccounts() } returns emptyList()
        coEvery { useCase.load(any(), any(), any(), any()) } returns DividendIncomeLoader.IncomeResult(
            dividendsBySymbol = emptyMap(),
            recordsBySymbol = emptyMap(),
            totalAnnualIncome = 1234.5,
            hasDividendData = true
        )
        coEvery { assetRepo.addOrUpdateStockAsset(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns stockAsset
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
    fun `addAsset 股票走 addOrUpdateStockAsset 而非 insertAsset`() = runBlocking {
        val vm = buildVm()
        vm.addAsset(stockAsset)
        coVerify { assetRepo.addOrUpdateStockAsset(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { assetRepo.insertAsset(any()) }
    }

    @Test
    fun `addAsset 非股票基金走 insertAsset 而非 addOrUpdateStockAsset`() = runBlocking {
        val vm = buildVm()
        vm.addAsset(depositAsset)
        coVerify { assetRepo.insertAsset(depositAsset) }
        coVerify(exactly = 0) { assetRepo.addOrUpdateStockAsset(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
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
                currentPrice = any()
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
    fun `同步未超时时不弹截断提示`() = runBlocking {
        mockkObject(AssetSyncManager)
        try {
            coEvery {
                AssetSyncManager.syncAllAssets(any(), any(), any(), any(), any(), any(), any(), any(), any())
            } returns SyncResult(successCount = 5, failedCount = 0, truncatedByTimeLimit = false)

            val vm = buildVm()
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
            // 不要用 withTimeout 包：refresh 内部走真实的 Dispatchers.IO，runTest 的虚拟时钟
            // 会在调度器空转时把 5s 直接推完，偶发 TimeoutCancellationException（假失败）。
            // 直接 await，未发射时由 runTest 自身的 60s 超时兜底。
            val result = event.await()
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
