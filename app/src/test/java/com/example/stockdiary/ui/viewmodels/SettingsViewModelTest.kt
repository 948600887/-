package com.example.stockdiary.ui.viewmodels

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.example.stockdiary.data.backup.BackupCodec
import com.example.stockdiary.data.backup.BackupManager
import com.example.stockdiary.data.backup.BackupPayload
import com.example.stockdiary.data.export.DataExporter
import com.example.stockdiary.data.prefs.DashboardPreferences
import com.example.stockdiary.data.prefs.DashboardPreferencesManager
import com.example.stockdiary.data.prefs.SortOrder
import com.example.stockdiary.data.repository.AssetRepository
import com.example.stockdiary.data.price.PriceCacheMaintenance
import com.example.stockdiary.data.prefs.SnapshotManualAdjustmentStore
import com.example.stockdiary.data.sync.AssetSyncFailureStore
import com.example.stockdiary.data.sync.PriceSyncPreferencesManager
import com.example.stockdiary.data.sync.PriceSyncScheduler
import com.example.stockdiary.data.sync.SnapshotScheduler
import com.example.stockdiary.common.Result
import com.example.stockdiary.data.repository.DividendRepository
import com.example.stockdiary.data.repository.TransactionRepository
import com.example.stockdiary.utils.AppLogger
import com.example.stockdiary.utils.DataSourceHealth
import com.example.stockdiary.utils.DataSourceHealthChecker
import com.example.stockdiary.utils.ExchangeRateManager
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    private val dividendRepository = mockk<DividendRepository>(relaxed = true)
    private val transactionRepository = mockk<TransactionRepository>(relaxed = true)
    private val dataExporter = mockk<DataExporter>(relaxed = true)
    private val dashboardPreferencesManager = mockk<DashboardPreferencesManager>(relaxed = true)
    private val dataSourceHealthChecker = mockk<DataSourceHealthChecker>(relaxed = true)
    private val priceSyncPreferencesManager = mockk<PriceSyncPreferencesManager>(relaxed = true)
    private val priceCacheMaintenance = mockk<PriceCacheMaintenance>(relaxed = true)
    // 备份/恢复在本文件不测（由 BackupCodecTest 覆盖数据层契约），这里只需让 VM 能构造
    private val backupManager = mockk<BackupManager>(relaxed = true)

    /** Manager 持有的全应用唯一偏好流；VM 直接暴露它，不再自建副本 */
    private lateinit var prefsFlow: MutableStateFlow<DashboardPreferences>

    private lateinit var vm: SettingsViewModel

    // ---- saveDashboardPreferences 护栏用的落盘记账器（每条用例拿到全新的测试实例，天然隔离）----
    /** 模拟「真实落盘」的流水：installFakeUpdateEngine 每次 merge 追加一条 */
    private val persistedHits = CopyOnWriteArrayList<DashboardPreferences>()
    /** updatePreferences 被调用次数，供 waitUntil 判断 IO 协程是否已落地 */
    private val mergeCount = AtomicInteger(0)

    @Before
    fun setUp() {
        // 构造前必须先 mock 这些 object：deleteAllData 会取消 PriceSyncScheduler / SnapshotScheduler。
        mockkObject(PriceSyncScheduler)
        mockkObject(SnapshotScheduler)
        every { PriceSyncScheduler.cancelDailySync(any()) } just Runs
        every { SnapshotScheduler.cancelDailySnapshot(any()) } just Runs

        // init 里的三个 Flow 收集必须给确定性数据源，否则会挂住或落到未初始化实现上抛异常
        every { assetRepository.getAllAssets() } returns flowOf(emptyList())
        every { assetRepository.getAllSnapshots() } returns flowOf(emptyList())
        every { transactionRepository.getAllFlow() } returns flowOf(emptyList())
        every { dividendRepository.getAllDividendsFlow() } returns flowOf(emptyList())
        coEvery { dividendRepository.getAllDividendsSync() } returns emptyList()

        prefsFlow = MutableStateFlow(DashboardPreferences())
        every { dashboardPreferencesManager.preferences } returns prefsFlow

        vm = SettingsViewModel(
            appContext = appContext,
            assetRepository = assetRepository,
            dividendRepository = dividendRepository,
            transactionRepository = transactionRepository,
            dataExporter = dataExporter,
            dashboardPreferencesManager = dashboardPreferencesManager,
            dataSourceHealthChecker = dataSourceHealthChecker,
            priceSyncPreferencesManager = priceSyncPreferencesManager,
            priceCacheMaintenance = priceCacheMaintenance,
            backupManager = backupManager
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
        every { dataExporter.exportDividendsToCSV(any(), any()) } returns Result.success(fakeUri)

        vm.exportData(mockk(relaxed = true), SettingsViewModel.ExportType.CSV_DIVIDENDS)
        waitUntil { vm.uiState.value.exportResult != null }

        coVerify { dividendRepository.getAllDividendsSync() }
        verify { dataExporter.exportDividendsToCSV(any(), any()) }
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
    fun `deleteAllData 清空数据库并取消价格同步与每日快照`() = runBlocking {
        coEvery { assetRepository.deleteAllData() } just Runs

        vm.deleteAllData()
        waitUntil { vm.uiState.value.deleteResult != null }

        // 数据库层必须被清
        coVerify { assetRepository.deleteAllData() }
        // 派生缓存必须走数据层门面清理（设置页不再直接认识各数据源单例）
        coVerify { priceCacheMaintenance.clearDerivedCaches() }
        // 两个后台任务必须被取消（这是此前漏掉的快照取消 + 价格同步）
        verify { PriceSyncScheduler.cancelDailySync(any()) }
        verify { SnapshotScheduler.cancelDailySnapshot(any()) }
        // 终态应为成功
        assertTrue(vm.uiState.value.deleteResult?.isSuccess == true)
    }

    @Test
    fun `体检失败时清空旧摘要_避免与失败提示自相矛盾`() = runBlocking {
        coEvery { dataSourceHealthChecker.checkAllHealth() } throws RuntimeException("offline")

        vm.checkDataSourceHealth()
        waitUntil { vm.uiState.value.errorMessage != null }

        assertEquals("上次体检失败，点击重试", vm.uiState.value.dataSourceHealthSummary)
        assertNull(vm.uiState.value.dataSourceHealth)
        assertEquals("检查数据源状态失败", vm.uiState.value.errorMessage)
    }

    @Test
    fun `体检摘要按资产域分组统计`() = runBlocking {
        coEvery { dataSourceHealthChecker.checkAllHealth() } returns listOf(
            DataSourceHealth("新浪财经", "hq.sinajs.cn", true, 10, 1L, category = "股票"),
            DataSourceHealth("天天基金", "fundmobapi", false, 20, 1L, "HTTP 500", category = "基金"),
            DataSourceHealth("Binance", "binance", false, 30, 1L, "timeout", category = "加密")
        )

        vm.checkDataSourceHealth()
        waitUntil { vm.uiState.value.dataSourceHealthSummary != null }

        assertEquals(
            "股票 1/1 · 基金 0/1 · 加密 0/1（部分源降级）",
            vm.uiState.value.dataSourceHealthSummary
        )
    }

    @Test
    fun `汇率刷新返回失败时给出可见提示`() = runBlocking {
        mockkObject(ExchangeRateManager)
        coEvery { ExchangeRateManager.refresh(any()) } returns false

        vm.refreshExchangeRate()
        waitUntil { vm.uiState.value.errorMessage != null }

        assertFalse(vm.uiState.value.isRefreshingRate)
        assertEquals("汇率刷新失败，仍沿用当前汇率（网络不通或汇率源异常）", vm.uiState.value.errorMessage)
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
     * 拉磁盘最新值 → 套用 mutate → 记录落盘对象 → 同步唯一偏好流 → 返回合并结果。
     *
     * 必须这么打桩：prefsMgr 是 relaxed mock，其 `updatePreferences` 是桩、真实方法体不会执行，
     * 内部的 load/save 与唯一流更新都不会发生，缺省返回值还是 MockK 造的假对象。
     * 不打桩的话「断言落盘对象 / 对外流」这类断言全部读到假数据，护栏等于没写。
     */
    private fun installFakeUpdateEngine(disk: DashboardPreferences) {
        every { dashboardPreferencesManager.loadPreferences() } returns disk
        prefsFlow.value = disk
        every { dashboardPreferencesManager.updatePreferences(any()) } answers {
            val mutate = firstArg<(DashboardPreferences) -> DashboardPreferences>()
            val merged = mutate(dashboardPreferencesManager.loadPreferences())
            persistedHits.add(merged)
            mergeCount.incrementAndGet()
            prefsFlow.value = merged
            merged
        }
    }

    /**
     * 在上面这层记账之后**重新构造**一个 VM。
     *
     * 必须重新构造：[dashboardPreferencesManager] 的 `loadPreferences()` 要在 VM 构造前
     * 打桩，并把唯一偏好流置成指定的磁盘值，VM 暴露的 `dashboardPreferences` 才以它为准。
     * 只关心「落盘了什么」的用例用 [vm] 即可。
     */
    private fun buildVmWithPrefs(disk: DashboardPreferences): SettingsViewModel {
        every { dashboardPreferencesManager.loadPreferences() } returns disk
        prefsFlow.value = disk
        return SettingsViewModel(
            appContext = appContext,
            assetRepository = assetRepository,
            dividendRepository = dividendRepository,
            transactionRepository = transactionRepository,
            dataExporter = dataExporter,
            dashboardPreferencesManager = dashboardPreferencesManager,
            dataSourceHealthChecker = dataSourceHealthChecker,
            priceSyncPreferencesManager = priceSyncPreferencesManager,
            priceCacheMaintenance = priceCacheMaintenance,
            backupManager = backupManager
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
        // 整块回写入口 savePreferences 已在生产代码私有化，再用陈旧副本回写在编译期就不成立
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

    // ==================== 全量备份 / 恢复：VM 状态机（QA B1/B2/B3/B4/B6 回归） ====================
    //
    // 这一组专门补 QA 指出的「UI/VM 层零测试」空白。Android 框架类在 JVM 单测里用
    // MockK 构造：Uri = mockk<Uri>(relaxed)，ContentResolver 自己持一份 relaxed mock 并
    // 让 appContext.contentResolver 返回它，openInputStream 返回纯 JDK 的 ByteArrayInputStream。
    // VM 内部 viewModelScope.launch(Dispatchers.IO) 走真实 IO 线程，runTest 的虚拟时钟推不动，
    // 断言沿用本文件既有约定：waitUntil 实时轮询等状态落地。

    private val contentResolver = mockk<ContentResolver>(relaxed = true)

    /** 构造一份能被 decode 全量校验通过的合法备份 JSON。 */
    private fun backupJson(exportedAt: Long, assetCount: Int = 1): String {
        val assets = (1..assetCount).map { i ->
            com.example.stockdiary.database.Asset(
                id = i.toLong(),
                name = "资产$i",
                type = com.example.stockdiary.database.AssetType.STOCK,
                account = "默认账户"
            )
        }
        return BackupCodec.encode(
            BackupPayload(formatVersion = 1, dbVersion = 18, exportedAt = exportedAt, assets = assets)
        )
    }

    /** 让 appContext 走我们能控制的 ContentResolver；每次调用返回一份新的流。 */
    private fun stubInputStream(uri: Uri, jsonProvider: (Int) -> String) {
        val reads = AtomicInteger(0)
        every { appContext.contentResolver } returns contentResolver
        every { contentResolver.openInputStream(uri) } answers {
            ByteArrayInputStream(jsonProvider(reads.getAndIncrement()).toByteArray(Charsets.UTF_8))
        }
    }

    // 不 mock AppLogger：单测配置已让 android.util.Log 退化为空操作，且 queueLog
    // 自带 try/catch 兜底，本文件既有用例（如 deleteAllData）也一直是直接跑真身。
    // 避免为 w(String,String) / w(String,Throwable,String) 两个重载打桩时引入歧义。

    @Test
    fun `readBackup 成功时原子设置含Uri与摘要的pendingRestore`() = runTest {

        val uri = mockk<Uri>(relaxed = true)
        stubInputStream(uri) { backupJson(1_700_000_000_000L, assetCount = 2) }

        vm.readBackup(uri)
        assertTrue("readBackup 应设置 pendingRestore", waitUntil { vm.uiState.value.pendingRestore != null })

        val pending = vm.uiState.value.pendingRestore
        assertNotNull(pending)
        assertEquals("Uri 必须与摘要同在 ViewModel 里，不能只留摘要", uri, pending?.uri)
        assertEquals(1, pending?.summary?.formatVersion)
        assertEquals(18, pending?.summary?.dbVersion)
        assertEquals(1_700_000_000_000L, pending?.summary?.exportedAt)
        assertEquals(2, pending?.summary?.assetCount)
        assertEquals(0, pending?.summary?.snapshotCount)
        assertFalse(vm.uiState.value.isBackingUp)
    }

    @Test
    fun `readBackup 缺数据表时拒绝_不进入确认框且零写库`() = runTest {

        val uri = mockk<Uri>(relaxed = true)
        val root = org.json.JSONObject(backupJson(1_700_000_000_000L)).apply { remove("transactions") }
        stubInputStream(uri) { root.toString() }

        vm.readBackup(uri)
        assertTrue(waitUntil { vm.uiState.value.backupResult?.isError == true })

        // 缺表文件不应产生待确认恢复，更不应触达 BackupManager（库内数据不变）
        assertNull(vm.uiState.value.pendingRestore)
        val message = vm.uiState.value.backupResult?.exceptionOrNull()?.message.orEmpty()
        assertTrue("应指名缺的表，实际：$message", message.contains("交易记录"))
        coVerify(exactly = 0) { backupManager.restore(any()) }
    }

    @Test
    fun `readBackup 坏文件失败并清空pendingRestore且给出可读错误`() = runTest {

        val uri = mockk<Uri>(relaxed = true)
        stubInputStream(uri) { "这不是 JSON" }

        vm.readBackup(uri)
        assertTrue(waitUntil { vm.uiState.value.backupResult != null })

        assertNull(vm.uiState.value.pendingRestore)
        assertFalse(vm.uiState.value.isBackingUp)
        assertTrue(vm.uiState.value.backupResult?.isError == true)
        // readableBackupError 对自定义 InvalidBackupException 原样透传其自身中文消息
        // （"备份文件不是合法的 JSON…"），不套英文堆栈，也不静默
        val message = vm.uiState.value.backupResult?.exceptionOrNull()?.message.orEmpty()
        assertTrue("应给出可读的中文错误，实际：$message", message.contains("备份"))
        assertFalse(message.contains("JSONException"))
    }

    @Test
    fun `restoreBackup 二次读流成功恢复并清空pendingRestore`() = runTest {

        val uri = mockk<Uri>(relaxed = true)
        val json = backupJson(1_700_000_000_000L)
        val reads = AtomicInteger(0)
        every { appContext.contentResolver } returns contentResolver
        every { contentResolver.openInputStream(uri) } answers {
            reads.incrementAndGet()
            ByteArrayInputStream(json.toByteArray(Charsets.UTF_8))
        }
        coEvery { backupManager.restore(any()) } just Runs

        vm.readBackup(uri)
        assertTrue(waitUntil { vm.uiState.value.pendingRestore != null })
        vm.restoreBackup()
        assertTrue(waitUntil { vm.uiState.value.backupResult != null })

        assertEquals("必须二次读流，不信任缓存摘要", 2, reads.get())
        coVerify(exactly = 1) { backupManager.restore(any()) }
        assertNull(vm.uiState.value.pendingRestore)
        assertTrue(vm.uiState.value.backupResult?.isSuccess == true)
        assertFalse(vm.uiState.value.isBackingUp)
    }

    @Test
    fun `L1 恢复结束后配对释放持久Uri读授权`() = runTest {
        val uri = mockk<Uri>(relaxed = true)
        val json = backupJson(1_700_000_000_000L)
        every { appContext.contentResolver } returns contentResolver
        every { contentResolver.openInputStream(uri) } answers {
            ByteArrayInputStream(json.toByteArray(Charsets.UTF_8))
        }
        coEvery { backupManager.restore(any()) } just Runs

        vm.readBackup(uri)
        assertTrue(waitUntil { vm.uiState.value.pendingRestore != null })
        vm.restoreBackup()
        assertTrue(waitUntil { vm.uiState.value.backupResult != null })

        assertTrue(vm.uiState.value.backupResult?.isSuccess == true)
        // 成功恢复后必须配对释放 take 时拿到的持久读授权（L1）
        verify(exactly = 1) {
            contentResolver.releasePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    @Test
    fun `L1 取消恢复时配对释放持久Uri读授权`() = runTest {
        val uri = mockk<Uri>(relaxed = true)
        stubInputStream(uri) { backupJson(1_700_000_000_000L) }

        vm.readBackup(uri)
        assertTrue(waitUntil { vm.uiState.value.pendingRestore != null })
        vm.clearPendingRestore() // 用户点「取消」

        verify(exactly = 1) {
            contentResolver.releasePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    @Test
    fun `restoreBackup 二次摘要不一致时中止且零写库`() = runTest {

        val uri = mockk<Uri>(relaxed = true)
        // 第一次（只读摘要）是一份文件，第二次（确认后 decode）已被换成另一份
        stubInputStream(uri) { n -> if (n == 0) backupJson(111L) else backupJson(222L) }

        vm.readBackup(uri)
        assertTrue(waitUntil { vm.uiState.value.pendingRestore != null })
        vm.restoreBackup()
        assertTrue(waitUntil { vm.uiState.value.backupResult != null })

        coVerify(exactly = 0) { backupManager.restore(any()) }
        coVerify(exactly = 0) { priceCacheMaintenance.clearDerivedCaches() }
        assertNull(vm.uiState.value.pendingRestore)
        assertTrue(vm.uiState.value.backupResult?.isError == true)
        assertTrue(
            "必须给出中文提示而不是静默",
            vm.uiState.value.backupResult?.exceptionOrNull()?.message
                ?.contains("备份文件在确认期间发生变化") == true
        )
    }

    @Test
    fun `clearPendingRestore 取消后清空且restoreBackup不再写库`() = runTest {

        val uri = mockk<Uri>(relaxed = true)
        stubInputStream(uri) { backupJson(333L) }

        vm.readBackup(uri)
        assertTrue(waitUntil { vm.uiState.value.pendingRestore != null })
        assertNotNull(vm.uiState.value.pendingRestore)

        vm.clearPendingRestore()
        assertNull(vm.uiState.value.pendingRestore)

        // 取消后再误触恢复（例如旋转后残留的点击）：不得写库，且必须给明确提示
        vm.restoreBackup()
        coVerify(exactly = 0) { backupManager.restore(any()) }
        assertTrue(vm.uiState.value.backupResult?.isError == true)
        assertTrue(
            vm.uiState.value.backupResult?.exceptionOrNull()?.message
                ?.contains("请重新选择备份文件") == true
        )
    }

    @Test
    fun `B1 清派生缓存严格在restore之前_其余六项复位在restore之后`() = runTest {

        val uri = mockk<Uri>(relaxed = true)
        stubInputStream(uri) { backupJson(444L) }
        val order = mutableListOf<String>()

        coEvery { priceCacheMaintenance.clearDerivedCaches() } answers { order.add("clearDerived"); Unit }
        coEvery { backupManager.restore(any()) } answers { order.add("restore"); Unit }
        every { dashboardPreferencesManager.invalidateAnnualDividendIncome() } answers {
            order.add("invalidate"); DashboardPreferences()
        }
        every { dividendRepository.clearCache() } answers { order.add("clearCache"); Unit }
        every { dividendRepository.clearFetchState() } answers { order.add("clearFetchState"); Unit }

        val adjustments = mockk<SnapshotManualAdjustmentStore>(relaxed = true)
        mockkObject(SnapshotManualAdjustmentStore)
        every { SnapshotManualAdjustmentStore.getInstance(any()) } returns adjustments
        every { adjustments.clearAll() } answers { order.add("clearAll"); Unit }

        mockkObject(ExchangeRateManager)
        every { ExchangeRateManager.resetToDefaultRates() } answers { order.add("rates"); Unit }

        val failures = mockk<AssetSyncFailureStore>(relaxed = true)
        mockkObject(AssetSyncFailureStore)
        every { AssetSyncFailureStore.getInstance(any()) } returns failures
        every { failures.clear() } answers { order.add("failureStore"); Unit }

        vm.readBackup(uri)
        assertTrue(waitUntil { vm.uiState.value.pendingRestore != null })
        vm.restoreBackup()
        assertTrue(waitUntil { vm.uiState.value.backupResult != null })

        // 6+1 项复位：前置 1 项（清旧库派生缓存）+ 后置 6 项
        assertEquals(
            listOf(
                "clearDerived", "restore",
                "invalidate", "clearCache", "clearFetchState", "clearAll", "rates", "failureStore"
            ),
            order
        )
        assertTrue(
            "B1：clearDerivedCaches 必须在 backupManager.restore 之前，否则会删掉刚写回的第 7 张表",
            order.indexOf("clearDerived") < order.indexOf("restore")
        )
        assertFalse(
            "B1：restore 之后不得再出现 clearDerivedCaches",
            order.drop(order.indexOf("restore") + 1).contains("clearDerived")
        )
        coVerify(exactly = 1) { priceCacheMaintenance.clearDerivedCaches() }
        assertTrue(vm.uiState.value.backupResult?.isSuccess == true)
    }

    /**
     * 7 张表各放 1 行的可识别载荷（每表带哨兵值），用于「真实写出的字节」与「传给
     * BackupManager 的实参」断言——空表 payload 无法暴露「写成 {} 或半份数据」的假绿。
     */
    private fun sentinelPayload(exportedAt: Long = 1_700_000_000_777L): BackupPayload {
        val asset = com.example.stockdiary.database.Asset(
            id = 1L,
            name = "哨兵资产",
            type = com.example.stockdiary.database.AssetType.STOCK,
            account = "哨兵账户",
            symbol = "sh600519"
        )
        val snapshot = com.example.stockdiary.database.AssetSnapshot(
            id = 10L, timestamp = exportedAt, date = "2025-11-14",
            totalAssets = 1.0, totalLiabilities = 2.0, netAssets = -1.0
        )
        val detail = com.example.stockdiary.database.AssetSnapshotDetail(
            id = 100L, snapshotId = 10L, assetId = 1L, assetName = "哨兵资产",
            assetType = com.example.stockdiary.database.AssetType.STOCK,
            balance = 1.0, price = 2.0, totalValue = 2.0, cost = 1.0, profit = 1.0, yieldRate = 1.0
        )
        val dividend = com.example.stockdiary.database.Dividend(
            id = 7, symbol = "sh600519", name = "哨兵分红", amount = 3.5, date = "2025-06-01"
        )
        val transaction = com.example.stockdiary.database.Transaction(
            id = 3L, symbol = "sh600519", name = "哨兵交易", type = "BUY",
            quantity = 2.0, price = 10.0, date = "2025-05-01", fee = 0.5
        )
        val stockBasic = com.example.stockdiary.database.StockBasic(
            code = "sh600519", name = "哨兵证券", market = "sh"
        )
        val fundNav = com.example.stockdiary.database.FundNavCache(
            code = "000306", name = "哨兵基金", nav = 1.2345, changePercent = 0.5,
            navDate = "2025-11-14", fetchedAt = exportedAt
        )
        return BackupPayload(
            formatVersion = 1, dbVersion = 18, exportedAt = exportedAt,
            assets = listOf(asset),
            snapshots = listOf(snapshot),
            snapshotDetails = listOf(detail),
            dividends = listOf(dividend),
            transactions = listOf(transaction),
            stockBasics = listOf(stockBasic),
            fundNavCaches = listOf(fundNav)
        )
    }

    @Test
    fun `exportBackup_真实写出的字节是完整七表备份而非空壳`() = runTest {
        val uri = mockk<Uri>(relaxed = true)
        val payload = sentinelPayload()
        // buildPayload 返回 7 表齐全的哨兵载荷；openOutputStream 用 ByteArrayOutputStream 捕获真实写出字节
        coEvery { backupManager.buildPayload() } returns payload
        val sink = ByteArrayOutputStream()
        every { appContext.contentResolver } returns contentResolver
        every { contentResolver.openOutputStream(uri) } returns sink

        vm.exportBackup(uri)
        assertTrue(waitUntil { vm.uiState.value.backupResult != null })

        assertTrue("导出应成功，实际 ${vm.uiState.value.backupResult}", vm.uiState.value.backupResult?.isSuccess == true)
        val written = sink.toByteArray()
        assertTrue("不得写出空文件", written.isNotEmpty())
        // 真实写出的字节必须能 decode 回同一份 7 表载荷：把 encode 写成 "{}" 或只写半份，
        // 这里 decode 会抛 InvalidBackupException 或字段对不上，测试失败。
        val decoded = BackupCodec.decode(String(written, Charsets.UTF_8))
        assertEquals(payload, decoded)
        // 逐表哨兵断言：明确证明每一张表都真的写进了文件
        assertEquals("哨兵资产", decoded.assets.single().name)
        assertEquals("哨兵分红", decoded.dividends.single().name)
        assertEquals("哨兵交易", decoded.transactions.single().name)
        assertEquals("哨兵证券", decoded.stockBasics.single().name)
        assertEquals("哨兵基金", decoded.fundNavCaches.single().name)
        assertEquals(1, decoded.snapshots.size)
        assertEquals(10L, decoded.snapshotDetails.single().snapshotId)
    }

    @Test
    fun `restoreBackup_传给BackupManager的实参是完整解码载荷且身份摘要一致`() = runTest {
        val uri = mockk<Uri>(relaxed = true)
        val payload = sentinelPayload()
        val json = BackupCodec.encode(payload)
        // 两次读流都返回同一份完整 JSON（第一次摘要、第二次 decode）
        stubInputStream(uri) { json }
        val captured = slot<BackupPayload>()
        coEvery { backupManager.restore(capture(captured)) } just Runs

        vm.readBackup(uri)
        assertTrue(waitUntil { vm.uiState.value.pendingRestore != null })
        vm.restoreBackup()
        assertTrue(waitUntil { vm.uiState.value.backupResult != null })

        assertTrue(vm.uiState.value.backupResult?.isSuccess == true)
        val passed = captured.captured
        // 7 张表行数与载荷一致（不是被换成空列表/半份）
        assertEquals(1, passed.assets.size)
        assertEquals(1, passed.snapshots.size)
        assertEquals(1, passed.snapshotDetails.size)
        assertEquals(1, passed.dividends.size)
        assertEquals(1, passed.transactions.size)
        assertEquals(1, passed.stockBasics.size)
        assertEquals(1, passed.fundNavCaches.size)
        // 关键字段未被替换，且传给 Manager 的载荷与确认时摘要身份一致
        assertEquals("哨兵资产", passed.assets.single().name)
        assertEquals(10L, passed.snapshotDetails.single().snapshotId)
        assertTrue(BackupCodec.hasSameIdentity(passed, BackupCodec.summary(json)))
    }

    @Test
    fun `B4 exportBackup 失败时清空pendingRestore并删除残件`() = runTest {

        val uri = mockk<Uri>(relaxed = true)
        val backupUri = mockk<Uri>(relaxed = true)
        stubInputStream(uri) { backupJson(555L) }
        every { appContext.contentResolver } returns contentResolver
        every { contentResolver.openOutputStream(backupUri) } throws IllegalStateException("磁盘已满")
        every { contentResolver.delete(backupUri, null, null) } returns 1

        vm.readBackup(uri)
        assertTrue(waitUntil { vm.uiState.value.pendingRestore != null })
        vm.exportBackup(backupUri)
        assertTrue(waitUntil { vm.uiState.value.backupResult != null })

        assertNull("导出开始/失败都要清掉残留的待恢复确认框", vm.uiState.value.pendingRestore)
        assertTrue(vm.uiState.value.backupResult?.isError == true)
        assertFalse(vm.uiState.value.isExporting)
        assertFalse(vm.uiState.value.isBackingUp)
        verify { contentResolver.delete(backupUri, null, null) }
    }
}
