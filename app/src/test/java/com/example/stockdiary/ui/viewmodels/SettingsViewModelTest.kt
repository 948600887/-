package com.example.stockdiary.ui.viewmodels

import android.content.Context
import com.example.stockdiary.data.export.DataExporter
import com.example.stockdiary.data.prefs.DashboardPreferences
import com.example.stockdiary.data.prefs.DashboardPreferencesManager
import com.example.stockdiary.data.prefs.SortOrder
import com.example.stockdiary.data.repository.AssetRepository
import com.example.stockdiary.data.sync.PriceSyncPreferencesManager
import com.example.stockdiary.data.sync.PriceSyncScheduler
import com.example.stockdiary.data.sync.SnapshotScheduler
import com.example.stockdiary.common.Result
import com.example.stockdiary.database.DividendDao
import com.example.stockdiary.database.SnapshotDao
import com.example.stockdiary.database.TransactionDao
import com.example.stockdiary.utils.AppLogger
import com.example.stockdiary.utils.DataSourceHealthChecker
import com.example.stockdiary.utils.NotificationHelper
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * SettingsViewModel 单测。
 *
 * 沿用 CalendarViewModelTest 的 Main dispatcher 处理手法：UnconfinedTestDispatcher 在
 * @BeforeClass 设一次、@AfterClass 才 reset。VM 内部 exportData/deleteAllData 走真实
 * Dispatchers.IO，虚拟时钟推不动，断言用 waitUntil 实时轮询。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

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

    private val appContext = mockk<Context>(relaxed = true)
    private val assetRepository = mockk<AssetRepository>(relaxed = true)
    private val snapshotDao = mockk<SnapshotDao>(relaxed = true)
    private val transactionDao = mockk<TransactionDao>(relaxed = true)
    private val dividendDao = mockk<DividendDao>(relaxed = true)
    private val dataExporter = mockk<DataExporter>(relaxed = true)
    private val dashboardPreferencesManager = mockk<DashboardPreferencesManager>(relaxed = true)
    private val dataSourceHealthChecker = mockk<DataSourceHealthChecker>(relaxed = true)
    private val priceSyncPreferencesManager = mockk<PriceSyncPreferencesManager>(relaxed = true)

    private lateinit var vm: SettingsViewModel

    // ---- saveDashboardPreferences 护栏用的落盘记账器（每条用例拿到全新的测试实例，天然隔离）----
    /** 模拟「真实落盘」的流水：installFakeUpdateEngine 每次 merge 追加一条 */
    private val persistedHits = CopyOnWriteArrayList<DashboardPreferences>()
    /** updatePreferences 被调用次数，供 waitUntil 判断 IO 协程是否已落地 */
    private val mergeCount = AtomicInteger(0)

    @Before
    fun setUp() {
        // 构造前必须先 mock 这些 object：VM 字段初始化会调 NotificationHelper.isReminderEnabled /
        // getReminderTime，且 deleteAllData 会取消 PriceSyncScheduler / SnapshotScheduler / 提醒。
        mockkObject(NotificationHelper)
        every { NotificationHelper.isReminderEnabled(any()) } returns false
        every { NotificationHelper.getReminderTime(any()) } returns Pair(8, 0)
        mockkObject(PriceSyncScheduler)
        mockkObject(SnapshotScheduler)
        every { PriceSyncScheduler.cancelDailySync(any()) } just Runs
        every { SnapshotScheduler.cancelDailySnapshot(any()) } just Runs
        every { NotificationHelper.cancelReminder(any()) } just Runs

        // init 里的三个 Flow 收集必须给确定性数据源，否则会挂住或落到未初始化实现上抛异常
        every { assetRepository.getAllAssets() } returns flowOf(emptyList())
        every { snapshotDao.getAllSnapshots() } returns flowOf(emptyList())
        every { transactionDao.getAllFlow() } returns flowOf(emptyList())
        every { dividendDao.getAllFlow() } returns flowOf(emptyList())
        coEvery { dividendDao.getAllSync() } returns emptyList()

        vm = SettingsViewModel(
            appContext = appContext,
            assetRepository = assetRepository,
            snapshotDao = snapshotDao,
            transactionDao = transactionDao,
            dividendDao = dividendDao,
            dataExporter = dataExporter,
            dashboardPreferencesManager = dashboardPreferencesManager,
            dataSourceHealthChecker = dataSourceHealthChecker,
            priceSyncPreferencesManager = priceSyncPreferencesManager
        )
    }

    @After
    fun tearDown() {
        io.mockk.unmockkAll()
    }

    /** 实时轮询等 IO 上的协程落地 */
    private fun waitUntil(timeoutMs: Long = 3000, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            Thread.sleep(5)
        }
        return cond()
    }

    @Test
    fun `导出分红明细走 exportDividendsToCSV 分支`() = runBlocking {
        val fakeUri = mockk<android.net.Uri>(relaxed = true)
        every { dataExporter.exportDividendsToCSV(any()) } returns Result.success(fakeUri)

        vm.exportData(mockk(relaxed = true), SettingsViewModel.ExportType.CSV_DIVIDENDS)
        waitUntil { vm.uiState.value.exportResult != null }

        coVerify { dividendDao.getAllSync() }
        verify { dataExporter.exportDividendsToCSV(any()) }
        assertTrue(vm.uiState.value.exportResult?.isSuccess == true)
    }

    @Test
    fun `导出资产报告走 exportToText 分支`() = runBlocking {
        val fakeUri = mockk<android.net.Uri>(relaxed = true)
        every { dataExporter.exportToText(any()) } returns Result.success(fakeUri)

        vm.exportData(mockk(relaxed = true), SettingsViewModel.ExportType.TEXT)
        waitUntil { vm.uiState.value.exportResult != null }

        verify { dataExporter.exportToText(any()) }
        assertTrue(vm.uiState.value.exportResult?.isSuccess == true)
    }

    @Test
    fun `deleteAllData 清空数据库并取消价格同步与每日快照与提醒`() = runBlocking {
        coEvery { assetRepository.deleteAllData() } just Runs

        vm.deleteAllData()
        waitUntil { vm.uiState.value.deleteResult != null }

        // 数据库层必须被清
        coVerify { assetRepository.deleteAllData() }
        // 三个后台任务必须被取消（这是此前漏掉的快照取消 + 价格同步 + 提醒）
        verify { PriceSyncScheduler.cancelDailySync(any()) }
        verify { SnapshotScheduler.cancelDailySnapshot(any()) }
        verify { NotificationHelper.cancelReminder(any()) }
        // 终态应为成功
        assertTrue(vm.uiState.value.deleteResult?.isSuccess == true)
    }

    @Test
    fun `openAlarmSettings 转发到 NotificationHelper`() = runBlocking {
        vm.openAlarmSettings()
        verify { NotificationHelper.openAlarmSettings(appContext) }
    }

    @Test
    fun `clearReminderError 清空错误状态且不抛异常`() = runBlocking {
        vm.clearReminderError()
        assertNull(vm.uiState.value.reminderError)
    }

    // ==================== saveDashboardPreferences：原子读-改-写护栏 ====================
    //
    // 历史事故：页面拿**构造期**加载的过期内存副本整块 `savePreferences(...)` 回写，
    // 把别处（日历 FIRE 的月度生活费、仪表盘/持仓的年度分红缓存、分组展开态）刚改的
    // 字段冲回旧值，用户表现为「改了但不保存」。下面这组用例钉住「只有 sheet 负责的
    // 18 个字段跟随入参，其余字段必须以磁盘最新值为准」。

    /**
     * 磁盘最新值：sheet 负责的 18 个字段全部取 **[DashboardPreferences] 默认值**，
     * 只有 sheet 不负责的四个字段设成非默认值，用于验证它们不被入参覆盖。
     *
     * 默认值序列与下面 [staleSnapshot] 的取值**逐字相反**（含 lowValueThresholdCny /
     * sortOrder / defaultPeriod 三个非布尔字段），因此任何「漏 copy 一个字段」或
     * 「误写成 fresh.xxx」都会被用例抓住——合并后每个字段的取值都能唯一指出来源。
     */
    private val diskLatest = DashboardPreferences(
        monthlyExpense = "8888",
        annualDividendCache = 1234.5,
        annualDividendSymbols = setOf("SH600519"),
        defaultExpanded = setOf("股票")
    )

    /**
     * 构造期内存副本（sheet 提交上来的参数）：四个非 sheet 字段停留在**构造期旧值**，
     * 而 18 个 sheet 字段相对默认值全部翻转。它同时扮演「用户刚在 sheet 里改完的配置」。
     */
    private val staleSnapshot = DashboardPreferences(
        showTotalAssets = true,
        showNetAssets = false,
        showProfit = false,
        showDistribution = false,
        showAssetGrowth = false,
        showDividendProgress = false,
        showNextPayout = false,
        showMonthlyCashFlow = false,
        showBankDeposits = false,
        showStocks = false,
        showFunds = false,
        showCrypto = false,
        showLiabilities = false,
        hideLowValueCrypto = true,
        lowValueThresholdCny = "999",
        hideUnknownPriceCrypto = true,
        sortOrder = SortOrder.BY_VALUE_DESC,
        defaultPeriod = "ALL",
        // ↓ 这四个是构造期快照，必须被磁盘最新值盖掉
        monthlyExpense = "5000",
        annualDividendCache = 0.0,
        annualDividendSymbols = emptySet(),
        defaultExpanded = emptySet()
    )

    /**
     * 给 [DashboardPreferencesManager] 装上假引擎，模拟 Manager 的真实契约：
     * 拉磁盘最新值 → 套用 mutate → 记录落盘对象 → 返回合并结果。
     *
     * 必须这么打桩：prefsMgr 是 relaxed mock，其 `updatePreferences` 是桩、真实方法体不会执行，
     * 内部的 load/save 都不会发生，缺省返回值还是 MockK 造的假对象。不打桩的话
     * 「断言落盘对象」这类断言全部读到假数据，护栏等于没写。
     *
     * 刻意**不**在 answer 里调 `savePreferences`：[persistedHits] 就是落盘流水，
     * 这样 [saveDashboardPreferences 走 updatePreferences 原子入口而不是整块回写]
     * 才能干净地断言 `savePreferences` 一次都没被调到。
     */
    private fun installFakeUpdateEngine(disk: DashboardPreferences) {
        every { dashboardPreferencesManager.loadPreferences() } returns disk
        every { dashboardPreferencesManager.updatePreferences(any()) } answers {
            val mutate = firstArg<(DashboardPreferences) -> DashboardPreferences>()
            val merged = mutate(dashboardPreferencesManager.loadPreferences())
            persistedHits.add(merged)
            mergeCount.incrementAndGet()
            merged
        }
    }

    /**
     * 在上面这层记账之后**重新构造**一个 VM。
     *
     * 必须重新构造：[dashboardPreferencesManager] 的 `loadPreferences()` 要在 VM 构造前
     * 打桩，VM 字段初始化时读到的才是指定的磁盘值——`@Before` 里那个 [vm] 是在打桩之前
     * 构造的，`_dashboardPreferences` 初值是 MockK 的缺省返回值，拿它当「合并前基线」
     * 断言会误判（第一轮跑就是这样栽的）。只关心「落盘了什么」的用例用 [vm] 即可。
     */
    private fun buildVmWithPrefs(disk: DashboardPreferences): SettingsViewModel {
        every { dashboardPreferencesManager.loadPreferences() } returns disk
        return SettingsViewModel(
            appContext = appContext,
            assetRepository = assetRepository,
            snapshotDao = snapshotDao,
            transactionDao = transactionDao,
            dividendDao = dividendDao,
            dataExporter = dataExporter,
            dashboardPreferencesManager = dashboardPreferencesManager,
            dataSourceHealthChecker = dataSourceHealthChecker,
            priceSyncPreferencesManager = priceSyncPreferencesManager
        )
    }

    /** 断言 sheet 负责的 18 个字段与入参一致 */
    private fun assertSheetFieldsFrom(expected: DashboardPreferences, actual: DashboardPreferences) {
        assertEquals("showTotalAssets", expected.showTotalAssets, actual.showTotalAssets)
        assertEquals("showNetAssets", expected.showNetAssets, actual.showNetAssets)
        assertEquals("showProfit", expected.showProfit, actual.showProfit)
        assertEquals("showDistribution", expected.showDistribution, actual.showDistribution)
        assertEquals("showAssetGrowth", expected.showAssetGrowth, actual.showAssetGrowth)
        assertEquals("showDividendProgress", expected.showDividendProgress, actual.showDividendProgress)
        assertEquals("showNextPayout", expected.showNextPayout, actual.showNextPayout)
        assertEquals("showMonthlyCashFlow", expected.showMonthlyCashFlow, actual.showMonthlyCashFlow)
        assertEquals("showBankDeposits", expected.showBankDeposits, actual.showBankDeposits)
        assertEquals("showStocks", expected.showStocks, actual.showStocks)
        assertEquals("showFunds", expected.showFunds, actual.showFunds)
        assertEquals("showCrypto", expected.showCrypto, actual.showCrypto)
        assertEquals("showLiabilities", expected.showLiabilities, actual.showLiabilities)
        assertEquals("hideLowValueCrypto", expected.hideLowValueCrypto, actual.hideLowValueCrypto)
        assertEquals("lowValueThresholdCny", expected.lowValueThresholdCny, actual.lowValueThresholdCny)
        assertEquals("hideUnknownPriceCrypto", expected.hideUnknownPriceCrypto, actual.hideUnknownPriceCrypto)
        assertEquals("sortOrder", expected.sortOrder, actual.sortOrder)
        assertEquals("defaultPeriod", expected.defaultPeriod, actual.defaultPeriod)
    }

    /** 断言 sheet **不负责**的四个字段保持磁盘最新值 */
    private fun assertNonSheetFieldsFrom(expected: DashboardPreferences, actual: DashboardPreferences) {
        assertEquals("monthlyExpense", expected.monthlyExpense, actual.monthlyExpense)
        assertEquals("annualDividendCache", expected.annualDividendCache, actual.annualDividendCache, 0.0)
        assertEquals("annualDividendSymbols", expected.annualDividendSymbols, actual.annualDividendSymbols)
        assertEquals("defaultExpanded", expected.defaultExpanded, actual.defaultExpanded)
    }

    @Test
    fun `saveDashboardPreferences 保留磁盘最新的月度生活费与分红缓存与展开态`() = runBlocking {
        installFakeUpdateEngine(diskLatest)

        vm.saveDashboardPreferences(staleSnapshot)
        waitUntil { mergeCount.get() > 0 }

        val merged = persistedHits.single()
        // 核心护栏：这四个字段不是 DashboardConfigSheet 的职责，必须以磁盘最新值为准。
        // 若退化成「整块回写过期副本」，这里会被冲回 "5000" / 0.0 / 空集 / 空集。
        assertNonSheetFieldsFrom(diskLatest, merged)
        assertFalse("月度生活费被过期的构造期旧值冲回去了", merged.monthlyExpense == "5000")
        // 反向确认：sheet 负责的字段确实取自入参，不是原样透传磁盘值
        assertSheetFieldsFrom(staleSnapshot, merged)
    }

    @Test
    fun `saveDashboardPreferences 把 sheet 负责的 18 个字段全部套用`() = runBlocking {
        installFakeUpdateEngine(diskLatest)

        vm.saveDashboardPreferences(staleSnapshot)
        waitUntil { mergeCount.get() > 0 }

        val merged = persistedHits.single()
        assertSheetFieldsFrom(staleSnapshot, merged)
        // 非布尔字段也要跟入参走，只跟错的别名应是「入参优先、磁盘让位」
        assertEquals("999", merged.lowValueThresholdCny)
        assertEquals(SortOrder.BY_VALUE_DESC, merged.sortOrder)
        assertEquals("ALL", merged.defaultPeriod)
        // 同时非 sheet 字段仍未被带偏
        assertNonSheetFieldsFrom(diskLatest, merged)
    }

    @Test
    fun `saveDashboardPreferences 把合并结果写回 dashboardPreferences 流`() = runBlocking {
        installFakeUpdateEngine(diskLatest)
        val vm = buildVmWithPrefs(diskLatest)
        // 前置：初值来自磁盘；且入参与磁盘不同，否则「合并结果 == 原值」看不出是否真更新
        assertEquals(diskLatest, vm.dashboardPreferences.value)
        assertTrue(vm.dashboardPreferences.value != staleSnapshot)

        vm.saveDashboardPreferences(staleSnapshot)
        waitUntil { mergeCount.get() > 0 }
        waitUntil { vm.dashboardPreferences.value == persistedHits.firstOrNull() }

        val merged = persistedHits.single()
        assertEquals("落盘对象与对外暴露的流必须是同一个合并结果", merged, vm.dashboardPreferences.value)
        assertSheetFieldsFrom(staleSnapshot, vm.dashboardPreferences.value)
        assertNonSheetFieldsFrom(diskLatest, vm.dashboardPreferences.value)
    }

    @Test
    fun `saveDashboardPreferences 走 updatePreferences 原子入口而不是整块回写`() = runBlocking {
        installFakeUpdateEngine(diskLatest)

        vm.saveDashboardPreferences(staleSnapshot)
        waitUntil { mergeCount.get() > 0 }

        verify(exactly = 1) { dashboardPreferencesManager.updatePreferences(any()) }
        // 一旦有人改回 `savePreferences(preferences)` 整块回写，这条立刻变红
        verify(exactly = 0) { dashboardPreferencesManager.savePreferences(any()) }
    }

    @Test
    fun `saveDashboardPreferences 写入失败时吞掉异常且后续写入仍可用`() = runBlocking {
        // VM 必须在 loadPreferences 打桩之后构造，且要在下面的 updatePreferences 抛桩之前
        val vm = buildVmWithPrefs(diskLatest)
        // AppLogger 真身会调 android.util.Log，单测里是 "not mocked"，必须替掉
        mockkObject(AppLogger)
        every { AppLogger.e(any(), any(), any()) } just Runs
        every { dashboardPreferencesManager.updatePreferences(any()) } throws RuntimeException("磁盘写入失败")

        vm.saveDashboardPreferences(staleSnapshot)
        // 调用本身不得抛出；异常应落入 VM 的 catch 并记日志
        verify(timeout = 3000) { AppLogger.e(any(), any(), any()) }
        // 没拿到合并结果时内存流停在磁盘初值，不能被 MockK 假对象污染
        assertEquals(diskLatest, vm.dashboardPreferences.value)

        // 关键：异常若冒泡到 viewModelScope，整个作用域会被取消，后续写入再也不执行。
        // 这里再成功写一次，证明 catch 确实把异常吃干净了。
        installFakeUpdateEngine(diskLatest)
        vm.saveDashboardPreferences(staleSnapshot)
        waitUntil { mergeCount.get() > 0 }

        val merged = persistedHits.single()
        assertSheetFieldsFrom(staleSnapshot, merged)
        assertNonSheetFieldsFrom(diskLatest, merged)
    }

    // ==================== refreshPriceSyncState：整组重读磁盘快照 ====================

    @Test
    fun `refreshPriceSyncState 把价格同步开关与上次同步时间写进 uiState`() = runBlocking {
        // 前置：构造期读到的旧快照（relaxed mock 默认 false / 0L），与下面的磁盘真值不同
        assertFalse(vm.uiState.value.autoPriceSyncEnabled)
        assertEquals(0L, vm.uiState.value.lastPriceSyncTime)

        every { priceSyncPreferencesManager.isAutoSyncEnabled() } returns true
        every { priceSyncPreferencesManager.getLastSyncTimeMillis() } returns 1_700_000_000_000L
        every { priceSyncPreferencesManager.isStockIntradaySyncEnabled() } returns true
        every { priceSyncPreferencesManager.isCryptoAutoSyncEnabled() } returns true

        vm.refreshPriceSyncState()
        waitUntil { vm.uiState.value.autoPriceSyncEnabled }

        assertTrue(vm.uiState.value.autoPriceSyncEnabled)
        assertEquals(1_700_000_000_000L, vm.uiState.value.lastPriceSyncTime)
        assertTrue(vm.uiState.value.stockIntradaySyncEnabled)
        assertTrue(vm.uiState.value.cryptoAutoSyncEnabled)
    }

    @Test
    fun `refreshPriceSyncState 把每日提醒开关与时间写进 uiState`() = runBlocking {
        // 构造期桩值是 false / 8:00，磁盘真值改成 true / 21:30
        every { NotificationHelper.isReminderEnabled(any()) } returns true
        every { NotificationHelper.getReminderTime(any()) } returns Pair(21, 30)

        vm.refreshPriceSyncState()
        waitUntil { vm.uiState.value.reminderEnabled }

        assertTrue(vm.uiState.value.reminderEnabled)
        assertEquals(21, vm.uiState.value.reminderHour)
        assertEquals(30, vm.uiState.value.reminderMinute)
        assertNull(vm.uiState.value.reminderError)
    }
}
