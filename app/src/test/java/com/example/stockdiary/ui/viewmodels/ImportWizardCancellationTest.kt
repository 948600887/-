package com.example.stockdiary.ui.viewmodels

import android.content.Context
import android.net.Uri
import com.example.stockdiary.data.importer.BinanceCredentialsManager
import com.example.stockdiary.data.importer.BinanceImportService
import com.example.stockdiary.data.importer.BrokerageImportService
import com.example.stockdiary.data.importer.HoldingSnapshotService
import com.example.stockdiary.data.importer.ImageTextRecognizer
import com.example.stockdiary.data.importer.OcrStatementService
import com.example.stockdiary.data.repository.AssetRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.AfterClass
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * 取消回归测试：验证「拉取 / 识别 / 写入 进行中取消协程」会把进度态复位，
 * 避免弹窗卡在转圈页（fetching / recognizing / committing 永不复位）。
 *
 * ## 根因
 * 见 [ImportWizardViewModel.launchIo]：原先取消只原样重抛 [kotlinx.coroutines.CancellationException]，
 * 不复位进度态；现在新增 `onCancel` 回调，在取消分支里复位进度态。
 *
 * ## 仿真方式
 * 把底层 suspend 桩成 [awaitCancellation]（可取消挂起、永不返回），等进度态置位后
 * 取消 `activeJob`，断言进度态被 `onCancel` 复位。
 *
 * 关键：测试子类**只调 `activeJob.cancel()`，不走 `reset()`**——`reset()` 自身也会把
 * 进度态清掉，若走 reset 就分不清是 `onCancel` 还是 `reset` 复位，回归测试就失去意义。
 *
 * ## 调度
 * 与 [CalendarViewModelTest] 一致：用 `runBlocking` + 实时轮询。IO 是真实线程，
 * 虚拟时钟推不动，只能等它落地。Main 在 @BeforeClass 设一次、@AfterClass 才 reset。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ImportWizardCancellationTest {

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

    /** 暴露 protected activeJob，便于测试中只取消协程（不走 reset）。 */
    private class CancelableBinanceVm(
        context: Context,
        repository: AssetRepository,
        service: BinanceImportService,
        credentialsManager: BinanceCredentialsManager
    ) : BinanceImportViewModel(context, repository, service, credentialsManager) {
        fun cancelJobForTest() {
            activeJob?.cancel()
        }
    }

    private class CancelableHoldingVm(
        context: Context,
        repository: AssetRepository,
        service: HoldingSnapshotService,
        recognizer: ImageTextRecognizer,
        source: HoldingSnapshotViewModel.Source
    ) : HoldingSnapshotViewModel(context, repository, service, recognizer, source) {
        fun cancelJobForTest() {
            activeJob?.cancel()
        }
    }

    private class CancelableBrokerageVm(
        service: BrokerageImportService,
        ocrService: OcrStatementService
    ) : ImportBrokerageViewModel(service, ocrService) {
        fun cancelJobForTest() {
            activeJob?.cancel()
        }
    }

    /** 实时轮询等 IO 上的协程落地；返回是否达成，失败细节交给后续断言 */
    private fun waitUntil(timeoutMs: Long = 4000, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            Thread.sleep(5)
        }
        return cond()
    }

    @Test
    fun `币安拉取进行中取消会复位 fetching 与 step`() = runBlocking {
        // init 块（BinanceImportViewModel:80）会异步读已存密钥并回填：
        // relaxed mock 的 getApiKey() 返回 null → 把 apiKey/apiSecret 清空。
        // 若不对齐时序，全量跑时 IO 线程可能在 onApiKeyChange("k") 之后、fetch()
        // 捕获状态之前把 apiKey 清掉，使 fetch() 命中空白守卫提前 return，
        // fetching 永不置位、回归测试误报。这里给 credentialsManager 一个可观测的
        // 哨兵返回值，等 init 回填完成（apiKey == 哨兵）再设真实 key，保证时序确定。
        val credentialsManager = mockk<BinanceCredentialsManager>(relaxed = true)
        coEvery { credentialsManager.getApiKey() } returns "PRESET_KEY"
        coEvery { credentialsManager.getApiSecret() } returns "PRESET_SECRET"

        val service = mockk<BinanceImportService>(relaxed = true)
        // 拉取编排已下沉到 BinanceImportService.fetchAccount（见审计 H2），
        // 底层 suspend 桩成永不返回的挂起（可取消），停在「正在拉取」
        coEvery { service.fetchAccount(any(), any()) } coAnswers { awaitCancellation() }

        val vm = CancelableBinanceVm(
            context = mockk(relaxed = true),
            repository = mockk(relaxed = true),
            service = service,
            credentialsManager = credentialsManager
        )
        // 等 init 异步回填密钥（说明 init 协程已 settle，之后设的 key 不会被覆盖）
        val initSettled = waitUntil { vm.uiState.value.apiKey == "PRESET_KEY" }
        assertTrue("init 异步回填密钥未完成，测试时序不可靠", initSettled)

        vm.onApiKeyChange("k")
        vm.onApiSecretChange("s")
        vm.fetch()

        // 等到 IO 协程把 fetching 置位（已进入 fetchAccount 挂起）
        val reachedFetching = waitUntil { vm.uiState.value.fetching }
        assertTrue("fetch 后 fetching 未置位，说明协程未真正进入拉取", reachedFetching)
        assertTrue(
            "fetch 后 step 应为 FETCHING",
            vm.uiState.value.step == BinanceImportViewModel.Step.FETCHING
        )

        // 模拟「关弹窗 / 离开」取消在途协程（只取消，不走 reset）
        vm.cancelJobForTest()

        // 取消后 onCancel 必须把进度态复位，弹窗才不会卡在转圈
        val recovered = waitUntil {
            !vm.uiState.value.fetching && vm.uiState.value.step == BinanceImportViewModel.Step.INPUT
        }
        assertTrue("取消后 fetching/step 未复位，弹窗会卡在「正在拉取…」", recovered)
        assertNull("取消不应残留 error（与复位语义一致）", vm.uiState.value.error)
    }

    @Test
    fun `持仓截图识别中取消会复位 recognizing`() = runBlocking {
        val recognizer = mockk<ImageTextRecognizer>(relaxed = true)
        coEvery { recognizer.recognize(any()) } coAnswers { awaitCancellation() }

        val vm = CancelableHoldingVm(
            context = mockk(relaxed = true),
            repository = mockk(relaxed = true),
            service = mockk(relaxed = true),
            recognizer = recognizer,
            source = HoldingSnapshotViewModel.Source.STOCK_SNAPSHOT
        )
        vm.onImageSelected(mockk(relaxed = true))

        // 等到 IO 协程把 recognizing 置位（已进入 recognize 挂起）
        val reached = waitUntil { vm.uiState.value.recognizing }
        assertTrue("onImageSelected 后 recognizing 未置位", reached)

        vm.cancelJobForTest()

        val recovered = waitUntil {
            !vm.uiState.value.recognizing && vm.uiState.value.step == HoldingSnapshotViewModel.Step.SELECT_IMAGE
        }
        assertTrue("识别中取消后 recognizing/step 未复位，弹窗会卡在选图页转圈", recovered)
    }

    @Test
    fun `交割单OCR识别中取消会复位 recognizing`() = runBlocking {
        val ocr = mockk<OcrStatementService>(relaxed = true)
        coEvery { ocr.recognizeAndParse(any()) } coAnswers { awaitCancellation() }

        val vm = CancelableBrokerageVm(
            service = mockk(relaxed = true),
            ocrService = ocr
        )
        vm.onImageSelected(mockk(relaxed = true))

        // 等到 IO 协程把 recognizing 置位（已进入 recognizeAndParse 挂起）
        val reached = waitUntil { vm.uiState.value.recognizing }
        assertTrue("onImageSelected 后 recognizing 未置位", reached)

        vm.cancelJobForTest()

        val recovered = waitUntil {
            !vm.uiState.value.recognizing && vm.uiState.value.step == ImportBrokerageViewModel.Step.SELECT_FILE
        }
        assertTrue("OCR 识别中取消后 recognizing/step 未复位，弹窗会卡在来源页转圈", recovered)
    }
}
