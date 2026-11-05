package com.example.stockdiary.data.price

import android.content.Context
import com.example.stockdiary.common.Result
import com.example.stockdiary.network.EastmoneySkillApi
import com.example.stockdiary.network.QuoteSuspendedException
import com.example.stockdiary.network.SinaStockAPI
import com.example.stockdiary.network.TencentStockAPI
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 股票侧「整轮只记一次成败」回归（对齐币安侧 UnifiedPriceService 的批量记账语义）。
 *
 * 修复前：批量预取整体 0 命中先由 [StockDataManager.reportBatchTotalMiss] 记 1 次源级失败，
 * 随后 N 个逐标的回落又对**同一个源**各喂一次熔断，于是「1 + N」次把单波并发抖动放大成误熔。
 * 现在由 [RoundSourceFailureScope] 承载显式轮次 ID，同一轮已被批量记过账的源不再重复喂。
 *
 * 钉死四条不变量（阈值一律用 [SourceCircuitBreaker.FAIL_THRESHOLD] /
 * [com.example.stockdiary.network.BinanceHostGate.FAIL_THRESHOLD] 推导，绝不硬编码 8）：
 *  ① 批量 0 命中 + N 个回落标的 → 只喂熔断 1 次、统计也只记 1 次；
 *  ② 停牌 / 符号级确定性失败仍不计数；
 *  ③ 前台（无轮次上下文）真实 IO 失败仍逐次记账、可达 OPEN；
 *  ④ 真实持续多波故障仍能打到 OPEN；且批内无普通股票、没记过账时**不**抑制逐标的记账。
 */
class StockSourceFailureDedupTest {

    private val context = mockk<Context>(relaxed = true)
    private lateinit var manager: StockDataManager
    private var originalKey: String = ""

    /** 限流文案 = 源级失败且不可重试，跑得快；与既有 StockDataManagerTest 同口径 */
    private val sourceLevelFailure = IOException("API请求频率过高，请稍后再试")

    /** 符号级确定性失败：代码不在此源支持范围，不计入源级 */
    private val symbolLevelFailure = IOException("未获取到股票数据，请检查股票代码")

    @Before
    fun setUp() {
        // PriceCacheManager.init(context) 会读 filesDir；给真实临时目录，避免 null 路径
        every { context.filesDir } returns File(System.getProperty("java.io.tmpdir"))
        originalKey = EastmoneySkillApi.apiKey
        EastmoneySkillApi.apiKey = "" // 过滤妙想，只桩新浪/腾讯
        manager = StockDataManager(context)
        manager.resetForTesting()
    }

    @After
    fun tearDown() {
        EastmoneySkillApi.apiKey = originalKey
        SinaStockAPI.resetHttpClientForTesting()
        SinaStockAPI.invalidateCache()
        unmockkAll()
    }

    private fun stubSinaSourceFailure() {
        mockkObject(SinaStockAPI)
        mockkObject(TencentStockAPI)
        coEvery { SinaStockAPI.getStockData(any()) } returns Result.failure(sourceLevelFailure)
        // 腾讯给符号级失败：它不会贡献源级计数，保证断言只针对新浪
        coEvery { TencentStockAPI.getStockData(any()) } returns Result.failure(symbolLevelFailure)
    }

    private fun newScope(roundId: Int): RoundSourceFailureScope =
        RoundSourceFailureScope(roundId, ConcurrentHashMap.newKeySet())

    /** 一轮：批量 0 命中记一次账 → 登记进本轮上下文 → 逐标的回落 */
    private suspend fun runRound(roundId: Int): RoundSourceFailureScope {
        val scope = newScope(roundId)
        val reported = manager.reportBatchTotalMiss(
            ApiManager.API_SINA,
            listOf("sh600519"),
            roundId
        )
        assertTrue("批内含普通股票且整体 0 命中，必须记一次源级失败", reported)
        if (reported) scope.reportedSources.add(ApiManager.API_SINA)
        withContext(scope) {
            repeat(SourceCircuitBreaker.FAIL_THRESHOLD) { i ->
                manager.getStockData(
                    "sh60000$i",
                    forceRefresh = true,
                    networkAvailable = true
                )
            }
        }
        return scope
    }

    @Test
    fun `批量0命中后N个回落标的_同一轮只喂熔断一次`() = runBlocking {
        stubSinaSourceFailure()

        val scope = runRound(roundId = 1)

        assertTrue(scope.isAlreadyReported(ApiManager.API_SINA))
        assertEquals(
            "1 次批量 + FAIL_THRESHOLD 个回落标的，熔断器只应被喂 1 次",
            1,
            manager.failuresForTesting(ApiManager.API_SINA)
        )
        assertEquals(
            "轮次末统计同样只该显示 1 次源级失败",
            1,
            manager.sourceStatsSnapshot().first { it.source == ApiManager.API_SINA }.sourceLevelFailure
        )
        assertEquals(
            "单波绝不能误熔",
            SourcePhase.CLOSED,
            manager.sourcePhaseForTesting(ApiManager.API_SINA)
        )
    }

