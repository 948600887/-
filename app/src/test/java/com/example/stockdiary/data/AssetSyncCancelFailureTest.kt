package com.example.stockdiary.data

import android.content.Context
import com.example.stockdiary.data.price.StockDataManager
import com.example.stockdiary.data.repository.AssetRepository
import com.example.stockdiary.data.repository.DividendRepository
import com.example.stockdiary.data.sync.AssetSyncManager
import com.example.stockdiary.data.contract.SyncErrorType
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.network.UnifiedPriceService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.IOException
import java.util.Collections

/**
 * QA 独立复验（fresh eyes）：Bug 2「同步取消时成功不被静默判失败」的针对性回归。
 *
 * ## 修复语义（AssetSyncManager.syncSingleAsset 写失败结果两处，约 :1097 与 :1112）
 * ```
 * if (currentCoroutineContext().isActive) {
 *     failedCount.incrementAndGet()
 *     errorTypes.offer(SyncStrategy.classifyError(lastError))
 * } else {
 *     // 协程已取消：lastError 多半是「我们自己放弃」的产物，不计入失败数
 * }
 * ```
 * 即：已取消的协程里触发的失败分支，跳过 failedCount/errorTypes 记账；
 * 成功分支（isSuccess 时 successCount++）不加守卫、正常记成功。
 *
 * ## 本组钉死的两类契约（核心：真实区分「活跃」与「取消」两种状态）
 *
 *  1. **前台活跃 + 真实取价失败** → 仍正常计入 failedCount（对照组）。
 *     一个 FUND 标的的 getPrice 返回 Result.failure(网络异常)，断言 result.failedCount == 1、
 *     且错误类型被归类（errorTypes 非空的副作用也发生了）。证明「活跃态失败照常计数，修复没把真失败漏掉」。
 *  2. **同步取消 + 取价失败** → failedCount 不因取消而增长。
 *     构造：看门狗在 ~2s 超预算取消在途子任务（runSync 自身仍活跃、照常返回 SyncResult），
 *     但子任务的 getPrice 用 Thread.sleep 阻塞到 4s 才返回 Result.failure——期间协程已取消
 *     (isActive=false)、且 getPrice 返回的是「值」而非抛异常，于是失败分支仍被走到、但
 *     isActive 守卫跳过记账。断言 result.failedCount == 0、dominantErrorType == null、
 *     且截断标记照常置位。这就是修复前「成功被静默判失败」的场景。
 *
 * 基础设施与 [AssetSyncManagerTest] 同构（relaxed mock + 桩 + 退后台取消套路）。
 * Main 调度器装 [Dispatchers.Unconfined]（与既有同步测试一致）。
 */
class AssetSyncCancelFailureTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun setupMain() {
            Dispatchers.setMain(Dispatchers.Unconfined)
        }

        @AfterClass
        @JvmStatic
        fun resetMainDispatcher() {
            Dispatchers.resetMain()
        }
    }

    private val context = mockk<Context>(relaxed = true)
    private val repository = mockk<AssetRepository>(relaxed = true)
    private val stockDataManager = mockk<StockDataManager>(relaxed = true)
    private val dividendRepository = mockk<DividendRepository>(relaxed = true)

    /** 事件流水：记录子任务是否真的进入 getPrice，用来确认取消发生在「在途」期间 */
    private val events = Collections.synchronizedList(mutableListOf<String>())

    @Before
    fun setUp() {
        events.clear()
        AssetSyncManager.resetForTesting()
        mockkObject(StockDataManager.Companion)
        every { StockDataManager.getInstance(any()) } returns stockDataManager
        mockkObject(DividendRepository.Companion)
        every { DividendRepository.getInstance(any()) } returns dividendRepository
        mockkObject(UnifiedPriceService)
    }

    @After
    fun tearDown() = unmockkAll()

    private fun fundAsset(symbol: String) = Asset(
        name = "基金-$symbol",
        type = AssetType.FUND,
        symbol = symbol,
        account = "默认",
        balance = 100.0
    )

    private suspend fun awaitUntil(timeoutMs: Long = 8000, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            delay(10)
        }
        return cond()
    }

    // ---------- 契约 1：前台活跃 + 真实失败 计入 failedCount（对照组） ----------

    @Test
    fun `前台活跃协程_真实取价失败_计入 failedCount`() = runBlocking {
        coEvery { UnifiedPriceService.getPrice("010001", AssetType.FUND, any()) } returns
            Result.failure(IOException("connection timeout"))

        val result = AssetSyncManager.syncAllAssets(
            context, repository, listOf(fundAsset("fund_010001")), {}, forceRefresh = true,
            startTime = System.currentTimeMillis(), timeLimitMs = 0L
        )

        // 对照：前台活跃时的真实网络失败必须正常计入失败数，并归类出错误类型
        assertEquals("前台真实失败必须计入 failedCount", 1, result.failedCount)
        assertEquals(0, result.successCount)
        assertEquals(SyncErrorType.NETWORK, result.dominantErrorType)
    }

    // ---------- 契约 2：同步取消 + 取价失败 不计入 failedCount（修复核心） ----------

    @Test
    fun `同步取消时_取价失败不被静默判失败_failedCount 不增长`() = runBlocking {
        // 让取价在「看门狗超预算取消」之后才返回失败：期间协程已被取消(isActive=false)，
        // 但 getPrice 返回的是 Result.failure（不是抛异常），失败分支仍会被走到。
        // 修复前的口径会把这次「取消产物的失败」计入 failedCount（「成功被静默判失败」），
        // 修复后由 currentCoroutineContext().isActive 守卫跳过。
        coEvery { UnifiedPriceService.getPrice("010001", AssetType.FUND, any()) } coAnswers {
            events += "enter-010001"
            // 阻塞：看门狗在 ~2s 取消在途子任务，但阻塞的 getPrice 不被打断
            // （与「execute() 跑到 callTimeout 才收尾」同构：取消状态一直保持到它返回）。
            Thread.sleep(4000)
            Result.failure(IOException("connection timeout"))
        }

        val deferred = async(Dispatchers.IO) {
            AssetSyncManager.syncAllAssets(
                context, repository, listOf(fundAsset("fund_010001")), {}, forceRefresh = true,
                startTime = System.currentTimeMillis(), timeLimitMs = 1500L
            )
        }
        assertTrue("应进入取价（在途期间才会被看门狗取消）", awaitUntil { events.contains("enter-010001") })
        val result = withTimeout(15_000) { deferred.await() }

        // 关键断言：取消后才返回的失败不得计入 failedCount（否则「成功被静默判失败」）
        assertEquals("取消后的失败不应计入 failedCount", 0, result.failedCount)
        assertEquals("取消态下成功也不应有", 0, result.successCount)
        assertNull(
            "取消产生的失败不应归类出任何错误类型（否则触发误导性的「同步多次失败」通知）",
            result.dominantErrorType
        )
        // 看门狗确实触发了截断——证明取消是「真实在途取消」而非用例压根没跑
        assertTrue("看门狗应触发截断标记", result.truncatedByTimeLimit)
    }
}
