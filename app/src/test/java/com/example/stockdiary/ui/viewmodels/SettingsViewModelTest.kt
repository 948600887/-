package com.example.stockdiary.ui.viewmodels

import android.content.Context
import com.example.stockdiary.data.export.DataExporter
import com.example.stockdiary.data.prefs.DashboardPreferencesManager
import com.example.stockdiary.data.repository.AssetRepository
import com.example.stockdiary.data.sync.PriceSyncPreferencesManager
import com.example.stockdiary.data.sync.PriceSyncScheduler
import com.example.stockdiary.data.sync.SnapshotScheduler
import com.example.stockdiary.database.SnapshotDao
import com.example.stockdiary.database.TransactionDao
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test

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
    private val dataExporter = mockk<DataExporter>(relaxed = true)
    private val dashboardPreferencesManager = mockk<DashboardPreferencesManager>(relaxed = true)
    private val dataSourceHealthChecker = mockk<DataSourceHealthChecker>(relaxed = true)
    private val priceSyncPreferencesManager = mockk<PriceSyncPreferencesManager>(relaxed = true)

    private lateinit var vm: SettingsViewModel

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

        vm = SettingsViewModel(
            appContext = appContext,
            assetRepository = assetRepository,
            snapshotDao = snapshotDao,
            transactionDao = transactionDao,
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
        every { dataExporter.exportDividendsToCSV(any()) } returns fakeUri

        vm.exportData(mockk(relaxed = true), SettingsViewModel.ExportType.CSV_DIVIDENDS)
        waitUntil { vm.uiState.value.exportResult != null }

        coVerify { assetRepository.getAllDividends() }
        verify { dataExporter.exportDividendsToCSV(any()) }
        assertTrue(vm.uiState.value.exportResult?.isSuccess == true)
    }

    @Test
    fun `导出资产报告走 exportToText 分支`() = runBlocking {
        val fakeUri = mockk<android.net.Uri>(relaxed = true)
        every { dataExporter.exportToText(any()) } returns fakeUri

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
        assertNull(vm.reminderError.value)
    }
}
