package com.example.stockdiary.data.price

import com.example.stockdiary.network.BinanceHostGate
import com.example.stockdiary.network.FailureType
import com.example.stockdiary.network.Phase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 熔断计数器的高并发 CAS 竞态压力测试。
 *
 * 两套断路器（数据源级 [SourceCircuitBreaker] / 币安主机级 [BinanceHostGate]）都用
 * AtomicReference 整包 CAS 实现状态迁移。「同步 6 并发 + 手动/Worker 撞车」是常态，
 * 因此必须钉住三件事：
 *  1. **计数准确**：N 个协程各喂 1 次失败，计数就是 N（不丢、不重复）；
 *  2. **CLOSED→OPEN 只转换一次**：并发远超阈值时只熔禁一次、冷却截止唯一；
 *  3. **探测名额只有一个**：冷却结束后并发申请探测，恰好 1 个放行。
 *
 * 阈值一律从 [SourceCircuitBreaker.FAIL_THRESHOLD] / [BinanceHostGate.FAIL_THRESHOLD]
 * 推导，禁止硬编码（历史上硬编码 5 曾造成阈值改到 8 后测试假绿）。
 *
 * 并发用 `CompletableDeferred` 起跑门 + [Dispatchers.IO]：挂起式等待不占线程，
 * N 个协程在多线程池上真正同时起跑，也不依赖 `delay()` 制造不确定性。
 */
class CircuitBreakerCasRaceTest {

    private val base = 1_700_000_000_000L
    private val afterCooldown: Long get() = base + SourceCircuitBreaker.COOLDOWN_MS

    @After
    fun tearDown() {
        // BinanceHostGate 是进程内 object，用例之间必须清状态
        BinanceHostGate.resetForTesting()
    }

    /** N 个真并发协程同时执行 action，返回全部结果。 */
    private suspend fun <T> race(n: Int, action: () -> T): List<T> = coroutineScope {
        val gate = CompletableDeferred<Unit>()
        val jobs = List(n) {
            async(Dispatchers.IO) {
                gate.await()
                action()
            }
        }
        gate.complete(Unit)
        jobs.awaitAll()
    }

    @Test
    fun `N个协程并发onFailure_源级计数准确且相位仍CLOSED`() = runBlocking {
        val breaker = SourceCircuitBreaker()
        val belowThreshold = SourceCircuitBreaker.FAIL_THRESHOLD - 1

        race(belowThreshold) { breaker.onFailure(base) }

        assertEquals(
            "N 个并发各喂 1 次，CAS 累加必须一次不丢",
            belowThreshold,
            breaker.failuresForTesting()
        )
        assertEquals(SourcePhase.CLOSED, breaker.phaseForTesting())
        assertTrue(breaker.tryAcquireProbe(base))

        // 再补最后 1 次：恰好达到阈值 → OPEN
        breaker.onFailure(base)
        assertEquals(SourcePhase.OPEN, breaker.phaseForTesting())
        assertEquals(0, breaker.failuresForTesting())
    }

    @Test
    fun `源级并发远超阈值_CLOSED转OPEN只一次_冷却截止唯一`() = runBlocking {
        val breaker = SourceCircuitBreaker()
        val threads = 16
        val perThread = SourceCircuitBreaker.FAIL_THRESHOLD * 2

        race(threads) {
            repeat(perThread) { breaker.onFailure(base) }
        }

        assertEquals(SourcePhase.OPEN, breaker.phaseForTesting())
        assertEquals(
            "所有并发都用同一个 now，冷却截止只能被写一次",
            base + SourceCircuitBreaker.COOLDOWN_MS,
            breaker.blockedUntilForTesting()
        )
        assertTrue(
            "转 OPEN 后计数应已清零或压在阈值以下",
            breaker.failuresForTesting() in 0 until SourceCircuitBreaker.FAIL_THRESHOLD
        )
    }

    @Test
    fun `源级冷却结束后并发申请探测_恰好只有1个名额放行`() = runBlocking {
        val breaker = SourceCircuitBreaker()
        repeat(SourceCircuitBreaker.FAIL_THRESHOLD) { breaker.onFailure(base) }
        assertEquals(SourcePhase.OPEN, breaker.phaseForTesting())

        val probes = race(16) { breaker.tryAcquireProbe(afterCooldown) }

        assertEquals(
            "半开探测名额全局只能有 1 个",
            1,
            probes.count { it }
        )
        assertEquals(SourcePhase.HALF_OPEN, breaker.phaseForTesting())
    }

    @Test
    fun `BinanceHostGate并发计数准确_blockCount恰好1_探测名额唯一`() = runBlocking {
        BinanceHostGate.resetForTesting()
        val belowThreshold = BinanceHostGate.FAIL_THRESHOLD - 1

        race(belowThreshold) {
            BinanceHostGate.onNetworkFailure(base, FailureType.TRANSIENT)
        }
        assertEquals(
            "N 个并发瞬断失败，计数必须准确",
            belowThreshold,
            BinanceHostGate.consecutiveFailuresForTesting()
        )
        assertEquals(Phase.CLOSED, BinanceHostGate.phaseForTesting())
        assertEquals(0, BinanceHostGate.blockCountForTesting())

        // 再并发远超阈值：CLOSED→OPEN 只能转一次（blockCount 是唯一可观测凭据）
        race(16) {
            repeat(BinanceHostGate.FAIL_THRESHOLD * 2) {
                BinanceHostGate.onNetworkFailure(base, FailureType.TRANSIENT)
            }
        }
        assertEquals(Phase.OPEN, BinanceHostGate.phaseForTesting())
        assertEquals(
            "并发下 CLOSED→OPEN 只应发生一次",
            1,
            BinanceHostGate.blockCountForTesting()
        )
        assertEquals(
            base + BinanceHostGate.COOLDOWN_MS,
            BinanceHostGate.blockedUntilForTesting()
        )

        val probes = race(16) {
            BinanceHostGate.tryAcquireProbe(base + BinanceHostGate.COOLDOWN_MS)
        }
        assertEquals(
            "主机级半开探测名额同样只能有 1 个",
            1,
            probes.count { it }
        )
    }
}
