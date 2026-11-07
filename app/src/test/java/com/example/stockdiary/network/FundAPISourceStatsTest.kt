package com.example.stockdiary.network

import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
    fun `限流口径_514与429归类为限流`() {
        assertEquals("HTTP 514 限流", FundAPI.httpFailureReason(514))
        assertEquals("HTTP 429 限流", FundAPI.httpFailureReason(429))
        assertEquals("HTTP 500", FundAPI.httpFailureReason(500))
        assertEquals("HTTP 404", FundAPI.httpFailureReason(404))
    }
}
