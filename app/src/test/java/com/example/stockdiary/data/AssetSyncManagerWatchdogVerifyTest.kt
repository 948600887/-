package com.example.stockdiary.data

import android.content.Context
import com.example.stockdiary.data.price.StockDataManager
import com.example.stockdiary.data.repository.AssetRepository
import com.example.stockdiary.data.repository.DividendRepository
import com.example.stockdiary.data.sync.AssetSyncManager
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.network.UnifiedPriceService
import com.example.stockdiary.network.model.AssetPrice
import com.example.stockdiary.network.model.FundData
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.util.Collections

/**
 * QA 独立复验（fresh eyes）：Part B（efe8978）看门狗与退后台取消的边界补测。
 *
 * 工程师已测：shouldContinueAfterChildCancel 纯函数两态、B3 的 no-op / 真取消+句柄清空。
 * 以下三条是工程师**没测**的边界，各对一个真实风险：
 *
 *  1. **Worker 路径绝不被 cancelActiveManualSync 波及**：Worker 传 startTime>0 走 else 分支、
 *     不登记 activeManualSyncJob；若登记逻辑误判（如把 startTime<=0 写反），退后台就会
 *     砍掉本该跑完的后台同步——这条测试直接钉死分流。
 *  2. **timeLimitMs<=0 时不设预算**：isExceeded 对 <=0 恒返 false、看门狗只在
 *     enforceTimeLimit（timeLimitMs>0）时 launch。若有人把看门狗改成无条件 launch，
 *     这条能抓到（不截断 + 正常跑完）。
 *  3. **看门狗端到端真的会砍在途请求**：纯函数测试证明不了 launch 出来的看门狗本身。
 *     这里用「新 startTime + 1.5s 预算 + 阻塞桩」构造：进场时快检查放行 → 看门狗
 *     1s 轮询后在 ~2s 超预算 → 取消在途子任务 → await 吞掉 CancellationException
 *     （父协程仍活）→ 照常返回 truncated=true 的 SyncResult，且不向上抛。
 *
 * 测试基础设施与 [AssetSyncManagerTest] 同构（ relaxed mock + 阻塞桩 + 挂起轮询），
 * 独立成文件，避免改动工程师的测试文件。
 */
class AssetSyncManagerWatchdogVerifyTest {

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

    private fun stubBlocking(code: String, gate: CompletableDeferred<Unit>) {
        coEvery { UnifiedPriceService.getPrice(code, AssetType.FUND, any()) } coAnswers {
            events += "enter-$code"
            gate.await()
            events += "exit-$code"
            Result.success(fundPrice(code))
        }
    }

    private fun stubQuick(code: String) {
        coEvery { UnifiedPriceService.getPrice(code, AssetType.FUND, any()) } coAnswers {
            events += "enter-$code"
            events += "exit-$code"
            Result.success(fundPrice(code))
        }
    }

    private suspend fun awaitUntil(timeoutMs: Long = 5000, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            delay(10)
        }
        return cond()
    }

    /**
     * Worker 路径（startTime>0）绝不登记在途手动同步句柄 → 退后台取消对它 no-op。
     * 防止「登记条件写反」把后台同步一起砍掉。
     */
    @Test
    fun `后台 Worker 同步不登记在途句柄_退后台取消不波及 Worker`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        stubBlocking("010001", gate)

        val workerJob = async(Dispatchers.Default) {
            AssetSyncManager.syncAllAssets(
                context = context,
                repository = repository,
                assets = listOf(fundAsset("fund_010001")),
                onProgress = {},
                forceRefresh = true,
                startTime = System.currentTimeMillis(), // Worker 风格：>0
                timeLimitMs = 9 * 60 * 1000L
            )
        }
        assertTrue("Worker 应进入抓取", awaitUntil { events.contains("enter-010001") })
        assertNull(
            "Worker 路径(startTime>0)绝不该登记 activeManualSyncJob，否则退后台会误砍后台同步",
            AssetSyncManager.activeManualSyncJobForTesting()
        )

        // 模拟 MainActivity.onStop：对 Worker 必须完全无感
        AssetSyncManager.cancelActiveManualSync("退后台（测试）")
        delay(300)
        assertTrue("退后台取消不该波及 Worker", workerJob.isActive)
        assertTrue("Worker 仍应卡在抓取里（未被取消）", events.contains("enter-010001") && !events.contains("exit-010001"))

        gate.complete(Unit)
        val result = withTimeout(10_000) { workerJob.await() }
        assertEquals(1, result.successCount)
        assertTrue("Worker 用自己的 9 分钟预算，不该被截断", result.truncatedByTimeLimit.not())
    }

    /**
     * timeLimitMs<=0 → 不设预算：快检查恒放行、看门狗根本不 launch。
     * 与改造前「无预算」行为一致。
     */
    @Test
    fun `timeLimitMs 为 0 时不设预算_正常跑完不截断`() = runBlocking {
        stubQuick("010001")

        val result = AssetSyncManager.syncAllAssets(
            context = context,
            repository = repository,
            assets = listOf(fundAsset("fund_010001")),
            onProgress = {},
            forceRefresh = true,
            startTime = System.currentTimeMillis(),
            timeLimitMs = 0L // 不设限
        )

        assertEquals("不设预算时标的应照常同步", 1, result.successCount)
        assertTrue("timeLimitMs<=0 不该产生截断标记", result.truncatedByTimeLimit.not())
        assertTrue(events.containsAll(listOf("enter-010001", "exit-010001")))
    }

    /**
     * 看门狗端到端：进场时未超预算（快检查放行）→ 在途请求挂起 → 看门狗轮询到超预算 →
     * 主动取消在途子任务 → 收集方吞掉子任务的 CancellationException（父协程仍活）→
     * 照常返回带 truncated=true 的 SyncResult，不向上抛异常。
     */
    @Test
    fun `看门狗超预算主动取消在途请求_结果截断且不向上抛`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        stubBlocking("010001", gate)

        val start = System.currentTimeMillis()
        val deferred = async(Dispatchers.Default) {
            AssetSyncManager.syncAllAssets(
                context = context,
                repository = repository,
                assets = listOf(fundAsset("fund_010001")),
                onProgress = {},
                forceRefresh = true,
                startTime = start,
                timeLimitMs = 1_500L // 1.5s 预算：进场(t≈0)放行，看门狗 ~2s 时砍
            )
        }
        assertTrue("进场时快检查应放行（预算未尽）", awaitUntil { events.contains("enter-010001") })

        // 看门狗应在超预算后取消在途任务； await 吞掉子取消、返回截断结果而不是抛异常
        val result = withTimeout(15_000) { deferred.await() }

        assertTrue("看门狗触发后必须带截断标记", result.truncatedByTimeLimit)
        assertEquals("在途请求被砍，没有标的跑完", 0, result.successCount)
        assertTrue(
            "在途请求应被看门狗取消（进了没出）",
            events.contains("enter-010001") && !events.contains("exit-010001")
        )

        // 收尾：放行被取消链路上悬挂的 gate。
        // 末尾 .let{} 令 runBlocking 块返回 Unit，满足 JUnit「测试方法返回 void」约定
        // （gate.complete 返回 Boolean，直接收尾会把测试方法变成非 void）。
        gate.complete(Unit)
        runCatching { withTimeout(15_000) { deferred.await() } }.let { }
    }
}
