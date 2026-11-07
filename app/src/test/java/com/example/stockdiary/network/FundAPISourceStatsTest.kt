package com.example.stockdiary.network

import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import com.example.stockdiary.data.price.RoundSourceFailureScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 基金净值两源（轻量 / legacy）的**轮次统计**回归。
 *
 * 只钉统计口径，不改回退与熔断行为：
 * - 成功一次只记一次成功，没走过的源不出现在快照里（不制造噪音）；
 * - 源级失败带原因与耗时，legacy 兜底的成功数单独记；
 * - 轻量源整源熔断时本轮没有真实请求，只能用 `degradedReason` 说明，
 *   绝不能虚增一次「源级失败」。
 */
class FundAPISourceStatsTest {

    @Before
    fun setUp() {
        FundAPI.resetNavSourcesForTest()
    }

    @After
    fun tearDown() {
        FundAPI.resetNavSourcesForTest()
    }

    private fun fundInfo(code: String, name: String) = FundAPI.FundInfo(
        name = name,
        code = code,
        price = 1.0,
        netWorth = 1.0,
        changePercent = null,
        date = "2026-11-06"
    )

    @Test
    fun `轻量源成功_只记录轻量源的成败与耗时`() = runBlocking {
        FundAPI.lightweightNavSource = {
            delay(5)
            fundInfo(it, "轻量源基金")
        }
        FundAPI.legacyNavSource = { error("轻量源成功时不应回退 legacy") }

        val info = FundAPI.fetchFundInfo("110022")
        assertNotNull(info)

        val stats = FundAPI.sourceStatsSnapshot()
        assertEquals("没走过的源不应出现在快照里", 1, stats.size)
        val light = stats.single()
        assertEquals("基金-轻量源", light.source)
        assertEquals("fundmobapi.eastmoney.com", light.host)
        assertEquals(1, light.success)
        assertEquals(0, light.sourceLevelFailure)
        assertEquals(0, light.symbolLevelFailure)
        assertTrue("耗时必须被记录", light.durationMs >= 5)
        assertNull(light.degradedReason)
    }

    @Test
    fun `轻量源失败回退legacy_两源分别记账且失败带原因`() = runBlocking {
        FundAPI.lightweightNavSource = {
            delay(3)
            throw IOException("连接超时")
        }
        FundAPI.legacyNavSource = {
            delay(4)
            fundInfo(it, "legacy源基金")
        }

        val info = FundAPI.fetchFundInfo("110022")
        assertNotNull(info)

        val stats = FundAPI.sourceStatsSnapshot()
        assertEquals(listOf("基金-轻量源", "基金-legacy源"), stats.map { it.source })

        val light = stats[0]
        assertEquals(0, light.success)
        assertEquals(1, light.sourceLevelFailure)
        assertTrue("失败原因应可读: ${light.lastFailureReason}", light.lastFailureReason!!.startsWith("网络异常"))
        assertTrue(light.durationMs >= 3)

        val legacy = stats[1]
        assertEquals("legacy 兜底成功必须单独记", 1, legacy.success)
        assertEquals(0, legacy.sourceLevelFailure)
        assertEquals("fund.eastmoney.com", legacy.host)
        assertTrue(legacy.durationMs >= 4)
    }

    @Test
    fun `轻量源熔断后本轮只走legacy_用降级原因而非虚增失败`() = runBlocking {
        FundAPI.lightweightNavSource = { null }
        FundAPI.legacyNavSource = { fundInfo(it, "legacy源基金") }

        // 连续 3 次拿不到 → 轻量源整源熔断（既有行为，顺便确认没被统计改动影响）
        repeat(3) { index -> FundAPI.fetchFundInfo("00000$index") }
        assertTrue(FundAPI.isLightweightSourceDisabled())

        // 新一轮：没有真实轻量请求，只应留下降级原因
        FundAPI.resetSourceStats()
        FundAPI.fetchFundInfo("000004")

        val stats = FundAPI.sourceStatsSnapshot()
        val light = stats.first { it.source == "基金-轻量源" }
        assertEquals(0, light.success)
        assertEquals("熔断后没有请求，不能虚增源级失败", 0, light.sourceLevelFailure)
        assertEquals("连续失败已熔断，本轮直接走 legacy", light.degradedReason)
        assertEquals(1, stats.first { it.source == "基金-legacy源" }.success)
    }

