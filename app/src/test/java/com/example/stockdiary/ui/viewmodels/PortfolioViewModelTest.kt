package com.example.stockdiary.ui.viewmodels

import com.example.stockdiary.data.AssetRepository
import com.example.stockdiary.data.DashboardPreferences
import com.example.stockdiary.data.DashboardPreferencesManager
import com.example.stockdiary.data.DividendIncomeLoader
import com.example.stockdiary.data.DividendIncomeUseCase
import com.example.stockdiary.data.SortOrder
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.di.AppContainer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
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
        every { assetRepo.getAllAssets() } returns flowOf(emptyList())
        coEvery { assetRepo.getAllAccounts() } returns emptyList()
        coEvery { useCase.load(any(), any(), any()) } returns DividendIncomeLoader.IncomeResult(
            dividendsBySymbol = emptyMap(),
            recordsBySymbol = emptyMap(),
            totalAnnualIncome = 888.0,
            hasDividendData = true
        )
        coEvery { assetRepo.addOrUpdateStockAsset(any(), any(), any(), any(), any(), any(), any()) } returns stockAsset
        coEvery { assetRepo.insertAsset(any()) } returns 1L
        return PortfolioViewModel(container)
    }

    @Test
    fun `loadDividendIncome 汇总年化分红收入并结束加载态`() = runBlocking {
        val vm = buildVm()
        vm.loadDividendIncome(listOf(stockAsset))
        // 连 dividendLoading 一起等：它在 finally 里复位，比收入赋值晚一步
        waitUntil { vm.estimatedAnnualDividend.value == 888.0 && !vm.dividendLoading.value }
        assertEquals(888.0, vm.estimatedAnnualDividend.value, 0.001)
        assertFalse(vm.dividendLoading.value)
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
        coVerify { assetRepo.addOrUpdateStockAsset(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { assetRepo.insertAsset(any()) }
    }

    @Test
    fun `addAsset 非股票基金走 insertAsset`() = runBlocking {
        val vm = buildVm()
        vm.addAsset(depositAsset)
        coVerify { assetRepo.insertAsset(depositAsset) }
        coVerify(exactly = 0) { assetRepo.addOrUpdateStockAsset(any(), any(), any(), any(), any(), any(), any()) }
    }
}
