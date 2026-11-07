package com.example.stockdiary.network

import java.io.IOException
import java.net.UnknownHostException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 基金净值链路的**主机级 60s 短退避**回归。
 *
 * 口径与分红链路一致：只对 514/429 限流和 DNS 不可达冷却；冷却期内直接跳过请求，
 * 不发网络、不算源级失败，原因只写进本轮源统计的 degradedReason。
 * 轻量源与 legacy 主机各自独立，一个冷却不影响另一个。
 */
class FundAPIHostBackoffTest {

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
    fun `HostCooldown窗口按60秒到期`() {
        val cooldown = HostCooldown(windowMs = 60_000L)
        cooldown.start("HTTP 429 限流", now = 1_000L)

        assertTrue(cooldown.isCooling(now = 1_000L))
        assertTrue("59 秒时仍在冷却", cooldown.isCooling(now = 60_999L))
        assertFalse("满 60 秒自动恢复", cooldown.isCooling(now = 61_000L))
        assertEquals("HTTP 429 限流（60s 退避中），本轮跳过", cooldown.degradedReason())

        cooldown.reset()
        assertFalse(cooldown.isCooling(now = 1_000L))
    }

    @Test
    fun `514限流让轻量主机冷却_冷却期内跳过请求且原因可见`() = runBlocking {
        var lightweightCalls = 0
        FundAPI.lightweightNavSource = {
            lightweightCalls++
            fundInfo(it, "轻量源基金")
        }
        FundAPI.legacyNavSource = { fundInfo(it, "legacy源基金") }

        FundAPI.noteRateLimit(FundAPI.lightweightHostCooldown, 514)
        assertTrue(FundAPI.lightweightHostCooldown.isCooling())

        val info = FundAPI.fetchFundInfo("110022")
        assertNotNull("轻量跳过时应回退 legacy 并拿到净值", info)
        assertEquals("冷却期内不应再请求轻量源", 0, lightweightCalls)

        val stats = FundAPI.sourceStatsSnapshot()
        val light = stats.first { it.source == "基金-轻量源" }
        assertEquals("跳过不是失败，不虚增源级失败", 0, light.sourceLevelFailure)
        assertEquals("退避跳过要计入 skipped（跳过≠失败）", 1, light.skipped)
        assertTrue(light.skipReasons.values.sum() == 1)
        assertTrue(light.degradedReason!!.contains("HTTP 514 限流"))
        assertTrue(light.degradedReason!!.contains("60s 退避中"))
        assertEquals(1, stats.first { it.source == "基金-legacy源" }.success)
    }

    @Test
    fun `DNS不可达让该主机冷却_下一轮直接跳过`() = runBlocking {
        var lightweightCalls = 0
        FundAPI.lightweightNavSource = {
            lightweightCalls++
            throw UnknownHostException("fundmobapi.eastmoney.com")
        }
        FundAPI.legacyNavSource = { fundInfo(it, "legacy源基金") }

        assertNotNull(FundAPI.fetchFundInfo("110022"))
        assertEquals(1, lightweightCalls)
        assertTrue("DNS 不可达应触发该主机冷却", FundAPI.lightweightHostCooldown.isCooling())
        assertFalse("legacy 主机不应被牵连", FundAPI.legacyHostCooldown.isCooling())

        // 新一轮：轻量主机仍在冷却，不应发请求
        FundAPI.resetSourceStats()
        FundAPI.fetchFundInfo("110023")
        assertEquals("冷却期内不应再打轻量源", 1, lightweightCalls)

        val light = FundAPI.sourceStatsSnapshot().first { it.source == "基金-轻量源" }
        assertEquals(0, light.sourceLevelFailure)
        assertTrue(light.degradedReason!!.contains("主机不可达（DNS 解析失败）"))
    }

    @Test
    fun `普通网络异常不进入主机退避`() = runBlocking {
        FundAPI.lightweightNavSource = { throw IOException("timeout") }
        FundAPI.legacyNavSource = { fundInfo(it, "legacy源基金") }

        FundAPI.fetchFundInfo("110022")

        assertFalse("非 DNS 异常不触发 60s 冷却", FundAPI.lightweightHostCooldown.isCooling())
        // 普通异常仍按原口径计一次源级失败
        val light = FundAPI.sourceStatsSnapshot().first { it.source == "基金-轻量源" }
        assertEquals(1, light.sourceLevelFailure)
    }

    @Test
    fun `轻量主机冷却不影响legacy_legacy不可达只冷却自己`() = runBlocking {
        FundAPI.noteRateLimit(FundAPI.lightweightHostCooldown, 429)
        assertTrue(FundAPI.lightweightHostCooldown.isCooling())
        assertFalse(FundAPI.legacyHostCooldown.isCooling())

        var legacyCalls = 0
        FundAPI.legacyNavSource = {
            legacyCalls++
            throw UnknownHostException("fund.eastmoney.com")
        }

        assertNull("两个主机都不可用时应返回空，交给上层按失败处理", FundAPI.fetchFundInfo("110022"))
        assertEquals(1, legacyCalls)
        assertTrue(FundAPI.legacyHostCooldown.isCooling())

        // 下一轮两个主机都在冷却：一个请求都不发
        FundAPI.resetSourceStats()
        FundAPI.fetchFundInfo("110023")
        assertEquals("legacy 冷却期内不应再发请求", 1, legacyCalls)

        val stats = FundAPI.sourceStatsSnapshot()
        assertEquals(2, stats.size)
        assertTrue(stats.all { it.degradedReason != null })
        assertTrue("跳过的请求都不计失败", stats.all { it.sourceLevelFailure == 0 })
    }
}
