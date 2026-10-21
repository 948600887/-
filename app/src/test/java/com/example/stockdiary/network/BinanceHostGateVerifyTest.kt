package com.example.stockdiary.network

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * QA 独立复验（fresh eyes）：[BinanceHostGate] 三态断路器的边界补测。
 *
 * 工程师的 [BinanceHostGateTest] 已覆盖状态机主干（阈值/冷却/半开/自愈/并发熔断），
 * 但有三处只有顺序调用或没覆盖，各对一个真实风险：
 *
 *  1. **真·并发下「恰好一个探测」**：工程师那条同名用例是**顺序**调两次 shouldAllow，
 *     CAS 的互斥性并没有被真并发压过。这里 8 线程统一放闸，钉死只放行 1 个。
 *  2. **isUnavailable 纯读不耗探测名额**：UI 每轮重组都会读它；若哪天有人把 UI 改成
 *     调 shouldAllow（带副作用），探测名额会被悄悄吃掉、真请求被拒——这条测试会红。
 *  3. **误调 shouldAllow 的危害有界且自愈**：副作用设计确实挡不住误调（误调者会成为
 *     探测），但代价仅限一个探测窗口，PROBE_TIMEOUT 自愈后请求仍能通过——不会永久
 *     冻结。把危害边界钉死，防止未来「加固」时误以为必须永久拒绝。
 */
class BinanceHostGateVerifyTest {

    private val base = 1_700_000_000_000L
    private val afterCooldown: Long get() = base + BinanceHostGate.COOLDOWN_MS

    @Before
    fun resetGate() {
        BinanceHostGate.resetForTesting()
    }

    @After
    fun resetGateAfter() {
        BinanceHostGate.resetForTesting()
    }

    private fun tripOpen(at: Long = base) {
        repeat(BinanceHostGate.FAIL_THRESHOLD) { BinanceHostGate.onNetworkFailure(at) }
    }

    /** 8 线程真并发：冷却结束那一刻只允许一个请求成为半开探测（CAS 互斥） */
    @Test
    fun `真并发下冷却结束只放行一个探测`() {
        tripOpen()
        val threads = 8
        val startGate = CountDownLatch(1)
        val doneGate = CountDownLatch(threads)
        val allowed = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            repeat(threads) {
                pool.execute {
                    startGate.await()
                    try {
                        if (BinanceHostGate.shouldAllow(afterCooldown)) allowed.incrementAndGet()
                    } finally {
                        doneGate.countDown()
                    }
                }
            }
            startGate.countDown()
            assertTrue("并发任务未在 10s 内完成", doneGate.await(10, TimeUnit.SECONDS))
        } finally {
            pool.shutdown()
        }

        assertEquals("冷却结束的并发请求里必须只放行一个探测", 1, allowed.get())
        assertEquals("唯一的探测把相位带到 HALF_OPEN", Phase.HALF_OPEN, BinanceHostGate.phaseForTesting())
        // 半开期内继续拒绝，不烧时间
        assertFalse(BinanceHostGate.shouldAllow(afterCooldown + 1))
    }

    /** UI 纯读契约：反复读 isUnavailable 不消耗探测名额（防 UI 误用带副作用的闸门） */
    @Test
    fun `isUnavailable 纯读_不消耗半开探测名额`() {
        tripOpen()
        // 模拟 UI 每轮重组反复读：纯读，不得驱动状态机
        repeat(5) { assertTrue(BinanceHostGate.isUnavailable()) }
        assertEquals("纯读不该改相位", Phase.OPEN, BinanceHostGate.phaseForTesting())

        // 冷却结束：探测名额仍在，恰好放行一个
        assertTrue(BinanceHostGate.shouldAllow(afterCooldown))
        assertFalse("探测名额没被 UI 的纯读消耗掉", BinanceHostGate.shouldAllow(afterCooldown))
    }

    /**
     * 误调 shouldAllow（非请求路径）确实会消耗探测名额——但危害有界：
     * 真请求被拒最多一个 PROBE_TIMEOUT 窗口，自愈后仍能通过，不会永久冻结。
     * 这条把「副作用设计的危害边界」钉死，兼作 isUnavailable/shouldAllow 职责分离的反面教材。
     */
    @Test
    fun `误调 shouldAllow 消耗探测名额_但超 PROBE_TIMEOUT 自愈不冻结`() {
        tripOpen()
        // 一个「误调」（比如未来的 UI 代码图省事调了 shouldAllow）先成为探测
        assertTrue(BinanceHostGate.shouldAllow(afterCooldown))
        // 真正的请求反而被拒——误调的代价
        assertFalse(BinanceHostGate.shouldAllow(afterCooldown))
        // 但有界：探测卡死（误调者不会真发请求）超 PROBE_TIMEOUT 后自愈重新武装
        assertTrue(
            "自愈应让后续请求重新获得探测资格，不永久冻结",
            BinanceHostGate.shouldAllow(afterCooldown + BinanceHostGate.PROBE_TIMEOUT_MS)
        )
        // 重新武装后依旧只有一个探测
        assertFalse(BinanceHostGate.shouldAllow(afterCooldown + BinanceHostGate.PROBE_TIMEOUT_MS))
    }

    /** 自愈重武装后的新探测同样受 CAS 保护：真并发下仍只放行一个 */
    @Test
    fun `自愈重武装后_真并发仍只放行一个探测`() {
        tripOpen()
        assertTrue(BinanceHostGate.shouldAllow(afterCooldown)) // 第一个探测（随后卡死）
        val rearmAt = afterCooldown + BinanceHostGate.PROBE_TIMEOUT_MS

        val threads = 8
        val startGate = CountDownLatch(1)
        val doneGate = CountDownLatch(threads)
        val allowed = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            repeat(threads) {
                pool.execute {
                    startGate.await()
                    try {
                        if (BinanceHostGate.shouldAllow(rearmAt)) allowed.incrementAndGet()
                    } finally {
                        doneGate.countDown()
                    }
                }
            }
            startGate.countDown()
            assertTrue("并发任务未在 10s 内完成", doneGate.await(10, TimeUnit.SECONDS))
        } finally {
            pool.shutdown()
        }

        assertEquals("自愈重武装后的并发请求里仍只放行一个探测", 1, allowed.get())
        assertEquals(Phase.HALF_OPEN, BinanceHostGate.phaseForTesting())
    }
}
