package com.example.stockdiary.data.price

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [SourceCircuitBreaker] 数据源三态熔断器状态机回归。
 *
 * 钉的契约（与 BinanceHostGate 同构，阈值/冷却按数据源侧取值）：
 * - 单波 3 连失败（=旧阈值=同步并发度）不再误禁；
 * - 累计 5 次源级失败才禁用、进入 OPEN；
 * - 冷却期内 tryAcquireProbe 恒拒绝；
 * - 冷却结束只放行 1 个半开探测、第 2 个仍拒；
 * - 探测成功闭路、失败立即重开冷却；
 * - 卡死的探测过期后自愈重新武装；
 * - 并发下只禁一次。
 */
class SourceCircuitBreakerTest {

    private val base = 1_700_000_000_000L
    private val afterCooldown: Long get() = base + SourceCircuitBreaker.COOLDOWN_MS
    private lateinit var breaker: SourceCircuitBreaker

    @Before
    fun setUp() {
        breaker = SourceCircuitBreaker()
    }

    @After
    fun tearDown() {
        breaker.resetForTesting()
    }

    private fun tripOpen(at: Long = base) {
        repeat(SourceCircuitBreaker.FAIL_THRESHOLD) { breaker.onFailure(at) }
    }

    @Test
    fun `连续 4 次失败未达阈值5，不熔禁`() {
        repeat(SourceCircuitBreaker.FAIL_THRESHOLD - 1) { breaker.onFailure(base) }
        assertEquals(SourcePhase.CLOSED, breaker.phaseForTesting())
        assertTrue(breaker.tryAcquireProbe(base))
        assertEquals(SourceCircuitBreaker.FAIL_THRESHOLD - 1, breaker.failuresForTesting())
    }

    @Test
    fun `单波 3 连失败不熔禁_钉死误伤修复`() {
        // 3 = 旧阈值 = 同步并发度：Part B 把挂死变 20s 失败后，单波 3 个并发同时超时即误禁。
        repeat(3) { breaker.onFailure(base) }
        assertEquals(SourcePhase.CLOSED, breaker.phaseForTesting())
        assertTrue(breaker.tryAcquireProbe(base))
        assertFalse(breaker.isUnavailable())
    }

    @Test
    fun `第 5 次失败达到阈值，熔禁进入 OPEN`() {
        tripOpen()
        assertEquals(SourcePhase.OPEN, breaker.phaseForTesting())
        assertTrue(breaker.isUnavailable())
        assertFalse(breaker.tryAcquireProbe(base))
        // 触发时计数清零
        assertEquals(0, breaker.failuresForTesting())
        assertEquals(base + SourceCircuitBreaker.COOLDOWN_MS, breaker.blockedUntilForTesting())
    }

    @Test
    fun `冷却期内 tryAcquireProbe 恒拒绝`() {
        tripOpen()
        assertFalse(breaker.tryAcquireProbe(base))
        assertFalse(breaker.tryAcquireProbe(base + SourceCircuitBreaker.COOLDOWN_MS - 1))
        assertTrue(breaker.isUnavailable())
    }

    @Test
    fun `冷却结束恰好 1 个探测放行_第 2 个仍拒`() {
        tripOpen()
        assertTrue("冷却结束后应放行恰好一个探测", breaker.tryAcquireProbe(afterCooldown))
        assertEquals(SourcePhase.HALF_OPEN, breaker.phaseForTesting())
        assertFalse(breaker.tryAcquireProbe(afterCooldown))
        assertFalse(breaker.tryAcquireProbe(afterCooldown + 1))
        assertTrue(breaker.isUnavailable())
    }

    @Test
    fun `探测成功闭路_后续全放行且计数清零`() {
        tripOpen()
        assertTrue(breaker.tryAcquireProbe(afterCooldown)) // 成为探测
        breaker.onSuccess() // 探测成功
        assertEquals(SourcePhase.CLOSED, breaker.phaseForTesting())
        assertEquals(0, breaker.failuresForTesting())
        assertEquals(0L, breaker.blockedUntilForTesting())
        assertTrue(breaker.tryAcquireProbe(afterCooldown))
        assertTrue(breaker.tryAcquireProbe(afterCooldown + 1))
        assertFalse(breaker.isUnavailable())
    }

    @Test
    fun `探测失败立即重开冷却_不等阈值`() {
        tripOpen()
        assertTrue(breaker.tryAcquireProbe(afterCooldown)) // 成为探测
        breaker.onFailure(afterCooldown) // 探测失败
        assertEquals(SourcePhase.OPEN, breaker.phaseForTesting())
        assertEquals(afterCooldown + SourceCircuitBreaker.COOLDOWN_MS, breaker.blockedUntilForTesting())
        assertFalse(breaker.tryAcquireProbe(afterCooldown))
    }

    @Test
    fun `OPEN 期间迟到成功只清零计数不解封`() {
        tripOpen()
        breaker.onFailure(base + 1_000) // 冷却期内迟来失败
        breaker.onSuccess() // 迟到成功：清计数，不解封
        assertEquals(0, breaker.failuresForTesting())
        assertEquals(SourcePhase.OPEN, breaker.phaseForTesting())
        assertFalse(breaker.tryAcquireProbe(base + 2_000))
    }

    @Test
    fun `探测卡死自愈_超过 PROBE_TIMEOUT 重新武装`() {
        tripOpen()
        assertTrue(breaker.tryAcquireProbe(afterCooldown)) // 探测，probeUntil = afterCooldown + PROBE_TIMEOUT
        assertFalse(breaker.tryAcquireProbe(afterCooldown + SourceCircuitBreaker.PROBE_TIMEOUT_MS - 1))
        assertTrue(breaker.tryAcquireProbe(afterCooldown + SourceCircuitBreaker.PROBE_TIMEOUT_MS))
        assertEquals(SourcePhase.HALF_OPEN, breaker.phaseForTesting())
        assertFalse(breaker.tryAcquireProbe(afterCooldown + SourceCircuitBreaker.PROBE_TIMEOUT_MS))
    }

    @Test
    fun `并发 onFailure 只熔禁一次`() {
        val threads = 8
        val perThread = 5 // 共 40 次，远超阈值
        val startGate = CountDownLatch(1)
        val doneGate = CountDownLatch(threads)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            repeat(threads) {
                pool.execute {
                    startGate.await()
                    try {
                        repeat(perThread) { breaker.onFailure(base) }
                    } finally {
                        doneGate.countDown()
                    }
                }
            }
            startGate.countDown()
            assertTrue(doneGate.await(10, TimeUnit.SECONDS))
        } finally {
            pool.shutdown()
        }
        assertEquals(SourcePhase.OPEN, breaker.phaseForTesting())
        assertEquals(base + SourceCircuitBreaker.COOLDOWN_MS, breaker.blockedUntilForTesting())
        assertTrue(breaker.failuresForTesting() in 0 until SourceCircuitBreaker.FAIL_THRESHOLD)
    }
}
