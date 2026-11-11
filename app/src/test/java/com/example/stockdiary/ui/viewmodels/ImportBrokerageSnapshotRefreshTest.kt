package com.example.stockdiary.ui.viewmodels

import com.example.stockdiary.data.importer.BrokerageImportService
import com.example.stockdiary.data.importer.OcrStatementService
import com.example.stockdiary.data.repository.AssetRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.AfterClass
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * 交割单导入完成后的「当日快照刷新」接线测试。
 *
 * 持仓截图 / 币安导入都会调 [com.example.stockdiary.data.sync.refreshTodaySnapshotAfterImport]，
 * 交割单曾经漏接：导入后首页净资产已变，历史页「今天」还停在导入前的旧值，直到下一次同步。
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

    @Test
    fun `交割单导入有成功行时刷新当日快照`() = runBlocking {
        val service = mockk<BrokerageImportService>(relaxed = true)
        coEvery { service.commit(any()) } returns result(success = 2)
        val repository = mockk<AssetRepository>(relaxed = true)
        coEvery { repository.replaceSnapshotForDate(any()) } returns null

        val vm = ImportBrokerageViewModel(service, mockk<OcrStatementService>(relaxed = true), repository)
        vm.commit()

        assertTrue("导入提交未完成", waitUntil { vm.uiState.value.commitResult != null })
        // 快照刷新与状态更新在同一个 IO 协程里顺序执行，稍等它落地
        assertTrue(
            "交割单导入成功后没有刷新当日快照",
            waitUntil {
                runCatching {
                    coVerify(exactly = 1) { repository.replaceSnapshotForDate(any()) }
                }.isSuccess
            }
        )
    }

    @Test
    fun `交割单导入零成功时不刷新当日快照`() = runBlocking {
        val service = mockk<BrokerageImportService>(relaxed = true)
        coEvery { service.commit(any()) } returns result(success = 0)
        val repository = mockk<AssetRepository>(relaxed = true)

        val vm = ImportBrokerageViewModel(service, mockk<OcrStatementService>(relaxed = true), repository)
        vm.commit()

        assertTrue("导入提交未完成", waitUntil { vm.uiState.value.commitResult != null })
        coVerify(exactly = 0) { repository.replaceSnapshotForDate(any()) }
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
