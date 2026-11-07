package com.example.stockdiary.data.sync

import com.example.stockdiary.data.price.SourceStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 源健康提示的纯逻辑回归：只把「源级失败」翻译成一句可见提示。
 *
 * 符号级失败（代码不在该源支持范围）是常态，不能触发提示，否则每轮刷新
 * 都会因为几个退市/未覆盖代码而弹一句无意义的话。
 */
class SyncResultHealthTest {

    private fun stat(
        source: String,
        success: Int = 0,
        sourceFailure: Int = 0,
        symbolFailure: Int = 0,
        durationMs: Long = 0,
        reason: String? = null,
        degradedReason: String? = null
    ) = SourceStats(
        source = source,
        success = success,
        sourceLevelFailure = sourceFailure,
        symbolLevelFailure = symbolFailure,
        durationMs = durationMs,
        lastFailureReason = reason,
        degradedReason = degradedReason
    )

    @Test
    fun `无源级失败不产生提示`() {
        val result = SyncResult(
            successCount = 10,
            failedCount = 0,
            sourceStats = listOf(stat("新浪", success = 10, durationMs = 320)),
            elapsedMs = 500
        )

        assertEquals("", result.sourceHealthHint)
        assertEquals(500L, result.elapsedMs)
        assertEquals(320L, result.sourceStats.single().durationMs)
    }

    @Test
    fun `符号级失败不算源故障`() {
        val result = SyncResult(
            successCount = 8,
            failedCount = 2,
            sourceStats = listOf(stat("腾讯", success = 8, symbolFailure = 2, durationMs = 90))
        )

        assertEquals("", result.sourceHealthHint)
    }

    @Test
    fun `源级失败带上源名次数与原因`() {
        val result = SyncResult(
            successCount = 20,
            failedCount = 3,
            sourceStats = listOf(
                stat("新浪", success = 20, sourceFailure = 3, durationMs = 1200, reason = "连接超时"),
                stat("腾讯", success = 20, durationMs = 400)
            )
        )

        val hint = result.sourceHealthHint
        assertTrue(hint.startsWith("；数据源异常："))
        assertTrue(hint.contains("新浪 源级失败 3 次"))
        assertTrue(hint.contains("连接超时"))
        // 正常源不点名，避免提示越滚越长
        assertTrue(!hint.contains("腾讯"))
    }

    @Test
    fun `多个源故障用顿号连接且原因为空时不加括号`() {
        val result = SyncResult(
            successCount = 1,
            failedCount = 1,
            sourceStats = listOf(
                stat("新浪", sourceFailure = 1, reason = null),
                stat("腾讯", sourceFailure = 2, reason = "HTTP 500")
            )
        )

        val hint = result.sourceHealthHint
        assertTrue(hint.contains("新浪 源级失败 1 次、腾讯 源级失败 2 次，HTTP 500"))
    }

    @Test
    fun `过长的失败原因会截短`() {
        val longReason = "x".repeat(40)
        val result = SyncResult(
            successCount = 0,
            failedCount = 1,
            sourceStats = listOf(stat("新浪", sourceFailure = 1, reason = longReason))
        )

        assertTrue(result.sourceHealthHint.contains("x".repeat(24)))
        assertTrue(!result.sourceHealthHint.contains("x".repeat(25)))
    }

    @Test
    fun `仅有降级原因也提示_且不显示源级失败次数`() {
        // 基金轻量源熔断 / 币安 451 粘死都属于「没发请求的降级」，不能虚增失败次数
        val result = SyncResult(
            successCount = 0,
            failedCount = 0,
            sourceStats = listOf(
                stat("币安-镜像", degradedReason = "HTTP 451/403 地区封锁，主机熔断粘死，本轮未请求")
            )
        )

        val hint = result.sourceHealthHint
        assertTrue(hint.startsWith("；数据源异常："))
        assertTrue(hint.contains("币安-镜像 HTTP 451/403 地区封锁"))
        assertTrue("降级不是源级失败，不能显示失败次数", !hint.contains("源级失败"))
    }

    @Test
    fun `源级失败与降级原因可同时出现`() {
        val result = SyncResult(
            successCount = 1,
            failedCount = 1,
            sourceStats = listOf(
                stat(
                    "基金-轻量源",
                    sourceFailure = 1,
                    reason = "HTTP 514 限流",
                    degradedReason = "连续失败已熔断，本轮直接走 legacy"
                )
            )
        )

        val hint = result.sourceHealthHint
        assertTrue(hint.contains("基金-轻量源 源级失败 1 次，HTTP 514 限流"))
        assertTrue(hint.contains("连续失败已熔断"))
    }
}
