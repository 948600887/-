package com.example.stockdiary.data

import com.example.stockdiary.data.repository.AssetRepository
import com.example.stockdiary.data.repository.DividendRepository
import com.example.stockdiary.data.price.StockDataManager
import com.example.stockdiary.data.sync.AssetSyncManager
import com.example.stockdiary.data.sync.SyncBudget
import android.content.Context
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.network.UnifiedPriceService
import com.example.stockdiary.network.model.AssetPrice
import com.example.stockdiary.network.model.FundData
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.util.Collections

/**
 * [AssetSyncManager] 手动/后台两条路径的行为契约回归。
 *
 * ## 为什么值得单测
 *
 * 这次「点刷新黑屏闪退」修复给 `syncAllAssets` 加了两个机制：**手动刷新互斥锁**
 * 与**时间预算**。判据是 `startTime <= 0` 视为手动刷新：
 *
 * - 手动路径套互斥锁 + 70 秒默认预算（[SyncBudget.MANUAL_SYNC_TIME_LIMIT_MS]，压在实测 ~83 秒 LMK 阈值之下）；
 * - 后台 [PriceSyncWorker] 传自己的 startTime 与 9 分钟上限，**两项都不套**——
 *   让用户点下的刷新排在 Worker 后面（最多 9 分钟）比叠加更糟。
 *
 * 这个「分流」目前没有任何测试兜底：一旦有人把 Worker 也套上互斥锁，或者把
 * 90 秒默认预算错按到 Worker 头上，编译照过、日常使用也未必立刻可见，
 * 但线上就是「刷新排队 9 分钟」或「后台同步被手动预算掐断」。这里用行为
 * （锁的串行/ bypass）加接线（预算入参）两个方向钉死。
 *
 * 测试里全部走**场外基金**（`fund_` 前缀 + OTC 代码）：分红批量预取对 OTC
 * 直接返回空（不走东财），逐标的路由到 [UnifiedPriceService]，桩掉它之后
 * 整条链路不碰 Room / OkHttp，JVM 单测可跑。
 *
 * Main 调度器装 [Dispatchers.Unconfined]：进度回调要 `withContext(Dispatchers.Main)`
 * 回主线程更新 StateFlow，而本测试有多个 IO 协程并发回调，用 Unconfined
 * 就地执行，避免测试调度器的线程约束。
 */
class AssetSyncManagerTest {

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
    private val repository = mockk<com.example.stockdiary.data.repository.AssetRepository>(relaxed = true)
    private val stockDataManager = mockk<StockDataManager>(relaxed = true)
    private val dividendRepository = mockk<DividendRepository>(relaxed = true)

    /** 事件流水：记录每个标的的进入/离开时机，用来断言串行与并发 */
    private val events = Collections.synchronizedList(mutableListOf<String>())

    @Before
    fun setUp() {
        events.clear()
        mockkObject(StockDataManager.Companion)
        every { StockDataManager.getInstance(any()) } returns stockDataManager
        mockkObject(DividendRepository.Companion)
        every { DividendRepository.getInstance(any()) } returns dividendRepository
        mockkObject(UnifiedPriceService)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun fundAsset(symbol: String) = Asset(
        name = "基金-$symbol",
        type = AssetType.FUND,
        symbol = symbol,
        account = "默认",
        balance = 100.0
    )

    private fun fundPrice(code: String) = AssetPrice.Fund(
        FundData(code = code, name = "测试基金", price = 1.5, change = 0.01, changePercent = 0.67)
    )

    /**
     * 阻塞式桩：进入时记事件、挂起在 gate 上、离开时再记一条。
     *
     * 注意 [UnifiedPriceService.getPrice] 的返回类型是 **kotlin.Result**
     * （那个文件没有 import common.Result），所以这里用 kotlin 的 Result.success，
     * 不要 import com.example.stockdiary.common.Result 把它遮掉。
     */
    private fun stubBlocking(code: String, gate: CompletableDeferred<Unit>) {
        coEvery { UnifiedPriceService.getPrice(code, AssetType.FUND, any()) } coAnswers {
            events += "enter-$code"
            gate.await()
            events += "exit-$code"
            Result.success(fundPrice(code))
        }
    }

    /**
     * 挂起式轮询（不能用 Thread.sleep：那会堵住 runBlocking 的事件循环，
     * sync1/sync2 这两个 async 子协程永远得不到调度，测试会退化成恒真）
     */
    private suspend fun awaitUntil(timeoutMs: Long = 3000, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            delay(10)
        }
        return cond()
    }

