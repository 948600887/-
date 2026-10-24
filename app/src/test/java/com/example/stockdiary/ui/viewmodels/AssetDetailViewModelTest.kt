package com.example.stockdiary.ui.viewmodels

import com.example.stockdiary.data.repository.AssetRepository
import com.example.stockdiary.data.repository.DividendRepository
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.database.TransactionDao
import com.example.stockdiary.di.AppContainer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test

/**
 * AssetDetailViewModel 单测，聚焦 [AssetDetailViewModel.adjustPosition] 的三分支路由：
 * - delta>0  -> addOrUpdateStockAsset（加仓）
 * - delta<0  -> sellStockAsset（减仓）
 * - delta==0 -> updateAsset（只改成本价，不再误走减仓写零流水）
 *
 * 注意：adjustPosition 与 getAssetById 都是 suspend，调用与录桩都需 coroutine 上下文。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AssetDetailViewModelTest {

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
    private val txnDao = mockk<TransactionDao>(relaxed = true)
    private val container = mockk<AppContainer>(relaxed = true)

    private fun buildVm(assetId: Long = 1L, current: Asset? = null): AssetDetailViewModel {
        every { container.assetRepository } returns assetRepo
        every { container.dividendRepository } returns divRepo
        every { container.database } returns mockk { every { transactionDao() } returns txnDao }
        every { container.context } returns mockk(relaxed = true)
        if (current != null) {
            // getAssetById 是 suspend，录桩要用 coEvery（every 的录制 lambda 不在 coroutine 中）
            coEvery { assetRepo.getAssetById(assetId) } returns current
        }
        return AssetDetailViewModel(container, assetId)
    }

    private val current = Asset(
        id = 1,
        name = "茅台",
        type = AssetType.STOCK,
        symbol = "sh600519",
        account = "a",
        balance = 100.0,
        purchasePrice = 1000.0,
        currentPrice = 1100.0
    )

    @Test
    fun `delta 大于 0 走加仓 addOrUpdateStockAsset`() {
        val vm = buildVm(current = current)
        val updated = current.copy(balance = 150.0, purchasePrice = 1050.0) // delta = +50

        runBlocking { vm.adjustPosition(updated) }

        val balanceSlot = slot<Double>()
        coVerify {
            assetRepo.addOrUpdateStockAsset(
                any(), any(), capture(balanceSlot), any(),
                any(), any(), any(), any(), any(), any(), any(), any()
            )
        }
        // 加仓数量必须是增量 delta，而不是新余额
        assertEquals(50.0, balanceSlot.captured, 0.0001)
        coVerify(exactly = 0) { assetRepo.sellStockAsset(any(), any(), any()) }
        coVerify(exactly = 0) { assetRepo.updateAsset(any()) }
    }

    @Test
    fun `delta 小于 0 走减仓 sellStockAsset`() {
        val vm = buildVm(current = current)
        val updated = current.copy(balance = 80.0) // delta = -20

        runBlocking { vm.adjustPosition(updated) }

        coVerify { assetRepo.sellStockAsset(eq(current), eq(20.0), any()) }
        coVerify(exactly = 0) { assetRepo.addOrUpdateStockAsset(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { assetRepo.updateAsset(any()) }
    }

    @Test
    fun `delta 等于 0 只改成本价走 updateAsset`() {
        val vm = buildVm(current = current)
        val updated = current.copy(purchasePrice = 1234.0) // balance 不变，只改成本

        runBlocking { vm.adjustPosition(updated) }

        coVerify { assetRepo.updateAsset(eq(updated)) }
        coVerify(exactly = 0) { assetRepo.addOrUpdateStockAsset(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { assetRepo.sellStockAsset(any(), any(), any()) }
    }

    @Test
    fun `当前资产为空时 adjustPosition 直接返回不崩溃`() {
        // 不传 current，init 里 getAssetById 返回 null，_asset 为空
        coEvery { assetRepo.getAssetById(any()) } returns null
        val vm = buildVm()

        runBlocking { vm.adjustPosition(current.copy(balance = 150.0)) }

        coVerify(exactly = 0) { assetRepo.addOrUpdateStockAsset(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { assetRepo.sellStockAsset(any(), any(), any()) }
        coVerify(exactly = 0) { assetRepo.updateAsset(any()) }
    }
}
