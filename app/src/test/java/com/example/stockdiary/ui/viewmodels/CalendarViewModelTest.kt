package com.example.stockdiary.ui.viewmodels

import com.example.stockdiary.data.repository.AssetRepository
import com.example.stockdiary.data.DashboardPreferences
import com.example.stockdiary.data.DashboardPreferencesManager
import com.example.stockdiary.data.dividend.DividendIncomeLoader
import com.example.stockdiary.data.dividend.DividendIncomeUseCase
import com.example.stockdiary.data.dividend.DividendRecord
import com.example.stockdiary.data.repository.DividendRepository
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.database.SnapshotDao
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.time.LocalDate

/**
 * CalendarViewModel 单测。
 *
 * Dispatchers.Main 的生命周期是关键：VM 内部的 generateCalendarData 走真实的
 * withContext(Dispatchers.IO)，IO 跑完再切回 Main。若每个用例都在 runTest 内部
 * setMain、finally 里 resetMain，那些慢一拍的 IO 续体会在 Main 被撤掉之后才去 dispatch，
 * 直接抛 "Module with the Main dispatcher had failed to initialize"，并污染下一个用例
 * （表现为 UncaughtExceptionsBeforeTest）。所以 Main 在 @BeforeClass 设一次、@AfterClass 才 reset。
 *
 * 同理，IO/Default 是真实线程，虚拟时钟 advanceUntilIdle 推不动它们，断言只能实时轮询等待。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModelTest {

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
    private val divRepo = mockk<DividendRepository>(relaxed = true)
    private val snapshotDao = mockk<SnapshotDao>(relaxed = true)
    private val prefsMgr = mockk<DashboardPreferencesManager>(relaxed = true)
    // DividendIncomeUseCase 本身用挂起 lambda 注入，直接用真实对象做桩。
    // 刻意**不加**延后：这样 loadDividendIncome 会先于 generateCalendarData 完成写入，
    // 正好是原先会把 realAnnualIncome 覆盖掉的顺序 —— 用例仍通过即证明 uiState 的
    // 原子更新（update{}）确实修好了那处竞态。
    private val useCase = DividendIncomeUseCase { _assets, _forceRefresh, _tag, _cacheOnly ->
        DividendIncomeLoader.IncomeResult(
            dividendsBySymbol = emptyMap(),
            recordsBySymbol = emptyMap(),
            totalAnnualIncome = 1234.5,
            hasDividendData = true
        )
    }

    private val stockAsset = Asset(
        name = "长江电力", type = AssetType.STOCK, symbol = "600900",
        account = "默认", balance = 1000.0
    )

    /** 实时轮询等 IO/Default 上的协程落地；返回是否达成，失败细节交给后续断言 */
    private fun waitUntil(timeoutMs: Long = 3000, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            Thread.sleep(5)
        }
        return cond()
    }

    private fun buildVm(): CalendarViewModel {
        every { prefsMgr.loadPreferences() } returns DashboardPreferences()
        every { assetRepo.getAllAssets() } returns flowOf(listOf(stockAsset))
        coEvery { snapshotDao.getAllSnapshotsSync() } returns emptyList()
        // 两个日历生成路径都会先调 preFetchDividends，必须显式打桩：
        // 只依赖 relaxed 会让它落到未初始化字段的真实实现上抛异常，日历生成整体失败
        coEvery { divRepo.preFetchDividends(any(), any(), any()) } returns Unit
        return CalendarViewModel(assetRepo, divRepo, snapshotDao, prefsMgr, useCase)
    }

    @Test
    fun `构造时加载资产并汇总分红收入`() = runBlocking {
        val vm = buildVm()
        waitUntil(timeoutMs = 6000) { vm.uiState.value.realAnnualIncome == 1234.5 }
        assertEquals(1234.5, vm.uiState.value.realAnnualIncome, 0.001)
        assertEquals(listOf(stockAsset), vm.uiState.value.assets)
    }

    @Test
    fun `切换标签页更新 selectedTab`() = runBlocking {
        val vm = buildVm()
        waitUntil { vm.uiState.value.assets.isNotEmpty() }
        vm.selectTab("收支日历")
        assertEquals("收支日历", vm.uiState.value.selectedTab)
        vm.selectTab("资产日历")
        assertEquals("资产日历", vm.uiState.value.selectedTab)
    }

    @Test
    fun `切换月份与原月份相同时不触发重算`() = runBlocking {
        val vm = buildVm()
        waitUntil { vm.uiState.value.assets.isNotEmpty() }
        val month = vm.uiState.value.currentMonth
        vm.selectMonth(month)
        assertEquals(month, vm.uiState.value.currentMonth)
    }

    @Test
    fun `选择日期直接写入 uiState`() = runBlocking {
        val vm = buildVm()
        waitUntil { vm.uiState.value.assets.isNotEmpty() }
        val date = LocalDate.now()
        vm.selectDate(date)
        assertEquals(date, vm.uiState.value.selectedDate)
        vm.selectDate(null)
        assertNull(vm.uiState.value.selectedDate)
    }

    @Test
    fun `更新月度生活费写入 uiState 并持久化`() = runBlocking {
        val vm = buildVm()
        waitUntil { vm.uiState.value.assets.isNotEmpty() }
        vm.updateMonthlyExpense("8000")
        assertEquals("8000", vm.uiState.value.monthlyExpense)
        // savePreferences 是非挂起函数，用 verify（不是 coVerify）
        waitUntil(timeoutMs = 2000) {
            runCatching { verify { prefsMgr.savePreferences(any()) } }.isSuccess
        }
        verify { prefsMgr.savePreferences(any()) }
    }

    @Test
    fun `收支日历按当前月分红记录聚合到对应日期的金额`() = runBlocking {
        // 记录落在「当前真实月份」，保证与 VM 内部 YearMonth.now() 对齐
        val recDate = LocalDate.now().withDayOfMonth(15)
        coEvery { divRepo.getDividendsBySymbol(any(), any()) } returns listOf(
            DividendRecord(
                exDividendDate = recDate.toString(),
                dividendPerShare = "10.0",
                announceDate = "",
                fiscalYear = recDate.year.toString(),
                paymentDate = null
            )
        )
        val vm = buildVm()
        waitUntil { vm.uiState.value.assets.isNotEmpty() }
        vm.selectTab("收支日历")
        waitUntil { vm.uiState.value.dividends.isNotEmpty() }

        // 每十股 10 元 × 1000 股 ÷ 10 = 1000
        assertEquals(1000.0, vm.uiState.value.calendarData[recDate] ?: 0.0, 0.001)
        assertTrue(vm.uiState.value.dividendDates.contains(recDate))
        assertEquals(1, vm.uiState.value.dividends.size)
        assertEquals("600900", vm.uiState.value.dividends.first().first)
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test
    fun `无分红记录的月份不产生分红日期也不报错`() = runBlocking {
        coEvery { divRepo.getDividendsBySymbol(any(), any()) } returns emptyList()
        val vm = buildVm()
        waitUntil { vm.uiState.value.assets.isNotEmpty() }
        vm.selectTab("收支日历")
        Thread.sleep(100)
        waitUntil { !vm.uiState.value.isLoading }
        assertTrue(vm.uiState.value.dividendDates.isEmpty())
        assertTrue(vm.uiState.value.dividends.isEmpty())
        assertNull(vm.uiState.value.errorMessage)
    }
}
