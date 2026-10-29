package com.example.stockdiary.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.InterruptedIOException
import java.io.IOException

/**
 * QA 独立复验（fresh eyes）：Bug 1「切后台中断不喂币安熔断」的针对性回归。
 *
 * ## 修复语义（BinanceAccountAPI.getPrices catch，约 :516）
 * ```
 * val gaveUp = !currentCoroutineContext().isActive || isCancellationArtifact(e)
 * if (!gaveUp && e is IOException) BinanceHostGate.onNetworkFailure(...)
 * ```
 * 即：**协程已取消（切后台/VM 销毁）** 或 **异常本身是「我们主动取消」的产物**
 * （CancellationException / IOException("Canceled")）时，跳过记账；
 * 只有「协程仍活跃时的真实网络 IO 失败（超时/连不上/DNS）」才喂熔断器。
 *
 * ## 本组钉死的三条契约（核心：精准排除取消、不误伤真实失败）
 *
 *  1. **取消态 + InterruptedIOException（真实 timeout）** → 不喂熔断。
 *     模拟「切后台取消后，阻塞中的 execute() 跑到 callTimeout 才以
 *     InterruptedIOException 收尾」：在假 Interceptor 里先把协程树取消、再抛超时异常，
 *     断言 [BinanceHostGate] 失败计数 == 0、相位仍 CLOSED、不显示「暂不可达」。
 *     这是修复前会误熔的场景（真机 20:07 那次切后台误熔）。
 *  2. **前台活跃 + 同样的 InterruptedIOException（真实 timeout）** → 照常喂到 OPEN。
 *     对照组：同样的异常、同样的取价路径，只是协程仍活跃（未取消），必须仍逐次记账、
 *     累计到 [BinanceHostGate.FAIL_THRESHOLD] 触发熔断。证明「精准排除取消」，不是「一刀切不记账」。
 *  3. **前台活跃 + OkHttp 的 IOException("Canceled")** → 仍不喂熔断。
 *     补充断言 isCancellationArtifact 口径：OkHttp 取消调用抛的 "Canceled" 是「我们主动取消」
 *     的标记，即便前台活跃也必须排除——否则每次手动取消都会误记一次。这条证明我们不会
 *     把「自己取消」误当成「主机不可达」。
 *
 * [BinanceHostGate] 是进程内 object：会真把闸门打到 OPEN，故 @Before/@After 双向 resetForTesting。
 */
class BinanceHostGateCancelTest {

    @Before
    fun resetGate() = BinanceHostGate.resetForTesting()

    @After
    fun resetGateAfter() = BinanceHostGate.resetForTesting()

    /** 造一个只挂 [interceptor] 的假 client（不连网、不复用真实连接池） */
    private fun fakeClient(interceptor: Interceptor): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(interceptor).build()

    // ---------- 契约 1：取消态 + 真实 timeout 不喂熔断 ----------

    @Test
    fun `getPrices 协程已取消_InterruptedIOException 不喂熔断`() = runBlocking {
        // 模拟「切后台取消」：协程树已被 cancel，但阻塞中的 execute() 仍跑到 callTimeout
        // 才以 InterruptedIOException 收尾（见 BinanceAccountAPI.getPrices 的注释与真机日志）。
        val scope = CoroutineScope(Job() + Dispatchers.IO)
        val client = fakeClient(object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
                // 在请求链路里把本协程树取消：此时 getPrices 的 withContext 子协程已处于取消态，
                // 但底层 execute() 抛出的 InterruptedIOException 仍会被 catch 收到。
                scope.cancel()
                throw InterruptedIOException("timeout")
            }
        })

        val job = scope.launch {
            BinanceAccountAPI.getPrices(listOf("BTCUSDT"), client)
        }
        try {
            // 取价失败降级为空 map；withContext 在协程已取消后重抛取消（调用方据此复位进度态）。
            // 我们关注的是：取消产生的超时异常绝不能喂进熔断器。
            job.join()
        } catch (_: CancellationException) {
            // 预期：取消穿透为 CancellationException，不影响下面的计数断言
        }

        // 关键断言：取消产生的 InterruptedIOException 不得被记成「主机不可达」
        assertEquals(
            "取消态下的超时异常绝不计入熔断连续失败计数",
            0,
            BinanceHostGate.consecutiveFailuresForTesting()
        )
        assertEquals(Phase.CLOSED, BinanceHostGate.phaseForTesting())
        assertFalse("取消导致的超时绝不能触发币安熔断", BinanceHostGate.isUnavailable())
        scope.cancel() // 兜底清理，避免悬挂协程泄漏
    }

    // ---------- 契约 2：前台活跃 + 同样异常 仍喂到 OPEN（对照组） ----------

    @Test
    fun `getPrices 前台活跃_真实网络超时必须喂到熔断`() = runBlocking {
        // 对照组：同样的 InterruptedIOException（真实 socket 读超时，非取消），但协程仍活跃。
        // 必须照常逐次记账，累计到阈值触发熔断——否则就是把「真实失败」也误排除了。
        val client = fakeClient(object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
                throw InterruptedIOException("timeout") // 真实网络超时
            }
        })

        repeat(BinanceHostGate.FAIL_THRESHOLD) {
            // 取价失败降级为空 map（绝不阻断导入）——公开语义，一并钉住
            assertTrue(
                "前台真实超时取价失败应降级为空 map（不阻断导入）",
                BinanceAccountAPI.getPrices(listOf("BTCUSDT"), client).isEmpty()
            )
        }

        // 关键断言：前台活跃协程下的真实网络超时必须照常累计到阈值并触发熔断
        assertEquals(Phase.OPEN, BinanceHostGate.phaseForTesting())
        assertTrue(BinanceHostGate.isUnavailable())
        assertEquals(1, BinanceHostGate.blockCountForTesting())
    }

    // ---------- 契约 3：前台活跃 + OkHttp "Canceled" 仍不喂（isCancellationArtifact 口径） ----------

    @Test
    fun `getPrices 前台活跃_OkHttp Canceled 标记仍不喂熔断`() = runBlocking {
        // OkHttp 取消调用会抛 IOException("Canceled")，这是「我们主动取消」的标记，
        // 即便前台活跃也必须排除——否则每次手动取消都会误记一次「主机不可达」。
        val client = fakeClient(object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
                throw IOException("Canceled")
            }
        })

        repeat(BinanceHostGate.FAIL_THRESHOLD) {
            BinanceAccountAPI.getPrices(listOf("BTCUSDT"), client)
        }

        assertEquals(
            "OkHttp 的 Canceled 是「我们主动取消」的标记，绝不计入熔断计数",
            0,
            BinanceHostGate.consecutiveFailuresForTesting()
        )
        assertEquals(Phase.CLOSED, BinanceHostGate.phaseForTesting())
        assertFalse(BinanceHostGate.isUnavailable())
    }
}