    @Test
    fun `并发轮次隔离_各轮统计互不串且reset只清本轮`() = runBlocking {
        // 回归：统计从前是进程级单份、每轮开头 reset()，手动轮与后台 Worker 并发时
        // 后开的一轮会把先开那轮的计数清空。现在按 roundId 各一份，互不影响。
        FundAPI.discardSourceStats(1)
        FundAPI.discardSourceStats(2)
        FundAPI.lightweightNavSource = { fundInfo(it, "轻量源基金") }
        FundAPI.legacyNavSource = { error("轻量源成功时不应回退 legacy") }

        withContext(RoundSourceFailureScope(1)) {
            repeat(2) { FundAPI.fetchFundInfo("110022") }
        }
        withContext(RoundSourceFailureScope(2)) {
            repeat(5) { FundAPI.fetchFundInfo("110023") }
        }

        val round1 = FundAPI.sourceStatsSnapshot(1).single()
        val round2 = FundAPI.sourceStatsSnapshot(2).single()
        assertEquals("round 1 只统计自己的 2 次成功", 2, round1.success)
        assertEquals("round 2 只统计自己的 5 次成功", 5, round2.success)

        // reset 只清目标轮次：并发中的另一轮必须完整保留
        FundAPI.resetSourceStats(1)
        assertTrue("reset(1) 后 round 1 应为空", FundAPI.sourceStatsSnapshot(1).isEmpty())
        assertEquals(
            "reset(1) 不能清空并发中的 round 2",
            5,
            FundAPI.sourceStatsSnapshot(2).single().success
        )

        FundAPI.discardSourceStats(1)
        FundAPI.discardSourceStats(2)
    }

    @Test
    fun `限流口径_514与429归类为限流`() {
        assertEquals("HTTP 514 限流", FundAPI.httpFailureReason(514))
        assertEquals("HTTP 429 限流", FundAPI.httpFailureReason(429))
        assertEquals("HTTP 500", FundAPI.httpFailureReason(500))
        assertEquals("HTTP 404", FundAPI.httpFailureReason(404))
    }

    // ---------- 熔断粒度：符号级不连坐 / 时间窗 / 限流去重 ----------

    @Test
    fun `符号级缺口不喂源级熔断`() = runBlocking {
        // 回归：200 但解析不出净值（或没有该代码）是符号级缺口。若跟着 noteLightweightFailure
        // 一起累计，个别坏代码就能把整源熔断（与股票侧「停牌不喂熔断」违背）。
        FundAPI.lightweightNavSource = {
            kotlinx.coroutines.currentCoroutineContext()[NavFetchTrace]?.failureKind =
                NavFailureKind.SYMBOL
            null
        }
        FundAPI.legacyNavSource = { fundInfo(it, "legacy源基金") }

        repeat(6) { index -> FundAPI.fetchFundInfo("00000$index") }

        assertFalse("符号级缺口不能熔断整源", FundAPI.isLightweightSourceDisabled())
        assertEquals("符号级缺口不计入连续失败", 0, FundAPI.lightweightFailureCount())
    }

    @Test
    fun `连续失败超过时间窗后重新计数`() {
        var clock = 1_000L
        val window = ConsecutiveFailureWindow(
            threshold = 3,
            windowMs = 60_000L,
            now = { clock }
        )

        assertEquals(1, window.recordFailure())
        clock += 10_000L
        assertEquals(2, window.recordFailure())
        assertFalse(window.thresholdReached())

        // 间隔超过窗口：说明不是同一轮持续故障，从 1 重新累计
        clock += 61_000L
        assertEquals(1, window.recordFailure())
        assertFalse(window.thresholdReached())

        clock += 1_000L
        assertEquals(2, window.recordFailure())
        clock += 1_000L
        assertEquals(3, window.recordFailure())
        assertTrue(window.thresholdReached())
    }

    @Test
    fun `限流期内的重复失败不计入熔断`() = runBlocking {
        // 回归：4 路并发同时撞 514 时，冷却置位前其余请求各自记一次失败 → 直接「3 连败」熔断。
        // 现在只有真正开启冷却窗口的那一路计数。
        FundAPI.lightweightNavSource = {
            val trace = kotlinx.coroutines.currentCoroutineContext()[NavFetchTrace]!!
            trace.failureKind = NavFailureKind.RATE_LIMITED
            trace.countsTowardBreaker = FundAPI.noteRateLimit(FundAPI.lightweightHostCooldown, 514)
            null
        }
        FundAPI.legacyNavSource = { fundInfo(it, "legacy源基金") }

        repeat(4) { index -> FundAPI.fetchFundInfo("00000$index") }

        assertFalse("一次限流不能被记成 3 连败", FundAPI.isLightweightSourceDisabled())
        assertEquals("同一次限流只计一次", 1, FundAPI.lightweightFailureCount())
    }

    @Test
    fun `同一冷却窗口内的重复限流不重复开启`() {
        val cooldown = HostCooldown(windowMs = 60_000L)
        assertTrue("第一路真正开启冷却", FundAPI.noteRateLimit(cooldown, 514))
        assertFalse("并发其余请求不重复计数", FundAPI.noteRateLimit(cooldown, 514))
        assertFalse("非限流状态码不开启冷却", FundAPI.noteRateLimit(cooldown, 500))
    }
}
