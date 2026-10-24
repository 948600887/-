package com.example.stockdiary.ui.viewmodels

import com.example.stockdiary.data.repository.AssetRepository
import com.example.stockdiary.data.prefs.DashboardPreferences
import com.example.stockdiary.data.prefs.DashboardPreferencesManager
import com.example.stockdiary.data.dividend.DividendIncomeLoader
import com.example.stockdiary.data.dividend.DividendIncomeUseCase
import com.example.stockdiary.data.dividend.DividendRecord
import com.example.stockdiary.data.repository.DividendRepository
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetSnapshot
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.database.SnapshotDao
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

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

    private fun buildVm(
        assets: List<Asset> = listOf(stockAsset),
        snapshots: List<AssetSnapshot> = emptyList()
    ): CalendarViewModel {
        every { prefsMgr.loadPreferences() } returns DashboardPreferences()
        // 模拟 Manager 的真实契约：拉磁盘最新值 → 套用 mutate → 存盘 → 返回合并结果。
        // 必须这么打桩：prefsMgr 是 mock，其 updatePreferences 是桩、真实方法体不会执行，
        // 于是内部的 load/save 都不会发生；且缺省返回值是 MockK 造的假对象。
        every { prefsMgr.updatePreferences(any()) } answers {
            val mutate = firstArg<(DashboardPreferences) -> DashboardPreferences>()
            val updated = mutate(prefsMgr.loadPreferences())
            prefsMgr.savePreferences(updated)
            updated
        }
        every { assetRepo.getAllAssets() } returns flowOf(assets)
        // E3：日历改用按月查询 getSnapshotsSinceSync，不再整表读取 asset_snapshots
        coEvery { snapshotDao.getSnapshotsSinceSync(any()) } returns snapshots
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
    fun `更新月度生活费只改该字段不覆盖其他偏好`() = runBlocking {
        val vm = buildVm()
        waitUntil { vm.uiState.value.assets.isNotEmpty() }

        // 模拟磁盘上已有别的页面设过的偏好（非默认值），验证月度生活费更新不会把它们冲掉。
        // 必须放在 buildVm() 之后打桩：buildVm 内部同样会打桩 loadPreferences，后打的桩才生效。
        every { prefsMgr.loadPreferences() } returns DashboardPreferences(
            monthlyExpense = "5000",
            showTotalAssets = true,   // 非默认：设置页开过「显示总资产」
            defaultPeriod = "3M"      // 非默认：历史页选过「3月」
        )
        val slot = slot<DashboardPreferences>()
        every { prefsMgr.savePreferences(capture(slot)) } returns Unit

        vm.updateMonthlyExpense("9999")

        waitUntil(timeoutMs = 2000) { slot.isCaptured }
        assertEquals("9999", slot.captured.monthlyExpense)
        // 其余字段必须原样保留——这正是「共享整块偏好互相踩踏」要锁的契约
        assertTrue(slot.captured.showTotalAssets)
        assertEquals("3M", slot.captured.defaultPeriod)
    }

    @Test
    fun `收支日历按当前月分红记录聚合到对应日期的金额`() = runBlocking {
        val vm = buildVm()
        waitUntil { vm.uiState.value.assets.isNotEmpty() }
        // 月份锚定 VM 实际使用的 currentMonth（VM 构造时固定，此后只有 selectMonth 能改），
        // 日期从它推导：测试若自己再采一次 LocalDate.now()，两次采样跨过月边界时
        // 记录月份与 VM 的生成月份不一致，分红会被「只算当月」的过滤掉，断言必挂。
        // 打桩放在构造之后：默认标签「资产日历」那轮生成同样会读分红记录并写下
        // 非空 dividends（但 calendarData 全 0），打桩过早会让等待条件被错误的
        // 步骤满足——高负载下表现为 expected 1000.0 but was 0.0 的偶发失败。
        val recDate = vm.uiState.value.currentMonth.atDay(15)
        coEvery { divRepo.getDividendsBySymbol(any(), any()) } returns listOf(
            DividendRecord(
                exDividendDate = recDate.toString(),
                dividendPerShare = "10.0",
                announceDate = "",
                fiscalYear = recDate.year.toString(),
                paymentDate = null
            )
        )
        vm.selectTab("收支日历")
        // 聚合金额是「收支日历」生成独有的产物（资产日历生成只把快照写进 calendarData，
        // 分红日在它那里恒为 0.0）：等这个值落地，等价于等到正确的异步步骤完成，
        // 再走其余断言，避免高负载下读到中间状态
        waitUntil { vm.uiState.value.calendarData[recDate] == 1000.0 }

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

    @Test
    fun `只变实时价不触发日历重算_数量或币种变化才触发`() = runBlocking {
        // getAllAssets() 是 Room 流：同步时每更新一个 currentPrice 就重发一次，实测一次全量刷新
        // 让「生成资产日历」白跑 20+ 次。日历与实时价无关，故按 (symbol,balance,currency) 签名去重。
        // 下面三次发射里，只有「首次」和「数量变化」应触发重算，「仅价格变化」那次必须被去重掉。
        val regenCount = java.util.concurrent.atomic.AtomicInteger(0)
        coEvery { divRepo.getDividendsBySymbol(any(), any()) } coAnswers {
            regenCount.incrementAndGet()
            emptyList<DividendRecord>()
        }
        every { prefsMgr.loadPreferences() } returns DashboardPreferences()
        every { assetRepo.getAllAssets() } returns flowOf(
            listOf(stockAsset),
            listOf(stockAsset.copy(currentPrice = 99.0)),  // 仅变实时价 → 不重算
            listOf(stockAsset.copy(balance = 2000.0))       // 变数量 → 重算
        )
        coEvery { snapshotDao.getSnapshotsSinceSync(any()) } returns emptyList()
        coEvery { divRepo.preFetchDividends(any(), any(), any()) } returns Unit

        val vm = CalendarViewModel(assetRepo, divRepo, snapshotDao, prefsMgr, useCase)

        // VM 已构造并处理完首次发射（末次发射的 balance=2000 已反映到 assets）
        waitUntil { vm.uiState.value.assets.firstOrNull()?.balance == 2000.0 }
        waitUntil(timeoutMs = 6000) { regenCount.get() >= 2 }
        // 再稳一手：若去重失效，价格那次会晚到凑成第 3 次，这里必须仍是 2
        Thread.sleep(400)
        assertEquals("价格抖动应被去重、只重算 2 次（首次 + 数量变化）", 2, regenCount.get())
    }

    // ---------- 「当日净资产」与资产页一致性（0.01 差异回归） ----------

    @Test
    fun `资产日历今天一格取实时净值_当日快照过期也不照抄`() = runBlocking {
        val today = LocalDate.now()
        // 复现用户反馈：当日快照是早先某一轮同步固化的旧值（旧价/旧汇率），
        // 资产页早已按实时价汇总，同一天两页差 0.01 元
        val staleSnapshot = AssetSnapshot(
            date = today.toString(),
            timestamp = 0L,
            totalAssets = 12345.67,
            totalLiabilities = 0.0,
            netAssets = 12345.67
        )
        // 1000 股 × 12.34568 = 12345.68：实时值比旧快照高 1 分，正是那 0.01
        val priced = stockAsset.copy(currentPrice = 12.34568)
        coEvery { divRepo.getDividendsBySymbol(any(), any()) } returns emptyList()

        val vm = buildVm(assets = listOf(priced), snapshots = listOf(staleSnapshot))
        waitUntil { !vm.uiState.value.isLoading && vm.uiState.value.calendarData.isNotEmpty() }

        // 「当日净资产」必须与资产页同口径（实时汇总），不得照抄过期快照
        assertEquals(12345.68, vm.uiState.value.calendarData[today] ?: 0.0, 0.001)
    }

    @Test
    fun `今天没有快照时也显示实时净值`() = runBlocking {
        val today = LocalDate.now()
        val priced = stockAsset.copy(currentPrice = 8.88) // 1000 股 × 8.88 = 8880
        coEvery { divRepo.getDividendsBySymbol(any(), any()) } returns emptyList()

        val vm = buildVm(assets = listOf(priced), snapshots = emptyList())
        waitUntil { !vm.uiState.value.isLoading && vm.uiState.value.calendarData.isNotEmpty() }

        // 当日还没存过快照（例如同步被截断不存）：今天这格也不能空着，
        // 按实时净值显示，与资产页一致
        assertEquals(8880.0, vm.uiState.value.calendarData[today] ?: 0.0, 0.001)
    }

    @Test
    fun `过去日期仍取当日快照_只有今天走实时口径`() = runBlocking {
        val today = LocalDate.now()
        // 今天是 1 号时当月没有「过去的日子」可断言，跳过（纯函数用例已覆盖该口径）
        assumeTrue(today.dayOfMonth > 1)
        val yesterday = today.minusDays(1)
        val priced = stockAsset.copy(currentPrice = 10.0) // 实时 1000 × 10 = 10000
        coEvery { divRepo.getDividendsBySymbol(any(), any()) } returns emptyList()

        val vm = buildVm(
            assets = listOf(priced),
            snapshots = listOf(
                AssetSnapshot(
                    date = yesterday.toString(),
                    timestamp = 0L,
                    totalAssets = 9999.99,
                    totalLiabilities = 0.0,
                    netAssets = 9999.99
                ),
                AssetSnapshot(
                    date = today.toString(),
                    timestamp = 0L,
                    totalAssets = 8888.88,
                    totalLiabilities = 0.0,
                    netAssets = 8888.88
                )
            )
        )
        waitUntil { !vm.uiState.value.isLoading && vm.uiState.value.calendarData.isNotEmpty() }

        // 昨天是历史记录：照抄当日快照，不被实时值覆盖
        assertEquals(9999.99, vm.uiState.value.calendarData[yesterday] ?: 0.0, 1e-9)
        // 今天：实时口径（10000）覆盖当日旧快照（8888.88）
        assertEquals(10000.0, vm.uiState.value.calendarData[today] ?: 0.0, 0.001)
    }

    @Test
    fun `翻到过去月份不发生实时覆盖_整月都是快照`() = runBlocking {
        val thisMonth = YearMonth.now()
        val lastMonth = thisMonth.minusMonths(1)
        val dayInLastMonth = lastMonth.atDay(lastMonth.lengthOfMonth())
        val priced = stockAsset.copy(currentPrice = 10.0)
        coEvery { divRepo.getDividendsBySymbol(any(), any()) } returns emptyList()

        val vm = buildVm(
            assets = listOf(priced),
            snapshots = listOf(
                AssetSnapshot(
                    date = dayInLastMonth.toString(),
                    timestamp = 0L,
                    totalAssets = 7777.77,
                    totalLiabilities = 0.0,
                    netAssets = 7777.77
                )
            )
        )
        waitUntil { !vm.uiState.value.isLoading && vm.uiState.value.calendarData.isNotEmpty() }
        vm.selectMonth(lastMonth)
        waitUntil { !vm.uiState.value.isLoading }

        // E3 回归锁：翻月触发的日历生成必须走按月查询 getSnapshotsSinceSync，
        // 而不是把整张 asset_snapshots 表都拉进来（老用户几百天快照时纯属浪费）
        coVerify { snapshotDao.getSnapshotsSinceSync(any()) }
        // 翻旧月：即使今天恰好也有快照，也不能把实时值写进上个月
        assertEquals(7777.77, vm.uiState.value.calendarData[dayInLastMonth] ?: 0.0, 1e-9)
        // 当月其余日子无快照 → 0（无数据）
        assertEquals(0.0, vm.uiState.value.calendarData[lastMonth.atDay(1)] ?: -1.0, 1e-9)
    }

    // ---------- buildAssetCalendarMonth 纯函数 ----------

    @Test
    fun `纯函数_今天取实时值_过去日子取快照_无快照日子为零`() {
        val month = YearMonth.of(2026, 10)
        val today = month.atDay(20)
        val result = buildAssetCalendarMonth(
            month = month,
            snapshotNetAssets = mapOf(
                month.atDay(10) to 1000.0,
                month.atDay(15) to 2000.0,
                today to 9999.99 // 当日旧快照：必须被实时值覆盖
            ),
            liveNetAssetsToday = 12345.68,
            today = today
        )

        assertEquals(31, result.size)
        assertEquals(1000.0, result[month.atDay(10)]!!, 1e-9)
        assertEquals(2000.0, result[month.atDay(15)]!!, 1e-9)
        assertEquals(12345.68, result[today]!!, 1e-9) // 今天：实时优先
        assertEquals(0.0, result[month.atDay(11)]!!, 1e-9) // 无快照 → 无数据
    }

    @Test
    fun `纯函数_今天不在视图月内时不覆盖`() {
        val month = YearMonth.of(2026, 9)
        val today = YearMonth.of(2026, 10).atDay(3) // 看 9 月，今天是 10 月
        val result = buildAssetCalendarMonth(
            month = month,
            snapshotNetAssets = mapOf(month.atDay(30) to 5000.0),
            liveNetAssetsToday = 12345.68,
            today = today
        )
        // 整月都是快照口径，实时值写不进来
        assertEquals(5000.0, result[month.atDay(30)]!!, 1e-9)
        assertEquals(30, result.size)
    }

    @Test
    fun `纯函数_当月无任何快照时今天仍显示实时净值`() {
        val month = YearMonth.of(2026, 2)
        val today = month.atDay(28)
        val result = buildAssetCalendarMonth(
            month = month,
            snapshotNetAssets = emptyMap(),
            liveNetAssetsToday = 66.6,
            today = today
        )
        assertEquals(28, result.size)
        assertEquals(66.6, result[today]!!, 1e-9)
        assertEquals(0.0, result[month.atDay(1)]!!, 1e-9)
    }
}
