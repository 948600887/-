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
 * [BinanceHostGate] 三态熔断器状态机回归。
 *
 * 钉的契约：
 * - 单波抖动（3 连失败，=旧阈值=同步并发度）不再误熔；
 * - 累计 5 次才熔断、blockCount +1；
 * - 冷却期内 tryAcquireProbe 恒拒绝；
 * - 冷却结束只放行 1 个半开探测、并发第 2 个仍拒；
 * - 探测成功闭路、后续全放行、计数清零、blockCount 不涨；
 * - 探测失败立即重开冷却（不等计数到阈值）；
 * - OPEN 期迟到成功不解封；成功后计数清零；
 * - 并发下只熔断一次且计数不越界；
 * - 卡死的探测过期后自愈重新武装。
 *
 * 契约一旦退化，症状是两个极端：「币安连不上时每轮同步白烧 20s×N 个标的、还弹同步失败通知」，
 * 或「单波抖动就误熔半个组合 10 分钟」——正是本轮要消除的。
 *
 * BinanceHostGate 是进程内 object：`@Before`/`@After` 复位保证本类用例互不污染，
 * 也防止熔断状态漏给别的测试类。
 */
class BinanceHostGateTest {

    /** 固定基准时间（epoch ms），与真实时钟解耦，时间断言才稳定 */
    private val base = 1_700_000_000_000L

    /** 冷却结束的那一刻（now == blockedUntil 即已解封，可竞争探测） */
    private val afterCooldown: Long get() = base + BinanceHostGate.COOLDOWN_MS

    @Before
    fun resetGate() {
        BinanceHostGate.resetForTesting()
    }

    @After
    fun resetGateAfter() {
        // 与 @Before 对称：用例跑完留下的熔断状态不能漏给后续测试类
        BinanceHostGate.resetForTesting()
    }

    /** 连打 FAIL_THRESHOLD 次失败，把闸门打到 OPEN */
    private fun tripOpen(at: Long = base) {
        repeat(BinanceHostGate.FAIL_THRESHOLD) { BinanceHostGate.onNetworkFailure(at) }
    }

    // ---------- 阈值：误伤修复 ----------

    @Test
    fun `连续 4 次失败未达阈值5，不熔断`() {
        repeat(BinanceHostGate.FAIL_THRESHOLD - 1) { BinanceHostGate.onNetworkFailure(base) }
        assertEquals(Phase.CLOSED, BinanceHostGate.phaseForTesting())
        // CLOSED 仍放行
        assertTrue(BinanceHostGate.tryAcquireProbe(base))
        assertEquals(BinanceHostGate.FAIL_THRESHOLD - 1, BinanceHostGate.consecutiveFailuresForTesting())
        assertEquals(0, BinanceHostGate.blockCountForTesting())
    }

    @Test
    fun `单波 3 连失败不熔断_钉死误伤修复`() {
        // 3 = 旧阈值 = 同步并发度：单波抖动（3 个在途同时挂满 20s）旧实现会误熔半个组合。
        // 新阈值 5，3 连失败必须仍在 CLOSED、后续请求照常放行、不熔断。
        repeat(3) { BinanceHostGate.onNetworkFailure(base) }
        assertEquals(Phase.CLOSED, BinanceHostGate.phaseForTesting())
        assertTrue(BinanceHostGate.tryAcquireProbe(base))
        assertFalse(BinanceHostGate.isUnavailable())
        assertEquals(0, BinanceHostGate.blockCountForTesting())
    }

    @Test
    fun `第 5 次失败达到阈值，熔断且 blockCount 加一`() {
        tripOpen()
        assertEquals(Phase.OPEN, BinanceHostGate.phaseForTesting())
        assertTrue(BinanceHostGate.isUnavailable())
        assertFalse(BinanceHostGate.tryAcquireProbe(base))
        assertEquals(1, BinanceHostGate.blockCountForTesting())
        // 触发熔断时计数清零：冷却期内的迟来失败重新累计
        assertEquals(0, BinanceHostGate.consecutiveFailuresForTesting())
        assertEquals(base + BinanceHostGate.COOLDOWN_MS, BinanceHostGate.blockedUntilForTesting())
    }

    // ---------- OPEN 冷却期 ----------

    @Test
    fun `冷却期内 tryAcquireProbe 恒拒绝`() {
        tripOpen()
        assertFalse(BinanceHostGate.tryAcquireProbe(base))
        assertFalse(BinanceHostGate.tryAcquireProbe(base + BinanceHostGate.COOLDOWN_MS - 1))
        // 冷却期一直是「暂不可达」，UI 该显示提示
        assertTrue(BinanceHostGate.isUnavailable())
    }

    @Test
    fun `OPEN 期间迟到成功不解封，仅清零计数`() {
        tripOpen()
        // 冷却期内又来了两次失败（并发迟来）：计数被压到阈值以下，不为 0
        BinanceHostGate.onNetworkFailure(base + 1_000)
        BinanceHostGate.onNetworkFailure(base + 2_000)
        // 一次成功把计数清零
        BinanceHostGate.onSuccess()
        assertEquals(0, BinanceHostGate.consecutiveFailuresForTesting())
        // 但熔断不因此提前解封：成功不清 blockedUntil，避免抖动解封
        assertEquals(Phase.OPEN, BinanceHostGate.phaseForTesting())
        assertFalse(BinanceHostGate.tryAcquireProbe(base + 3_000))
        assertFalse(BinanceHostGate.tryAcquireProbe(base + BinanceHostGate.COOLDOWN_MS - 1))
    }

