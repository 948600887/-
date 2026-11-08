package com.example.stockdiary.ui.viewmodels

import com.example.stockdiary.data.calculator.SnapshotAmountInput
import com.example.stockdiary.data.prefs.DashboardPreferences
import com.example.stockdiary.data.prefs.DashboardPreferencesManager
import com.example.stockdiary.data.prefs.SnapshotManualAdjustmentStore
import com.example.stockdiary.data.repository.AssetRepository
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * 历史页读取链路的两条回归：
 * 1. Room/磁盘异常不能被当成「没有历史数据」——页面要进入错误态并允许重试；
 * 2. 保存快照的真实异常不能被 createSnapshot 吞成 null 后误报成「当天没有持仓」。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {

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

    private val assetRepository = mockk<AssetRepository>(relaxed = true)
    private val preferencesManager = mockk<DashboardPreferencesManager>(relaxed = true)
    private val manualAdjustmentStore = mockk<SnapshotManualAdjustmentStore>(relaxed = true)

    private val asset = Asset(
        name = "长江电力", type = AssetType.STOCK, symbol = "600900",
        account = "默认", balance = 1000.0
    )

    private fun stubPreferences() {
        every { preferencesManager.preferences } returns MutableStateFlow(DashboardPreferences())
    }

    @Test
    fun `快照读取流异常时进入错误态而不是空数据`() = runBlocking {
        stubPreferences()
        every { assetRepository.getAllAssets() } returns flowOf(listOf(asset))
        every { assetRepository.getSnapshotsInDateRange(any(), any()) } returns
            flow { throw RuntimeException("database is locked") }
        every { assetRepository.getInvalidDateSnapshots() } returns flowOf(emptyList())
        every { assetRepository.getSnapshotBeforeDate(any()) } returns flowOf(null)
        every { assetRepository.getSnapshotCountFlow() } returns flowOf(1)

        val vm = HistoryViewModel(assetRepository, preferencesManager, manualAdjustmentStore)

        val state = withTimeout(5_000) {
            vm.uiState.first { it.snapshotsFrameReady }
        }

        assertFalse("读取失败时不能继续显示加载态", state.isLoading)
        assertNotNull("读取失败必须暴露页面级错误", state.loadError)
        assertTrue("错误态下不应把空列表当成功结果", state.filteredSnapshots.isEmpty())
    }

    @Test
    fun `保存快照的真实异常不会被误报成当天没有持仓`() = runBlocking {
        stubPreferences()
        every { assetRepository.getAllAssets() } returns flowOf(listOf(asset))
        every { assetRepository.getSnapshotsInDateRange(any(), any()) } returns flowOf(emptyList())
        every { assetRepository.getInvalidDateSnapshots() } returns flowOf(emptyList())
        every { assetRepository.getSnapshotBeforeDate(any()) } returns flowOf(null)
        every { assetRepository.getSnapshotCountFlow() } returns flowOf(1)
        coEvery { assetRepository.createSnapshot(any()) } throws RuntimeException("disk full")

        val vm = HistoryViewModel(assetRepository, preferencesManager, manualAdjustmentStore)
        vm.saveSnapshot()

        val state = withTimeout(5_000) {
            vm.uiState.first { it.saveResult != null }
        }
        val result = state.saveResult!!
        assertTrue(result.isFailure)
        assertEquals("disk full", result.exceptionOrNull()?.message)
        assertNotEquals(
            "当天没有持仓，未保存快照",
            state.errorMessage
        )
    }

    @Test
    fun `五项资产全为 0 时拒绝保存并提示`() = runBlocking {
        stubPreferences()
        every { assetRepository.getAllAssets() } returns flowOf(listOf(asset))
        every { assetRepository.getSnapshotsInDateRange(any(), any()) } returns flowOf(emptyList())
        every { assetRepository.getInvalidDateSnapshots() } returns flowOf(emptyList())
        every { assetRepository.getSnapshotBeforeDate(any()) } returns flowOf(null)
        every { assetRepository.getSnapshotCountFlow() } returns flowOf(1)

        val vm = HistoryViewModel(assetRepository, preferencesManager, manualAdjustmentStore)
        vm.updateManagedSnapshot("2026-11-01", SnapshotAmountInput())

        val state = withTimeout(5_000) {
            vm.uiState.first { it.errorMessage != null }
        }
        assertEquals("编辑快照失败：至少填写一项资产金额", state.errorMessage)
        coVerify(exactly = 0) { assetRepository.updateSnapshotAmounts(any(), any()) }
    }
}
