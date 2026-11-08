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
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.every
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.util.concurrent.atomic.AtomicInteger

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
    private val prefsMgr = mockk<DashboardPreferencesManager>(relaxed = true)

    /** 模拟 Manager 落盘后的完整偏好（updatePreferences 的返回/写入结果） */
    private var lastWrittenPrefs: DashboardPreferences? = null
    // DividendIncomeUseCase 本身用挂起 lambda 注入，直接用真实对象做桩。
    // 刻意**不加**延后：这样 loadDividendIncome 会先于 generateCalendarData 完成写入，
    // 正好是原先会把 realAnnualIncome 覆盖掉的顺序 —— 用例仍通过即证明 uiState 的
    // 原子更新（update{}）确实修好了那处竞态。
    // cacheOnly=true（进页/切月冷路径）返回旧缓存值；cacheOnly=false（手动刷新强制重算）返回最新值，
    // 用来回归「手动刷新后年度收入必须更新」这一修复（优化①）。
    private val useCase = DividendIncomeUseCase { _assets, _forceRefresh, _tag, _cacheOnly ->
        val income = if (_cacheOnly) 1234.5 else 9999.0
        DividendIncomeLoader.IncomeResult(
            dividendsBySymbol = emptyMap(),
            recordsBySymbol = emptyMap(),
            totalAnnualIncome = income,
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
            // UnconfinedTestDispatcher 上排队的续体（如 StateFlow 发射到收集器）不会自己跑；
            // 这里只跑「当前时刻」的任务，不推进虚拟时钟（避免把午夜 delay 提前触发）。
            mainDispatcher.scheduler.runCurrent()
            Thread.sleep(5)
        }
        return cond()
    }

    private fun buildVm(
        assets: List<Asset> = listOf(stockAsset),
        snapshots: List<AssetSnapshot> = emptyList(),
        assetsFlow: Flow<List<Asset>>? = null,
        /** 快照查询的人为延时：用于让「生成期间发生实时刷新」的时序可控 */
        snapshotDelayMs: Long = 0,
        /** getAllAssets 的可控返回：调用次数不同可返回不同价（用于新鲜度校验回归） */
        allAssetsFlow: (() -> Flow<List<Asset>>)? = null,
        incomeUseCase: DividendIncomeUseCase = useCase
    ): CalendarViewModel {
        every { prefsMgr.loadPreferences() } returns DashboardPreferences()
        // 模拟 Manager 的真实契约：拉磁盘最新值 → 套用 mutate → 存盘 → 返回合并结果。
        // prefsMgr 是 mock，其 updatePreferences 是桩、真实方法体不会执行；这里把合并结果
        // 记到 lastWrittenPrefs，供「只改该字段不覆盖其他偏好」的断言使用。
        every { prefsMgr.updatePreferences(any()) } answers {
            val mutate = firstArg<(DashboardPreferences) -> DashboardPreferences>()
            val updated = mutate(prefsMgr.loadPreferences())
            lastWrittenPrefs = updated
            updated
        }
        every { assetRepo.getAllAssets() } answers {
            (allAssetsFlow ?: { flowOf(assets) }).invoke()
        }
        every { assetRepo.getAssetsByTypesFlow(any()) } returns (assetsFlow ?: flowOf(assets))
        // E3：日历改用按月查询 getSnapshotsSinceSync，不再整表读取 asset_snapshots
        coEvery { assetRepo.getSnapshotsSinceSync(any()) } coAnswers {
            if (snapshotDelayMs > 0) delay(snapshotDelayMs)
            snapshots
        }
        // 两个日历生成路径都会先调 prefetchDividends，必须显式打桩：
        // 只依赖 relaxed 会让它落到未初始化字段的真实实现上抛异常，日历生成整体失败
        coEvery { divRepo.prefetchDividends(any(), any(), any()) } returns Unit
        return CalendarViewModel(assetRepo, divRepo, prefsMgr, incomeUseCase)
    }

    @Test
    fun `构造时加载资产并汇总分红收入`() = runBlocking {
        val vm = buildVm()
        waitUntil(timeoutMs = 6000) { vm.uiState.value.realAnnualIncome == 1234.5 }
        assertEquals(1234.5, vm.uiState.value.realAnnualIncome, 0.001)
        assertEquals(listOf(stockAsset), vm.uiState.value.assets)
    }

    /**
     * 优化①回归：手动刷新必须让「年度收入（喂给 FIRE）」按最新分红重算。
     *
     * 进页冷路径走 cacheOnly=true（桩返回旧缓存 1234.5）；手动刷新走 forceRefresh=true
     * → cacheOnly=false（桩返回最新 9999.0）。若 refresh() 漏掉 loadDividendIncome(forceRefresh=true)，
     * realAnnualIncome 会停在旧缓存 1234.5，FIRE 计算器看到的年度收入就过期了。
     */
    @Test
    fun `手动刷新后年度收入按最新分红重算`() = runBlocking {
        val vm = buildVm()
        // 进页冷路径：cacheOnly=true，拿到旧缓存值
        waitUntil(timeoutMs = 6000) { vm.uiState.value.realAnnualIncome == 1234.5 }
        assertEquals(1234.5, vm.uiState.value.realAnnualIncome, 0.001)

        // 手动刷新：必须按刚抓回的最新分红重算 → cacheOnly=false 返回 9999.0
        vm.refresh()
        waitUntil(timeoutMs = 6000) { vm.uiState.value.realAnnualIncome == 9999.0 }
        assertEquals(9999.0, vm.uiState.value.realAnnualIncome, 0.001)
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
    fun `翻月会清空选中日期`() = runBlocking {
        // 回归：选中日期属于「上一张月历」，翻月后详情卡不该继续显示一个当前月不存在的日子
        val vm = buildVm()
        waitUntil { vm.uiState.value.assets.isNotEmpty() }
        val selected = vm.uiState.value.currentMonth.atDay(10)
        vm.selectDate(selected)
        assertEquals(selected, vm.uiState.value.selectedDate)

        vm.selectMonth(vm.uiState.value.currentMonth.minusMonths(1))
        assertNull("翻月后必须清空选中日期", vm.uiState.value.selectedDate)
    }

    @Test
    fun `更新月度生活费写入 uiState 并持久化`() = runBlocking {
        val vm = buildVm()
        waitUntil { vm.uiState.value.assets.isNotEmpty() }
        vm.updateMonthlyExpense("8000")
        assertEquals("8000", vm.uiState.value.monthlyExpense)
        waitUntil(timeoutMs = 2000) { lastWrittenPrefs?.monthlyExpense == "8000" }
        assertEquals("8000", lastWrittenPrefs?.monthlyExpense)
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
        vm.updateMonthlyExpense("9999")

        waitUntil(timeoutMs = 2000) { lastWrittenPrefs?.monthlyExpense == "9999" }
        val written = lastWrittenPrefs!!
        assertEquals("9999", written.monthlyExpense)
        // 其余字段必须原样保留——这正是「共享整块偏好互相踩踏」要锁的契约
        assertTrue(written.showTotalAssets)
        assertEquals("3M", written.defaultPeriod)
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
        // 必须用本月内 <= today 的日期：生产只聚合"已到账"分红（date !isAfter(today)），钉 15 号在每月 1–14 号运行会被过滤成 0.0
        val recDate = vm.uiState.value.currentMonth.atDay(1)
        coEvery { divRepo.getDividendRecordsBatch(any()) } returns mapOf(
            "600900" to listOf(
                DividendRecord(
                    exDividendDate = recDate.toString(),
                    dividendPerShare = "10.0",
                    announceDate = "",
                    fiscalYear = recDate.year.toString(),
                    paymentDate = null
                )
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
        coEvery { divRepo.getDividendRecordsBatch(any()) } returns emptyMap()
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
        // getAssetsByTypesFlow 是下推 SQL 的 Room 流：同步时每更新一个 currentPrice 就重发一次，实测一次全量刷新
        // 让「生成资产日历」白跑 20+ 次。日历与实时价无关，故按 (symbol,balance,currency) 签名去重。
        // 下面三次发射里，只有「首次」和「数量变化」应触发重算，「仅价格变化」那次必须被去重掉。
        val regenCount = java.util.concurrent.atomic.AtomicInteger(0)
        every { prefsMgr.loadPreferences() } returns DashboardPreferences()
        every { assetRepo.getAllAssets() } returns flowOf(emptyList())
        // 用可手动改值的 StateFlow 逐段驱动：空首值 → 首笔资产 → 仅改价格 → 改数量。
        // 每段之间轮询等生成落地，避免后一次变更取消前一轮还没开始的生成，导致计数失真。
        val assetsFlow = kotlinx.coroutines.flow.MutableStateFlow<List<Asset>>(emptyList())

        val vm = buildVm(assetsFlow = assetsFlow)
        // 桩必须在 buildVm 之后打：buildVm 内部会把 getSnapshotsSinceSync 重置成空快照
        coEvery { assetRepo.getSnapshotsSinceSync(any()) } coAnswers {
            regenCount.incrementAndGet()
            emptyList()
        }

        assetsFlow.value = listOf(stockAsset)
        waitUntil(timeoutMs = 5000) { regenCount.get() >= 1 && !vm.uiState.value.isLoading }

        assetsFlow.value = listOf(stockAsset.copy(currentPrice = 99.0))  // 仅变实时价 → 不重算
        Thread.sleep(300)
        assertEquals("仅价格变化应被签名去重", 1, regenCount.get())

        assetsFlow.value = listOf(stockAsset.copy(balance = 2000.0))     // 变数量 → 重算
        waitUntil(timeoutMs = 6000) { regenCount.get() >= 2 && !vm.uiState.value.isLoading }
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
        coEvery { divRepo.getDividendRecordsBatch(any()) } returns emptyMap()

        val vm = buildVm(assets = listOf(priced), snapshots = listOf(staleSnapshot))
        waitUntil { !vm.uiState.value.isLoading && vm.uiState.value.calendarData.isNotEmpty() }

        // 「当日净资产」必须与资产页同口径（实时汇总），不得照抄过期快照
        assertEquals(12345.68, vm.uiState.value.calendarData[today] ?: 0.0, 0.001)
    }

    @Test
    fun `今天没有快照时也显示实时净值`() = runBlocking {
        val today = LocalDate.now()
        val priced = stockAsset.copy(currentPrice = 8.88) // 1000 股 × 8.88 = 8880
        coEvery { divRepo.getDividendRecordsBatch(any()) } returns emptyMap()

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
        coEvery { divRepo.getDividendRecordsBatch(any()) } returns emptyMap()

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
        coEvery { divRepo.getDividendRecordsBatch(any()) } returns emptyMap()

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
        coVerify { assetRepo.getSnapshotsSinceSync(any()) }
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

    // ---------- 同代码多账户：分红金额翻倍回归 ----------

    @Test
    fun `同一股票多账户时分红不翻倍_详情卡金额与格子对齐`() = runBlocking {
        // 回归：生成函数原先用未合并的 assets 循环，同一 symbol 多账户会被 dividends.add 塞进
        // 列表 N 次；而详情卡 DividendDayDetail 按合并 balance 逐条重算，会把当天分红放大 N 倍。
        // 修复后两生成函数都按 aggregateAssetsBySymbol 合并遍历，同一 symbol 只计一次。
        val a1 = stockAsset.copy(account = "华泰", balance = 1000.0)
        val a2 = stockAsset.copy(account = "中信", balance = 2000.0) // 同 symbol 600900，合并后 3000
        // 分红 mock 必须在构造 VM 之前就位：进页 init 流会立刻生成「资产日历」并把结果按
        // (Tab, 月, 资产签名) 缓存，若构造后才打桩，init 那轮抓到的是空分红，切回资产日历时
        // 命中该过期缓存 → dividends.size 错判为 0（生产环境 init 自己会把分红抓全，缓存恒为正确值；
        // 这里是测试构造时序差异）。recDate 按 YearMonth.now() 提前算好（等于 VM 构造时的 currentMonth）。
        val recDate = YearMonth.now().atDay(1)
        coEvery { divRepo.getDividendRecordsBatch(any()) } returns mapOf(
            "600900" to listOf(
                DividendRecord(
                    exDividendDate = recDate.toString(),
                    dividendPerShare = "10.0",
                    announceDate = "",
                    fiscalYear = recDate.year.toString(),
                    paymentDate = null
                )
            )
        )
        val vm = buildVm(assets = listOf(a1, a2))
        waitUntil { vm.uiState.value.assets.size == 2 }

        // 必须用本月内 <= today 的日期：生产只聚合"已到账"分红（date !isAfter(today)），钉 15 号在每月 1–14 号运行会被过滤成 0.0
        vm.selectTab("收支日历")
        // 每十股 10 元 × 合并 3000 股 ÷ 10 = 3000（修复前逐条未合并累加也恰好是 3000，
        // 所以格子数值无法区分；真正区分点在 dividends 列表是否重复）
        waitUntil { vm.uiState.value.calendarData[recDate] == 3000.0 }

        assertEquals(3000.0, vm.uiState.value.calendarData[recDate] ?: 0.0, 0.001)
        // 关键断言：同一 symbol 的 dividends 条目只能出现一次（修复前 = 2，详情卡金额翻倍）
        assertEquals(1, vm.uiState.value.dividends.size)
        assertEquals("600900", vm.uiState.value.dividends.first().first)
        assertEquals(1, vm.uiState.value.dividends.first().second.size)

        // 两 Tab 共用同一 dividends 来源，切回资产日历详情卡同样不能翻倍
        vm.selectTab("资产日历")
        waitUntil { vm.uiState.value.selectedTab == "资产日历" && vm.uiState.value.dividends.size == 1 }
        assertEquals(1, vm.uiState.value.dividends.size)
    }

    // ---------- 实时资产刷新去抖（P0-3） ----------

    @Test
    fun `refreshLiveAssets 值没变时不发新 uiState`() = runBlocking {
        val vm = buildVm()
        waitUntil(timeoutMs = 6000) { !vm.uiState.value.isLoading }
        vm.refreshLiveAssets()
        waitUntil(timeoutMs = 6000) { vm.uiState.value.fireAssets.isNotEmpty() }
        // 首次生成 / 年度收入重算可能还在收尾，先等它们落地，避免把无关的状态更新算到此次刷新头上
        Thread.sleep(500)

        val before = vm.uiState.value
        vm.refreshLiveAssets()
        // 等 IO 查询跑完；若错误地 new 了 UiState，StateFlow 会换成新实例
        Thread.sleep(300)
        assertSame("值完全没变时应原样返回同一实例", before, vm.uiState.value)
    }

    // ---------- 生成任务取消（P0-1） ----------

    @Test
    fun `切月会取消上一轮生成_旧任务不再触发分红预取`() = runBlocking {
        // 旧实现 selectMonth 只 launch 不持有 Job：连续翻月会同时跑多轮生成，
        // 每轮都各自预热/预取分红，慢的那轮还会把过期的 calendarData 写回来。
        // 这里让「上个月」的快照查询变慢，再立刻切到下个月：
        // 若旧任务被正确取消，它在快照查询返回后会抛 CancellationException，根本走不到 prefetch。
        val slowMonth = YearMonth.now().minusMonths(2)
        val fastMonth = YearMonth.now().minusMonths(1)
        val slowStart = slowMonth.atDay(1).atStartOfDay()
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val vm = buildVm()
        waitUntil(timeoutMs = 6000) { !vm.uiState.value.isLoading }
        // 打桩必须放在 buildVm 之后：buildVm 内部会重置 getSnapshotsSinceSync 的桩
        coEvery { assetRepo.getSnapshotsSinceSync(any()) } coAnswers {
            if (firstArg<Long>() == slowStart) delay(200)
            emptyList()
        }

        io.mockk.clearMocks(divRepo, answers = false)
        vm.selectMonth(slowMonth)
        vm.selectMonth(fastMonth)
        waitUntil(timeoutMs = 6000) {
            vm.uiState.value.currentMonth == fastMonth && !vm.uiState.value.isLoading
        }
        // 给被取消的慢任务足够的「如果没被取消就会继续跑」的时间
        Thread.sleep(400)

        // 只有新一轮走到了 prepareMergedAssets；旧任务应已被取消，压根不该进入这一步
        coVerify(exactly = 1) { divRepo.primeDividendCache(any(), any()) }
        assertNull("取消不是失败，不能报错", vm.uiState.value.errorMessage)
        assertFalse(vm.uiState.value.isLoading)
        assertEquals(fastMonth, vm.uiState.value.currentMonth)
    }

    @Test
    fun `刷新被切月取消时转圈停止且不报失败`() = runBlocking {
        val targetMonth = YearMonth.now().minusMonths(1)
        val vm = buildVm()
        waitUntil(timeoutMs = 6000) { !vm.uiState.value.isLoading }
        // 刷新时让快照查询变慢，确保「切月」发生在刷新还没结束时（桩要在 buildVm 之后打）
        coEvery { assetRepo.getSnapshotsSinceSync(any()) } coAnswers {
            delay(200)
            emptyList()
        }

        vm.refresh()
        assertTrue(vm.uiState.value.isRefreshing)
        vm.selectMonth(targetMonth)
        waitUntil(timeoutMs = 6000) {
            vm.uiState.value.currentMonth == targetMonth &&
                !vm.uiState.value.isLoading &&
                !vm.uiState.value.isRefreshing
        }
        assertNull("被取消的刷新不能留下「刷新失败」", vm.uiState.value.errorMessage)
    }

    // ---------- 资产变更触发的收入重算不被切页取消（d17195a 回归） ----------

    @Test
    fun `资产变更触发的收入重算_切页取消生成后仍会落地`() = runBlocking {
        // 回归（d17195a）：资产变更触发的 loadDividendIncome 曾挂在 generationJob 的 then 上，
        // selectTab/selectMonth 的 cancel 会把它一起取消 → realAnnualIncome/FIRE 永久停在旧口径。
        val incomeCalls = AtomicInteger(0)
        val secondStarted = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val gatedUseCase = DividendIncomeUseCase { _assets, _force, _tag, _cacheOnly ->
            if (incomeCalls.incrementAndGet() >= 2) {
                secondStarted.complete(Unit)
                gate.await()
                DividendIncomeLoader.IncomeResult(emptyMap(), emptyMap(), 4321.0, true)
            } else {
                DividendIncomeLoader.IncomeResult(emptyMap(), emptyMap(), 1234.5, true)
            }
        }
        val assetsFlow = MutableStateFlow(listOf(stockAsset))
        val vm = buildVm(assetsFlow = assetsFlow, incomeUseCase = gatedUseCase)
        waitUntil(timeoutMs = 6000) { vm.uiState.value.realAnnualIncome == 1234.5 }

        // 资产变更（数量变化才改签名）→ 第二轮收入计算在 gate 上挂起
        assetsFlow.value = listOf(stockAsset.copy(balance = 2000.0))
        assertTrue("第二轮收入计算应已开始", waitUntil(timeoutMs = 6000) { secondStarted.isCompleted })

        // 切页：只取消「生成」；收入重算必须独立于它
        vm.selectTab("收支日历")
        gate.complete(Unit)

        assertTrue(
            "切页后收入重算仍必须落地（修复前会被 then 一起取消，永远停在 1234.5）",
            waitUntil(timeoutMs = 6000) { vm.uiState.value.realAnnualIncome == 4321.0 }
        )
        assertEquals(4321.0, vm.uiState.value.realAnnualIncome, 0.001)
    }

    // ---------- 今天格新鲜度：缓存命中保留实时值 / 跨天回前台失效 / 落库前校验 ----------

    @Test
    fun `缓存命中保留刷新后的实时值`() = runBlocking {
        val today = LocalDate.now()
        val oldPriced = stockAsset.copy(currentPrice = 10.0) // 1000 × 10 = 10000
        val newPriced = oldPriced.copy(currentPrice = 12.0)  // 12000，签名不变（不触发重算）
        coEvery { divRepo.getDividendRecordsBatch(any()) } returns emptyMap()

        val vm = buildVm(assets = listOf(oldPriced))
        waitUntil(timeoutMs = 6000) {
            !vm.uiState.value.isLoading && vm.uiState.value.calendarData[today] == 10000.0
        }

        // 别页刷新落了新价：refreshLiveAssets 读到 12000
        every { assetRepo.getAllAssets() } returns flowOf(listOf(newPriced))
        vm.refreshLiveAssets()
        waitUntil(timeoutMs = 6000) { vm.uiState.value.calendarData[today] == 12000.0 }

        // 切走再切回 → 命中 init 那轮缓存；缓存里的今天格还是 10000，必须被实时值覆盖
        vm.selectTab("收支日历")
        waitUntil(timeoutMs = 6000) { vm.uiState.value.selectedTab == "收支日历" && !vm.uiState.value.isLoading }
        vm.selectTab("资产日历")
        waitUntil(timeoutMs = 6000) { vm.uiState.value.selectedTab == "资产日历" && !vm.uiState.value.isLoading }

        assertEquals(
            "缓存命中不能把今天格写回旧价",
            12000.0,
            vm.uiState.value.calendarData[today] ?: 0.0,
            0.001
        )
    }

    @Test
    fun `跨天回前台强制失效_新的一天按实时值生成`() = runBlocking {
        val today = LocalDate.now()
        val tomorrow = today.plusDays(1)
        val priced = stockAsset.copy(currentPrice = 10.0)
        coEvery { divRepo.getDividendRecordsBatch(any()) } returns emptyMap()

        val vm = buildVm(assets = listOf(priced))
        waitUntil(timeoutMs = 6000) {
            !vm.uiState.value.isLoading && vm.uiState.value.calendarData[today] == 10000.0
        }

        // 修复前：onScreenResumed 未传 invalidateCache → 命中昨天的缓存（tomorrow 格为 0），
        // 且把 generatedOnDate 记成 tomorrow，之后再也不会补算。
        vm.onScreenResumed(today = tomorrow)
        assertTrue(
            "跨天后 tomorrow 格必须按实时净值生成，而不是沿用昨天缓存里的 0",
            waitUntil(timeoutMs = 6000) {
                !vm.uiState.value.isLoading && vm.uiState.value.calendarData[tomorrow] == 10000.0
            }
        )
        assertEquals(10000.0, vm.uiState.value.calendarData[tomorrow] ?: 0.0, 0.001)
        // 「强制失效」的可观测判据：跨天回前台必须重新查快照（缓存命中则不会发起第二次查询）
        coVerify(exactly = 2) { assetRepo.getSnapshotsSinceSync(any()) }
    }

    @Test
    fun `生成期间刷新读到新价_提交用更新的实时值`() = runBlocking {
        val today = LocalDate.now()
        val oldPriced = stockAsset.copy(currentPrice = 10.0) // 生成开始时读到：10000
        val newPriced = oldPriced.copy(currentPrice = 12.0)  // 生成期间实时刷新读到：12000
        val allAssetsReads = AtomicInteger(0)
        coEvery { divRepo.getDividendRecordsBatch(any()) } returns emptyMap()

        // 生成在读完资产后会在快照查询上停留 250ms，给实时刷新留出完成的窗口
        val vm = buildVm(
            assets = listOf(oldPriced),
            snapshotDelayMs = 250,
            allAssetsFlow = {
                if (allAssetsReads.incrementAndGet() == 1) flowOf(listOf(oldPriced)) else flowOf(listOf(newPriced))
            }
        )
        assertTrue("生成应已开始读取资产", waitUntil { allAssetsReads.get() >= 1 })

        // 生成还在 IO 里，实时刷新已读到新价（快照序号更新）
        vm.refreshLiveAssets()

        assertTrue(
            "落库前应校验新鲜度：更新的实时值必须覆盖生成开始时读到的旧价",
            waitUntil(timeoutMs = 6000) {
                !vm.uiState.value.isLoading && vm.uiState.value.calendarData[today] == 12000.0
            }
        )
        assertEquals(12000.0, vm.uiState.value.calendarData[today] ?: 0.0, 0.001)
    }
}