    @Test
    fun `onSuccess 单独调用能把计数清零，需重新累计 5 次才熔断`() {
        BinanceHostGate.onNetworkFailure(base)
        BinanceHostGate.onNetworkFailure(base)
        BinanceHostGate.onSuccess()
        assertEquals(0, BinanceHostGate.consecutiveFailuresForTesting())
        // 若计数没被清零，这里 4+2=6 早已越过阈值；清零后只到 4，不熔断
        repeat(4) { BinanceHostGate.onNetworkFailure(base) }
        assertEquals(Phase.CLOSED, BinanceHostGate.phaseForTesting())
        assertTrue(BinanceHostGate.tryAcquireProbe(base))
        // 再失败一次（第 5 次）才重新熔断
        BinanceHostGate.onNetworkFailure(base)
        assertEquals(Phase.OPEN, BinanceHostGate.phaseForTesting())
        assertFalse(BinanceHostGate.tryAcquireProbe(base))
    }

    // ---------- HALF_OPEN 半开探测 ----------

    @Test
    fun `冷却结束恰好 1 个探测放行_并发第 2 个仍拒`() {
        tripOpen()
        // 冷却已过：第一个 tryAcquireProbe 竞争成为唯一的半开探测
        assertTrue("冷却结束后应放行恰好一个探测", BinanceHostGate.tryAcquireProbe(afterCooldown))
        assertEquals(Phase.HALF_OPEN, BinanceHostGate.phaseForTesting())
        // 半开期探测已在进行：其余请求全部拒绝，不烧时间
        assertFalse(BinanceHostGate.tryAcquireProbe(afterCooldown))
        assertFalse(BinanceHostGate.tryAcquireProbe(afterCooldown + 1))
        // 半开期对 UI 仍是「暂不可达」
        assertTrue(BinanceHostGate.isUnavailable())
    }

    @Test
    fun `探测成功闭路_后续全放行且 blockCount 不涨`() {
        tripOpen()
        assertTrue(BinanceHostGate.tryAcquireProbe(afterCooldown)) // 成为探测
        BinanceHostGate.onSuccess() // 探测成功
        // 闭路：全部清零，blockCount 不涨（探测成功是恢复，不是又一次判死）
        assertEquals(Phase.CLOSED, BinanceHostGate.phaseForTesting())
        assertEquals(0, BinanceHostGate.consecutiveFailuresForTesting())
        assertEquals(0L, BinanceHostGate.blockedUntilForTesting())
        assertEquals(1, BinanceHostGate.blockCountForTesting())
        // 后续请求全放行、UI 提示消失
        assertTrue(BinanceHostGate.tryAcquireProbe(afterCooldown))
        assertTrue(BinanceHostGate.tryAcquireProbe(afterCooldown + 1))
        assertFalse(BinanceHostGate.isUnavailable())
    }

    @Test
    fun `探测失败立即重开冷却_不等计数到阈值`() {
        tripOpen()
        assertTrue(BinanceHostGate.tryAcquireProbe(afterCooldown)) // 成为探测
        // 探测失败：主机仍不可达的确定证据，立即重开冷却，不等累计到阈值
        BinanceHostGate.onNetworkFailure(afterCooldown)
        assertEquals(Phase.OPEN, BinanceHostGate.phaseForTesting())
        // 截止时间按探测失败的 now 顺延一整轮冷却
        assertEquals(afterCooldown + BinanceHostGate.COOLDOWN_MS, BinanceHostGate.blockedUntilForTesting())
        // 重开不计入 blockCount（是同一次熔断 episode 的延续，不是新的判死）
        assertEquals(1, BinanceHostGate.blockCountForTesting())
        // 新一轮冷却内继续拒绝
        assertFalse(BinanceHostGate.tryAcquireProbe(afterCooldown))
        assertFalse(BinanceHostGate.tryAcquireProbe(afterCooldown + BinanceHostGate.COOLDOWN_MS - 1))
    }

    @Test
    fun `探测卡死自愈_超过 PROBE_TIMEOUT 重新武装`() {
        tripOpen()
        assertTrue(BinanceHostGate.tryAcquireProbe(afterCooldown)) // 成为探测，probeUntil = afterCooldown + PROBE_TIMEOUT
        // 探测窗口内：仍是 HALF_OPEN、拒绝新请求
        assertFalse(BinanceHostGate.tryAcquireProbe(afterCooldown + BinanceHostGate.PROBE_TIMEOUT_MS - 1))
        // 探测卡死（被取消、未 resolve）：超过 PROBE_TIMEOUT 后自愈，重新武装一个新探测并放行
        assertTrue(BinanceHostGate.tryAcquireProbe(afterCooldown + BinanceHostGate.PROBE_TIMEOUT_MS))
        assertEquals(Phase.HALF_OPEN, BinanceHostGate.phaseForTesting())
        // 重新武装后立刻又是「只有一个探测」：同一 now 再来一个仍被拒
        assertFalse(BinanceHostGate.tryAcquireProbe(afterCooldown + BinanceHostGate.PROBE_TIMEOUT_MS))
    }

    // ---------- 并发：只熔断一次 ----------

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
        assertEquals(Phase.OPEN, BinanceHostGate.phaseForTesting())
        assertTrue(BinanceHostGate.isUnavailable())
        assertEquals(base + BinanceHostGate.COOLDOWN_MS, BinanceHostGate.blockedUntilForTesting())
        assertEquals(1, BinanceHostGate.blockCountForTesting())
        // 计数不越界：并发越界的线程只把计数压回阈值以下，不会虚涨
        assertTrue(
            "计数越界: ${BinanceHostGate.consecutiveFailuresForTesting()}",
            BinanceHostGate.consecutiveFailuresForTesting() in 0 until BinanceHostGate.FAIL_THRESHOLD
        )
    }
}
