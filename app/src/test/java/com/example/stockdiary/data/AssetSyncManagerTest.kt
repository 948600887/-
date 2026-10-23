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
import com.example.stockdiary.network.BinanceHostUnavailableException
import com.example.stockdiary.network.model.AssetPrice
import com.example.stockdiary.network.model.FundData
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    // ---------- 退后台取消在途手动同步（B3） ----------

    @Test
    fun `无在途手动同步时取消是 no-op`() = runBlocking {
        AssetSyncManager.resetForTesting()
        assertNull("复位后不该有在途句柄", AssetSyncManager.activeManualSyncJobForTesting())
        // 没有在途同步：取消直接返回，不抛、不改状态
        AssetSyncManager.cancelActiveManualSync("无在途时取消")
        assertNull("no-op 取消不该凭空造出句柄", AssetSyncManager.activeManualSyncJobForTesting())
    }

    @Test
    fun `有在途手动同步时取消_真的取消且 finally 清空句柄`() = runBlocking {
        AssetSyncManager.resetForTesting()
        val gate = CompletableDeferred<Unit>()
        stubBlocking("010001", gate)

        val syncJob = async(Dispatchers.Default) {
            AssetSyncManager.syncAllAssets(
                context, repository, listOf(fundAsset("fund_010001")), {}, forceRefresh = true
            )
        }
        // 等它真正进入抓取并登记在途句柄（manualSyncMutex 块内置上）
        assertTrue(
            "手动同步应登记在途句柄",
            awaitUntil { AssetSyncManager.activeManualSyncJobForTesting() != null }
        )
        assertNotNull(AssetSyncManager.activeManualSyncJobForTesting())

        // 退后台取消：取消 activeManualSyncJob（= 这个 syncJob）
        AssetSyncManager.cancelActiveManualSync("测试取消在途手动同步")

        // 取消后协程应变为已取消（await 立即返回、同步立即收尾）
        assertTrue("取消后手动同步协程应已取消", awaitUntil { !syncJob.isActive })
        assertTrue(syncJob.isCancelled)
        // syncAllAssets 的 finally 会清空句柄，避免泄漏/误伤下次
        assertTrue(
            "finally 应清空在途句柄",
            awaitUntil { AssetSyncManager.activeManualSyncJobForTesting() == null }
        )

        // 收尾：放行 gate，让被取消的链路彻底结束（避免悬挂协程）。
        // 末尾 .let{} 令 runBlocking 块返回 Unit，满足 JUnit「测试方法返回 void」约定。
        gate.complete(Unit)
        runCatching { withTimeout(5000) { syncJob.await() } }.let { }
    }

    // ---------- 熔断期跳过不计失败（BinanceHostGate 的原始目的） ----------

    @Test
    fun `熔断期加密货币跳过不计失败_不触发同步失败通知`() = runBlocking {
        // 币安主机级熔断期间，UnifiedPriceService 对加密货币返回 BinanceHostUnavailableException；
        // syncSingleAsset 必须走 isUnavailable(lastError) 分支：不计 failedCount、不计 errorType、
        // 不推高「同步多次失败」通知——这是熔断器存在的原始目的，任何改动都不该破。
        val crypto = Asset(
            name = "BTC", type = AssetType.CRYPTO, symbol = "BTCUSDT",
            account = "币安", balance = 1.0
        )
        coEvery {
            UnifiedPriceService.getPrice("BTCUSDT", AssetType.CRYPTO, any())
        } returns Result.failure(BinanceHostUnavailableException("币安接口连续失败，已暂停访问 300 秒"))

        val result = AssetSyncManager.syncAllAssets(
            context, repository, listOf(crypto), {}, forceRefresh = true
        )

        // 跳过而非失败：不计成功也不计失败，无可重试错误，无主导错误类型
        assertEquals(0, result.successCount)
        assertEquals(0, result.failedCount)
        assertTrue("熔断跳过不该产生可重试错误", !result.hasRetryableErrors)
        assertNull("熔断跳过不该计入任何错误类型（否则触发误导性通知）", result.dominantErrorType)
    }

    /**
     * 收尾必须 flush：不足一批的落价意图也要写库。
     *
     * 背景：SyncPriceWriter 只在攒满 PRICE_WRITE_BATCH_SIZE(8) 条时才批量写一次。
     * 若收尾不调用 flushAll，每轮同步尾部最多 7 个**已经抓到**的价格会被静默丢弃——
     * 而标的处理顺序基本固定，被丢的往往总是同一批持仓，它们的现价会长期停在旧值，
     * 用户看到「刷了但某个持仓价格不动」，日志里却毫无痕迹。
     *
     * 这里用 3 个标的（< 批大小 8）钉死：若少了收尾 flush，
     * updatePricesInTransaction 一次都不会被调用，本测试直接失败。
     */
    @Test
    fun `收尾flush_不足一批的价格也要落库`() = runBlocking {
        val codes = listOf("010001", "010002", "010003")
        codes.forEach { code ->
            coEvery { UnifiedPriceService.getPrice(code, AssetType.FUND, any()) } coAnswers {
                Result.success(fundPrice(code))
            }
        }

        val result = AssetSyncManager.syncAllAssets(
            context, repository, codes.map { fundAsset("fund_$it") }, {}, forceRefresh = true
        )

        assertEquals(3, result.successCount)

        // 捕获批量落库的入参：必须被调用，且 3 条一条不少。
        // 用集合而非有序列表断言：三个标的是并发抓取的，完成顺序不固定。
        val slot = slot<List<com.example.stockdiary.data.repository.PendingPriceWrite>>()
        coVerify { repository.updatePricesInTransaction(capture(slot)) }
        val writes = slot.captured
        assertEquals("不足一批的落价意图被丢弃了", 3, writes.size)
        assertEquals(
            setOf("fund_010001", "fund_010002", "fund_010003"),
            writes.map { it.symbol }.toSet()
        )
        assertTrue("价格应原样写入", writes.all { it.price == 1.5 })
    }

    /**
     * 取消（退后台 / VM 销毁）时也必须 flush：已抓到但不足一批的价格不能白抓。
     *
     * SyncPriceWriter 的 KDoc 承诺「同步被取消时也要调用，否则会丢价」。收尾 flush
     * 因此必须放在 finally 里并用 NonCancellable 包住——取消会让 withHeartbeat
     * 整个块抛 CancellationException，放在块之后的正常收尾那句根本执行不到。
     *
     * 构造：第一个标的秒回（价格进缓冲区，2 < 批大小 8 不会触发中途 flush），
     * 第二个挂在 gate 上把同步拖住，然后取消整个同步 job。
     */
    @Test
    fun `取消时也flush_已抓到的价格不白抓`() = runBlocking {
        coEvery { UnifiedPriceService.getPrice("010001", AssetType.FUND, any()) } coAnswers {
            events += "got-010001"
            Result.success(fundPrice("010001"))
        }
        val gate = CompletableDeferred<Unit>()
        coEvery { UnifiedPriceService.getPrice("010002", AssetType.FUND, any()) } coAnswers {
            gate.await()
            Result.success(fundPrice("010002"))
        }

        val job = launch {
            AssetSyncManager.syncAllAssets(
                context, repository,
                listOf(fundAsset("fund_010001"), fundAsset("fund_010002")), {},
                forceRefresh = true
            )
        }

        // 等第一个标的抓到，再留足时间让它的价格真正进到缓冲区
        assertTrue("第一个标的应被抓取", awaitUntil { events.contains("got-010001") })
        delay(300)

        // 模拟退后台 / VM 销毁：取消整个同步
        job.cancelAndJoin()

        // 取消路径也必须把缓冲区里已抓到的那条写掉，否则它白抓了
        val slot = slot<List<com.example.stockdiary.data.repository.PendingPriceWrite>>()
        coVerify(atLeast = 1) { repository.updatePricesInTransaction(capture(slot)) }
        assertTrue(
            "取消后应把已抓到的 fund_010001 落库，实际=${slot.captured.map { it.symbol }}",
            slot.captured.any { it.symbol == "fund_010001" }
        )
        // 注意：不要在本方法末尾调用 gate.complete(Unit) —— 它返回 Boolean，
        // 会让整个测试方法的推断返回类型变成 Boolean，JUnit4 直接判为非法测试类。
        // 挂在 gate 上的协程已随 job 取消而结束，无需补完。
    }
}