    @Test
    fun `两个手动刷新串行_第二次在互斥锁上排队`() = runBlocking {
        val gate1 = CompletableDeferred<Unit>()
        val gate2 = CompletableDeferred<Unit>()
        stubBlocking("010001", gate1)
        stubBlocking("010002", gate2)

        val sync1 = async {
            AssetSyncManager.syncAllAssets(
                context, repository, listOf(fundAsset("fund_010001")), {}, forceRefresh = true
            )
        }
        // 等第一次刷新真正进入抓取，再发起第二次
        awaitUntil { events.contains("enter-010001") }

        val sync2 = async {
            AssetSyncManager.syncAllAssets(
                context, repository, listOf(fundAsset("fund_010002")), {}, forceRefresh = true
            )
        }
        // 给 sync2 充分机会去抢锁（挂起让出事件循环，它真的会开始跑）：
        // 它必须还等在锁外，一个标的都没开始
        delay(300)
        assertTrue(
            "第二个手动刷新应仍在互斥锁上排队，实际事件=$events",
            events.none { it.startsWith("enter-010002") || it.startsWith("exit-010002") }
        )

        gate1.complete(Unit)
        val result1 = withTimeout(5000) { sync1.await() }
        // 第一个跑完释放锁，第二个这才获准进入
        assertTrue("锁释放后第二个刷新应开始抓取", awaitUntil { events.contains("enter-010002") })
        gate2.complete(Unit)
        val result2 = withTimeout(5000) { sync2.await() }

        assertEquals(1, result1.successCount)
        assertEquals(1, result2.successCount)
        // 严格串行：第一个完全结束之后第二个才开始
        assertEquals(listOf("enter-010001", "exit-010001", "enter-010002", "exit-010002"), events)
    }

    @Test
    fun `后台 Worker 不拿互斥锁_手动刷新卡住时 Worker 照样跑完`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        stubBlocking("010001", gate)
        // Worker 的桩不阻塞，但同样记事件，供下面的时序断言使用
        coEvery { UnifiedPriceService.getPrice("010002", AssetType.FUND, any()) } coAnswers {
            events += "enter-010002"
            events += "exit-010002"
            Result.success(fundPrice("010002"))
        }

        // 手动刷新：进去就卡在 gate 上，持锁不放
        val sync1 = async {
            AssetSyncManager.syncAllAssets(
                context, repository, listOf(fundAsset("fund_010001")), {}, forceRefresh = true
            )
        }
        awaitUntil { events.contains("enter-010001") }

        // 后台 Worker：传 startTime + 自己的 9 分钟上限
        val workerStart = System.currentTimeMillis()
        val sync2 = async {
            AssetSyncManager.syncAllAssets(
                context = context,
                repository = repository,
                assets = listOf(fundAsset("fund_010002")),
                onProgress = {},
                forceRefresh = true,
                startTime = workerStart,
                timeLimitMs = 9 * 60 * 1000L
            )
        }
        // 手动刷新还卡着：Worker 必须已经跑完，没有被互斥锁挡住
        val workerResult = withTimeout(5000) { sync2.await() }
        assertEquals(1, workerResult.successCount)
        assertTrue("Worker 应在手动刷新持锁期间完成", events.contains("exit-010002"))
        assertTrue("手动刷新此刻仍应卡在抓取里", events.contains("enter-010001") && !events.contains("exit-010001"))

        gate.complete(Unit)
        val manualResult = withTimeout(5000) { sync1.await() }
        // 手动刷新恢复后也应正常收尾（互斥锁已释放，结果完整）
        assertEquals(1, manualResult.successCount)
        assertTrue(manualResult.truncatedByTimeLimit.not())
    }

    @Test
    fun `手动刷新套 90 秒默认预算_Worker 用调用方传入的上限`() = runBlocking {
        // 预算规则本身由 SyncBudgetTest 钉死；这里验证的是 syncAllAssets 的**接线**：
        // 两条路径分别把哪个 timeLimitMs 交给了预算判定与预取子预算
        mockkObject(SyncBudget)
        every { SyncBudget.isExceeded(any(), any(), any()) } returns false
        every { SyncBudget.prefetchPhaseBudget(any()) } returns 0L
        val quickPrice = Result.success(fundPrice("010001"))
        coEvery { UnifiedPriceService.getPrice(any(), AssetType.FUND, any()) } returns quickPrice

        // 手动（不传 startTime）
        AssetSyncManager.syncAllAssets(
            context, repository, listOf(fundAsset("fund_010001")), {}, forceRefresh = true
        )
        verify { SyncBudget.prefetchPhaseBudget(SyncBudget.MANUAL_SYNC_TIME_LIMIT_MS) }
        verify { SyncBudget.isExceeded(any(), eq(SyncBudget.MANUAL_SYNC_TIME_LIMIT_MS), any()) }

        // 后台 Worker（传 startTime + 自己的上限）
        AssetSyncManager.syncAllAssets(
            context, repository, listOf(fundAsset("fund_010002")), {}, forceRefresh = true,
            startTime = 12345L, timeLimitMs = 540_000L
        )
        verify { SyncBudget.prefetchPhaseBudget(540_000L) }
        verify { SyncBudget.isExceeded(any(), eq(540_000L), any()) }
    }

    @Test
    fun `达到时间预算时未开始的标的被跳过_结果带截断标记`() = runBlocking {
        mockkObject(SyncBudget)
        every { SyncBudget.isExceeded(any(), any(), any()) } returns true
        // mockkObject 之后对象上所有方法都是桩：不打桩的调用会直接抛，
        // 预取子预算这一路也要给答案（返回 0 = 预取阶段不设限）
        every { SyncBudget.prefetchPhaseBudget(any()) } returns 0L

        val result = AssetSyncManager.syncAllAssets(
            context,
            repository,
            listOf(fundAsset("fund_010001"), fundAsset("fund_010002")),
            {},
            forceRefresh = true
        )

        assertTrue("截断标记必须透传到 SyncResult，调用方靠它提示用户", result.truncatedByTimeLimit)
        assertEquals(0, result.successCount)
        // 预算已尽：一个网络请求都不该再发
        coVerify(exactly = 0) { UnifiedPriceService.getPrice(any(), any(), any()) }
    }
}
