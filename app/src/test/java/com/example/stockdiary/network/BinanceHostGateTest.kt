package com.example.stockdiary.network

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
 * [BinanceHostGate] 主机级熔断状态机回归。
 *
 * 钉的契约：连续 3 次网络级失败才熔断、熔断 10 分钟、冷却期内成功不解封、
 * 成功后计数清零、并发下只熔断一次且计数不越界。
 * 这条契约一旦退化，症状是「币安连不上时每轮同步白烧 20s×N 个标的，
 * 还弹同步失败通知」——正是本类要消除的场景。
 *
 * BinanceHostGate 是进程内 object：`@Before` 复位保证本类用例互不污染，
 * 也防止熔断状态漏给别的测试类。
 */
class BinanceHostGateTest {

    /** 固定基准时间（epoch ms），与真实时钟解耦，时间断言才稳定 */
    private val base = 1_700_000_000_000L

    @Before
    fun resetGate() {
        BinanceHostGate.resetForTesting()
    }

    @After
    fun resetGateAfter() {
        // 与 @Before 对称：用例跑完留下的熔断状态不能漏给后续测试类
        BinanceHostGate.resetForTesting()
    }

    @Test
    fun `连续 2 次失败未达阈值，不熔断`() {
        BinanceHostGate.onNetworkFailure(base)
        BinanceHostGate.onNetworkFailure(base)
        assertFalse(BinanceHostGate.isBlocked(base))
        assertEquals(2, BinanceHostGate.consecutiveFailuresForTesting())
    }

    @Test
    fun `第 3 次失败达到阈值，熔断且 isBlocked 为 true`() {
        repeat(BinanceHostGate.FAIL_THRESHOLD) { BinanceHostGate.onNetworkFailure(base) }
        assertTrue(BinanceHostGate.isBlocked(base))
        assertEquals(1, BinanceHostGate.blockCountForTesting())
        // 触发熔断时计数清零：冷却期内的迟来失败重新累计
        assertEquals(0, BinanceHostGate.consecutiveFailuresForTesting())
    }

    @Test
    fun `冷却期内 isBlocked 一直为 true，越过 blockedUntil 后恢复 false`() {
        repeat(BinanceHostGate.FAIL_THRESHOLD) { BinanceHostGate.onNetworkFailure(base) }
        assertTrue(BinanceHostGate.isBlocked(base))
        assertTrue(BinanceHostGate.isBlocked(base + BinanceHostGate.COOLDOWN_MS - 1))
        // 边界：now == blockedUntil 时已解封（isBlocked 是 now < blockedUntil）
        assertFalse(BinanceHostGate.isBlocked(base + BinanceHostGate.COOLDOWN_MS))
        assertFalse(BinanceHostGate.isBlocked(base + BinanceHostGate.COOLDOWN_MS + 1))
    }

    @Test
    fun `熔断期间成功后计数清零，但不提前解封`() {
        repeat(BinanceHostGate.FAIL_THRESHOLD) { BinanceHostGate.onNetworkFailure(base) }
        // 冷却期内又来了两次失败（并发迟来）：计数不为 0
        BinanceHostGate.onNetworkFailure(base + 1_000)
        BinanceHostGate.onNetworkFailure(base + 2_000)
        // 一次成功把计数清零
        BinanceHostGate.onSuccess()
        assertEquals(0, BinanceHostGate.consecutiveFailuresForTesting())
        // 但熔断不因此提前解封：成功不清 blockedUntil，避免抖动解封
        assertTrue(BinanceHostGate.isBlocked(base + 3_000))
        assertTrue(BinanceHostGate.isBlocked(base + BinanceHostGate.COOLDOWN_MS - 1))
    }

    @Test
    fun `onSuccess 单独调用能把计数清零，下次需重新累计 3 次才熔断`() {
        BinanceHostGate.onNetworkFailure(base)
        BinanceHostGate.onNetworkFailure(base)
        BinanceHostGate.onSuccess()
        assertEquals(0, BinanceHostGate.consecutiveFailuresForTesting())
        // 若计数没被清零，这里 2+2=4 早已越过阈值；清零后只到 2，不熔断
        BinanceHostGate.onNetworkFailure(base)
        BinanceHostGate.onNetworkFailure(base)
        assertFalse(BinanceHostGate.isBlocked(base))
        // 再失败一次才重新熔断
        BinanceHostGate.onNetworkFailure(base)
        assertTrue(BinanceHostGate.isBlocked(base))
    }

    @Test
    fun `冷却过期后可重新武装，再次累计 3 次会再次熔断`() {
        repeat(BinanceHostGate.FAIL_THRESHOLD) { BinanceHostGate.onNetworkFailure(base) }
        assertTrue(BinanceHostGate.isBlocked(base))
        // 冷却过去、主机恢复：这段时间的成功把计数清零
        val afterCooldown = base + BinanceHostGate.COOLDOWN_MS + 1
        BinanceHostGate.onSuccess()
        assertFalse(BinanceHostGate.isBlocked(afterCooldown))
        // 主机又挂了：重新累计 3 次，再次熔断，截止时间按新的 now 顺延
        repeat(BinanceHostGate.FAIL_THRESHOLD) { BinanceHostGate.onNetworkFailure(afterCooldown) }
        assertTrue(BinanceHostGate.isBlocked(afterCooldown))
        assertEquals(afterCooldown + BinanceHostGate.COOLDOWN_MS, BinanceHostGate.blockedUntilForTesting())
        assertEquals(2, BinanceHostGate.blockCountForTesting())
    }

    @Test
    fun `并发 onNetworkFailure 只熔断一次且计数不越界`() {
        val threads = 8
        val perThread = 5 // 共 40 次失败，远超阈值
        val startGate = CountDownLatch(1)
        val doneGate = CountDownLatch(threads)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            repeat(threads) {
                pool.execute {
                    startGate.await()
                    try {
                        repeat(perThread) { BinanceHostGate.onNetworkFailure(base) }
                    } finally {
                        doneGate.countDown()
                    }
                }
            }
            // 统一放闸，把并发压到最紧
            startGate.countDown()
            assertTrue("并发任务未在 10s 内完成", doneGate.await(10, TimeUnit.SECONDS))
        } finally {
            pool.shutdown()
        }

        // 只熔断一次：blockedUntil 只被 CAS 设置一次，且就是 base + COOLDOWN_MS
        assertTrue(BinanceHostGate.isBlocked(base))
        assertEquals(base + BinanceHostGate.COOLDOWN_MS, BinanceHostGate.blockedUntilForTesting())
        assertEquals(1, BinanceHostGate.blockCountForTesting())
        // 计数不越界：并发越界的线程只把计数压回阈值以下，不会虚涨
        assertTrue(
            "计数越界: ${BinanceHostGate.consecutiveFailuresForTesting()}",
            BinanceHostGate.consecutiveFailuresForTesting() in 0 until BinanceHostGate.FAIL_THRESHOLD
        )
    }
}
