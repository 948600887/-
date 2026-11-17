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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test

/**
 * 分红预取「不阻塞价格同步」与「取消轮次收尾日志」的行为契约。
 *
 * ## 为什么钉这两条
 *
 * 真机诊断（2026-11-15）实锤：手动刷新 31~33s 中分红预取独占 29.4s，
 * 价格同步只剩 2.0s —— 用户点刷新要看的是行情，却被配角占满了前台时间。
 * 修法是把预取转成独立后台 job，**预算与闸门原样保留**。
 *
 * 所以这里不测「预取做了什么」（那是 DividendRepository 的用例），只测两个新契约：
 * 1. 预取桩挂在 gate 上不返回时，[AssetSyncManager.syncAllAssets] 仍能在数秒内返回；
 * 2. 轮次被取消时，日志（与可测的收尾文本）会写出「取消收尾」而不是只剩「开始」。
 *
 * 测法与 [AssetSyncManagerTest] 一致：全场外基金（`fund_` + OTC 代码），
 * 桩掉门面的 getPrice / 分红仓库，整条链路不碰 Room 与网络。
 * Main 调度器装 Unconfined：进度回调要回主线程，Unconfined 就地执行。
 */
class AssetSyncManagerPrefetchNonBlockingTest {

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

    @Before
    fun setUp() {
        // object 是进程内单例：上一条用例若留下后台 job 会跨用例污染
        AssetSyncManager.resetForTesting()
        mockkObject(StockDataManager.Companion)
        every { StockDataManager.getInstance(any()) } returns stockDataManager
        mockkObject(DividendRepository.Companion)
        every { DividendRepository.getInstance(any()) } returns dividendRepository
        mockkObject(UnifiedPriceService)
        every { UnifiedPriceService.resetSourceStats() } returns Unit
        every { UnifiedPriceService.cryptoSourceStatsSnapshot() } returns emptyList()
        every { stockDataManager.sourceStatsSnapshot(any()) } returns emptyList()
        every { stockDataManager.isNetworkAvailableNow() } returns true
    }

    @After
    fun tearDown() {
        AssetSyncManager.resetForTesting()
        unmockkAll()
    }

    private fun fundAsset(symbol: String) = Asset(
        name = "基金-$symbol",
        type = AssetType.FUND,
        symbol = symbol,
        account = "默认",
        balance = 100.0
    )

    private fun fundPrice() = AssetPrice.Fund(
        FundData(code = "010001", name = "测试基金", price = 1.5, change = 0.01, changePercent = 0.67)
    )

    @Test
    fun `分红预取挂住不返回时价格同步仍立即完成`() = runBlocking {
        val prefetchEntered = CompletableDeferred<Unit>()
        val prefetchGate = CompletableDeferred<Unit>()
        coEvery { dividendRepository.prefetchDividends(any(), any(), any()) } coAnswers {
            prefetchEntered.complete(Unit)
            // 模拟「预取还在慢慢抓分红」：不释放，价格同步不应等它
            prefetchGate.await()
        }
        coEvery { UnifiedPriceService.getPrice(any(), AssetType.FUND, any()) } returns
            Result.success(fundPrice())

        val result = withTimeout(3_000) {
            AssetSyncManager.syncAllAssets(
                context, repository, listOf(fundAsset("fund_010001")), {}, forceRefresh = true
            )
        }

        assertEquals("价格同步不应被预取阻塞", 1, result.successCount)
        withTimeout(3_000) { prefetchEntered.await() }
        assertNotNull(
            "预取应作为独立后台 job 在推进",
            AssetSyncManager.backgroundDividendPrefetchJobForTesting()
        )

        // 收尾：释放 gate 并等后台 job 结束，避免泄漏到下一个用例
        prefetchGate.complete(Unit)
        AssetSyncManager.backgroundDividendPrefetchJobForTesting()?.join()
        // 表达式体 + 末尾 safe-call 会让返回类型变成 Unit?，JUnit4 要求 @Test 方法返回 void；
        // 补一个非空 Unit 把返回类型钉回 Unit，避免 initializationError。
        Unit
    }

    @Test
    fun `轮次被取消时写出取消收尾而非只剩开始`() = runBlocking {
        val priceEntered = CompletableDeferred<Unit>()
        val priceGate = CompletableDeferred<Unit>()
        coEvery { UnifiedPriceService.getPrice(any(), AssetType.FUND, any()) } coAnswers {
            priceEntered.complete(Unit)
            priceGate.await()
            Result.success(fundPrice())
        }

        val job = launch {
            AssetSyncManager.syncAllAssets(
                context, repository, listOf(fundAsset("fund_010001")), {}, forceRefresh = true
            )
        }
        withTimeout(3_000) { priceEntered.await() }
        // 给 runSync 一点时间把「开始」写出去，再模拟退后台取消
        delay(50)
        job.cancelAndJoin()

        val summary = AssetSyncManager.lastRoundCancelledSummaryForTesting()
        assertNotNull("取消轮次必须留一行收尾，否则日志上像丢了轮次", summary)
        assertTrue("收尾要能对上轮次：$summary", summary!!.contains("轮次#"))
        assertTrue("收尾要标明是取消：$summary", summary.contains("取消收尾"))
        assertTrue("收尾要带已完成进度：$summary", summary.contains("已完成"))
    }
}
