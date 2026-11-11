package com.example.stockdiary.ui.viewmodels

import com.example.stockdiary.data.importer.BrokerageImportService
import com.example.stockdiary.data.importer.OcrStatementService
import com.example.stockdiary.data.repository.AssetRepository
import com.example.stockdiary.data.sync.refreshTodaySnapshotAfterImport
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * 交割单导入「未同步前禁止刷当日快照」的护栏测试。
 *
 * [refreshTodaySnapshotAfterImport] 的契约（见 `SyncSnapshotPolicy` KDoc 硬约束）：调用方必须
 * 先完成 `PriceSyncCoordinator.sync` 并把其 `successCount` 作为 `syncedCount` 传入。持仓截图 /
 * 币安导入都照此接线；交割单入口刻意不补前置行情同步（新建 Asset 无 `currentPrice`、按成交价
 * 估值，此时刷快照会把未同步价格固化进历史曲线并清掉当日手动调整），因此**不得**调用该函数。
 *
 * 曾有一版在 `commit()` 成功后直接用 `result.successCount` 刷新（把导入行数冒充同步数）。
 * 本测试作为护栏：VM 保持两参构造（不再持有 repository）+ 断言顶层函数 0 调用，防止后人
 * 再把调用加回来。
 *
 * ## 调度
 * 与 `ImportWizardCancellationTest` 一致：`runBlocking` + 实时轮询。IO 在真实线程，
 * 虚拟时钟推不动（**不用** `runTest` + 虚拟时间，避免挂起）。这里**不再**用
 * `runCatching { coVerify(...) }.isSuccess` 把断言异常吞成布尔 —— 断言失败必须真实抛出。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ImportBrokerageSnapshotRefreshTest {

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

    /** 静态 mock 顶层函数后每例清理，避免污染同一 test worker 里的其它测试类。 */
    @After
    fun clearStaticMocks() = unmockkAll()

    @Test
    fun `交割单导入成功后_未同步行情前禁止刷当日快照`() = runBlocking {
        mockkStatic("com.example.stockdiary.data.sync.SyncSnapshotPolicyKt")
        val service = mockk<BrokerageImportService>(relaxed = true)
        coEvery { service.commit(any()) } returns result(success = 2)
        val repository = mockk<AssetRepository>(relaxed = true)

        // 两参构造：VM 不再持有 repository（原第三参是专为那次刷新加的，已回退）。
        val vm = ImportBrokerageViewModel(service, mockk<OcrStatementService>(relaxed = true))
        vm.commit()

        assertTrue("导入提交未完成", waitUntil { vm.uiState.value.commitResult != null })
        // 断言真实抛出、不再被 runCatching 吞成布尔：导入落库 ≠ 行情同步。
        coVerify(exactly = 0) { refreshTodaySnapshotAfterImport(any(), any(), any()) }
        coVerify(exactly = 0) { repository.replaceSnapshotForDate(any()) }
    }

    @Test
    fun `交割单导入零成功时不刷新当日快照`() = runBlocking {
        mockkStatic("com.example.stockdiary.data.sync.SyncSnapshotPolicyKt")
        val service = mockk<BrokerageImportService>(relaxed = true)
        coEvery { service.commit(any()) } returns result(success = 0)
        val repository = mockk<AssetRepository>(relaxed = true)

        val vm = ImportBrokerageViewModel(service, mockk<OcrStatementService>(relaxed = true))
        vm.commit()

        assertTrue("导入提交未完成", waitUntil { vm.uiState.value.commitResult != null })
        coVerify(exactly = 0) { refreshTodaySnapshotAfterImport(any(), any(), any()) }
        coVerify(exactly = 0) { repository.replaceSnapshotForDate(any()) }
    }

    @Test
    fun `护栏自检_顶层函数静态mock确实能记录调用`() = runBlocking {
        mockkStatic("com.example.stockdiary.data.sync.SyncSnapshotPolicyKt")
        val repository = mockk<AssetRepository>(relaxed = true)
        // 主动调一次（已被静态 mock 拦截，不会真的落快照），用来证明下面两个用例里的
        // coVerify(exactly = 0) 不是「永远 0」的空断言：mock 生效时这次调用会被记录下来；
        // 若哪天静态 mock 失效，本例会先失败，避免护栏悄悄失灵。
        refreshTodaySnapshotAfterImport(repository, 2, "guard-selfcheck")
        coVerify(exactly = 1) { refreshTodaySnapshotAfterImport(any(), any(), any()) }
    }

    private fun result(success: Int) = BrokerageImportService.CommitResult(
        successCount = success,
        skippedDuplicate = 0,
        skippedByUser = 0,
        skippedError = 0,
        failedCount = 0,
        failures = emptyList()
    )

    /** 实时轮询等 IO 线程上的协程落地（虚拟时钟推不动 Dispatchers.IO） */
    private fun waitUntil(timeoutMs: Long = 4000, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            Thread.sleep(5)
        }
        return cond()
    }
}
