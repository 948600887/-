package com.example.stockdiary.ui.viewmodels

import com.example.stockdiary.data.AssetRepository
import com.example.stockdiary.data.DashboardPreferences
import com.example.stockdiary.data.DashboardPreferencesManager
import com.example.stockdiary.data.DividendIncomeLoader
import com.example.stockdiary.data.DividendIncomeUseCase
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.di.AppContainer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
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
        every { assetRepo.getAllAssets() } returns flowOf(emptyList())
        every { assetRepo.getAllSnapshots() } returns flowOf(emptyList())
        coEvery { assetRepo.getAllAccounts() } returns emptyList()
        coEvery { useCase.load(any(), any(), any()) } returns DividendIncomeLoader.IncomeResult(
            dividendsBySymbol = emptyMap(),
            recordsBySymbol = emptyMap(),
            totalAnnualIncome = 1234.5,
            hasDividendData = true
        )
        coEvery { assetRepo.addOrUpdateStockAsset(any(), any(), any(), any(), any(), any(), any()) } returns stockAsset
        coEvery { assetRepo.insertAsset(any()) } returns 1L
        return DashboardViewModel(container)
    }

    @Test
    fun `loadDividendIncome 汇总年化收入与每日预估并结束加载态`() = runBlocking {
        val vm = buildVm()
        vm.loadDividendIncome(listOf(stockAsset))
        // 必须连 dividendLoading 一起等：它在 finally 里复位，比收入赋值晚一步
        waitUntil { vm.realAnnualIncome.value == 1234.5 && !vm.dividendLoading.value }
        assertEquals(1234.5, vm.realAnnualIncome.value, 0.001)
        assertEquals(1234.5 / 365.0, vm.estimatedDailyDividend.value, 1e-6)
        assertFalse(vm.dividendLoading.value)
    }

    @Test
    fun `loadDividendIncome 空资产列表直接返回不报错`() = runBlocking {
        val vm = buildVm()
        vm.loadDividendIncome(emptyList())
        Thread.sleep(100)
        assertEquals(0.0, vm.realAnnualIncome.value, 0.001)
        assertFalse(vm.dividendLoading.value)
    }

    @Test
    fun `addAsset 股票走 addOrUpdateStockAsset 而非 insertAsset`() = runBlocking {
        val vm = buildVm()
        vm.addAsset(stockAsset)
        coVerify { assetRepo.addOrUpdateStockAsset(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { assetRepo.insertAsset(any()) }
    }

    @Test
    fun `addAsset 非股票基金走 insertAsset 而非 addOrUpdateStockAsset`() = runBlocking {
        val vm = buildVm()
        vm.addAsset(depositAsset)
        coVerify { assetRepo.insertAsset(depositAsset) }
        coVerify(exactly = 0) { assetRepo.addOrUpdateStockAsset(any(), any(), any(), any(), any(), any(), any()) }
    }
}