    @Test
    fun `无轮次上下文时_前台真实IO失败仍逐次记账并可达OPEN`() = runBlocking {
        stubSinaSourceFailure()

        // 不包 withContext(scope)：模拟前台单标的查价 / 导入回显等路径
        repeat(SourceCircuitBreaker.FAIL_THRESHOLD) { i ->
            manager.getStockData(
                "sh60000$i",
                forceRefresh = true,
                networkAvailable = true
            )
        }

        assertEquals(
            "前台真实 IO 失败必须照常累计，不能因为去重改造被吞掉",
            SourcePhase.OPEN,
            manager.sourcePhaseForTesting(ApiManager.API_SINA)
        )
    }

    @Test
    fun `持续多波真实故障_每轮只记一次仍能打到OPEN`() = runBlocking {
        stubSinaSourceFailure()
        val threshold = SourceCircuitBreaker.FAIL_THRESHOLD

        // 前 threshold-1 波：每波只记 1 次，仍 CLOSED（单波不误熔）
        repeat(threshold - 1) { wave -> runRound(roundId = wave + 1) }
        assertEquals(
            "连续多波但每波只记 1 次，未达阈值前不得熔禁",
            SourcePhase.CLOSED,
            manager.sourcePhaseForTesting(ApiManager.API_SINA)
        )
        assertEquals(
            threshold - 1,
            manager.failuresForTesting(ApiManager.API_SINA)
        )

        // 第 threshold 波：累计到阈值，真实持续故障仍必须 OPEN
        runRound(roundId = threshold)
        assertEquals(
            "真实持续多波故障必须仍能触发熔断 OPEN（去重不得把熔断器饿死）",
            SourcePhase.OPEN,
            manager.sourcePhaseForTesting(ApiManager.API_SINA)
        )
    }

    @Test
    fun `停牌与符号级失败仍不计数_即使带轮次上下文`() = runBlocking {
        mockkObject(SinaStockAPI)
        mockkObject(TencentStockAPI)
        // 新浪返回停牌、腾讯返回符号级：两者都不是源级故障
        coEvery { SinaStockAPI.getStockData(any()) } returns
            Result.failure(QuoteSuspendedException("sh600000"))
        coEvery { TencentStockAPI.getStockData(any()) } returns Result.failure(symbolLevelFailure)

        val scope = newScope(roundId = 7)
        scope.reportedSources.add(ApiManager.API_SINA) // 即便本源已被登记，也不该凭空多出计数
        withContext(scope) {
            repeat(SourceCircuitBreaker.FAIL_THRESHOLD) { i ->
                manager.getStockData(
                    "sh60000$i",
                    forceRefresh = true,
                    networkAvailable = true
                )
            }
        }

        assertEquals(0, manager.failuresForTesting(ApiManager.API_SINA))
        assertEquals(
            SourcePhase.CLOSED,
            manager.sourcePhaseForTesting(ApiManager.API_SINA)
        )
        assertEquals(
            "停牌/符号级失败不该产生任何源级失败统计（该源甚至可能一条统计都没有）",
            0,
            manager.sourceStatsSnapshot()
                .firstOrNull { it.source == ApiManager.API_SINA }
                ?.sourceLevelFailure ?: 0
        )
    }

    @Test
    fun `批内无普通股票时不记账_逐标的IO失败仍照常累计`() = runBlocking {
        stubSinaSourceFailure()

        val scope = newScope(roundId = 9)
        // 整批都是场内基金/REIT：0 命中是正常行为，不该登记「已记账」
        val reported = manager.reportBatchTotalMiss(
            ApiManager.API_SINA,
            listOf("sh508085", "sz160719"),
            roundId = 9
        )
        assertFalse("整批都是场内基金时 0 命中不能计源级失败", reported)
        if (reported) scope.reportedSources.add(ApiManager.API_SINA)
        assertFalse(scope.isAlreadyReported(ApiManager.API_SINA))

        withContext(scope) {
            repeat(SourceCircuitBreaker.FAIL_THRESHOLD) { i ->
                manager.getStockData(
                    "sh60000$i",
                    forceRefresh = true,
                    networkAvailable = true
                )
            }
        }

        assertEquals(
            "没有记过账就不能抑制逐标的的真实 IO 失败",
            SourcePhase.OPEN,
            manager.sourcePhaseForTesting(ApiManager.API_SINA)
        )
    }
}
