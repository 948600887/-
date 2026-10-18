package com.example.stockdiary.network

import com.example.stockdiary.common.Result
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * [NetworkRetryHelper] 的「确定性失败」判定回归契约。
 *
 * 钉死的契约（改动前 regressions 的根源）：
 * - 真正的 HTTP 403 / 429 属于限流，重试只会火上浇油 → 只请求 1 次；
 * - 文案里恰好出现 403/429 这几位数字的普通故障（成交额 14035、错误码 4291、
 *   股票代码 sz000403…）必须照常重试 → 请求 maxRetries 次。
 *
 * 判据统一取「block 的实际调用次数」：这是该 Bug 唯一的用户可见差异，
 * 且不依赖任何 private 成员的可见性。
 */
class NetworkRetryHelperNonRetryableTest {

    /** 退避压到毫秒级：用例跑得快，重试语义不受影响 */
    private val tinyDelayMs = 1L
    private val maxRetries = 3

    /**
     * 跑一遍「永远失败」的请求，返回 block 被实际调用的次数。
     *
     * @param message 失败文案，决定这次是否被判成确定性失败
     * @param viaThrow true 走 throw 分支，false 走 Result.Error 分支（两条分支都要覆盖）
     */
    private fun attemptsFor(message: String, viaThrow: Boolean = true): Int {
        var attempts = 0
        runBlocking {
            val result = NetworkRetryHelper.executeWithRetry<Unit>(
                maxRetries = maxRetries,
                initialDelayMs = tinyDelayMs,
                maxDelayMs = tinyDelayMs * 2,
                onRetry = { _, _ -> /* 免打日志 */ }
            ) {
                attempts++
                val failure = IOException(message)
                if (viaThrow) {
                    throw failure
                }
                Result.failure(failure)
            }
            assertTrue("预期最终是失败结果", result.isError)
        }
        return attempts
    }

    // ---------- 真正的 403 / 429：必须仍然是不重试 ----------

    @Test
    fun `HTTP 403 各种写法都判定为确定性失败，一次就放弃`() {
        listOf(
            "API响应错误: 403",
            "服务器返回 HTTP 403",
            "403 Forbidden",
            "status=403",
            "code 403 limit exceeded"
        ).forEach { message ->
            assertEquals("$message 应视为 403 不重试", 1, attemptsFor(message))
            assertEquals("$message（Result.Error 分支）应视为 403 不重试", 1, attemptsFor(message, viaThrow = false))
        }
    }

    @Test
    fun `429 太频繁判定为确定性失败，一次就放弃`() {
        listOf(
            "429",
            "HTTP 429 Too Many Requests",
            "响应异常 code=429"
        ).forEach { message ->
            assertEquals("$message 应视为 429 不重试", 1, attemptsFor(message))
            assertEquals("$message（Result.Error 分支）应视为 429 不重试", 1, attemptsFor(message, viaThrow = false))
        }
    }

    // ---------- 含 403/429 数字的普通故障：必须照常重试 ----------

    @Test
    fun `含 14035 这类数字的文案不能被误判成不重试`() {
        listOf(
            "拉取流水失败，成交额 14035 元",
            "东方财富错误码: 14035",
            "笔数 4291 超限",
            "金额 40300.00 解析失败",
            "股票代码 sz000403 行情为空"
        ).forEach { message ->
            assertEquals("$message 只是数字，应照常重试", maxRetries, attemptsFor(message))
            assertEquals(
                "$message（Result.Error 分支）只是数字，应照常重试",
                maxRetries,
                attemptsFor(message, viaThrow = false)
            )
        }
    }

    @Test
    fun `普通网络故障仍然重试，且退避次数达到上限`() {
        assertEquals(maxRetries, attemptsFor("连接被重置"))
        assertEquals(maxRetries, attemptsFor("timeout waiting for response"))

        var attempts = 0
        runBlocking {
            val result = NetworkRetryHelper.executeWithRetry<Unit>(
                maxRetries = maxRetries,
                initialDelayMs = tinyDelayMs,
                maxDelayMs = tinyDelayMs * 2,
                onRetry = { _, _ -> }
            ) {
                attempts++
                throw SocketTimeoutException("connect timed out")
            }
            assertTrue("超时必须走到重试耗尽", result.isError)
        }
        assertEquals(maxRetries, attempts)
    }

    // ---------- 中文 / 英文限流词组：子串匹配保持不变 ----------

    @Test
    fun `限流词组判定为确定性失败，一次就放弃`() {
        listOf(
            "API请求频率过高，请稍后再试",
            "您的请求过于频繁，请稍后重试",
            "Rate limit exceeded",
            "RATE LIMIT hit for this endpoint"
        ).forEach { message ->
            assertEquals("$message 属于限流，不应重试", 1, attemptsFor(message))
        }
    }

    @Test
    fun `无效代码类词组判定为确定性失败，一次就放弃`() {
        listOf(
            "未获取到股票数据，请检查股票代码",
            "代码不存在或已退市",
            "无效的代码: abcd"
        ).forEach { message ->
            assertEquals("$message 属于无效标的，不应重试", 1, attemptsFor(message))
        }
    }

    // ---------- 协程取消不能被重试逻辑吞掉 ----------

    @Test
    fun `block 抛出的取消异常直接上抛，不进入重试`() {
        var attempts = 0
        runBlocking {
            try {
                NetworkRetryHelper.executeWithRetry<Unit>(
                    maxRetries = maxRetries,
                    initialDelayMs = tinyDelayMs,
                    maxDelayMs = tinyDelayMs * 2,
                    onRetry = { _, _ -> }
                ) {
                    attempts++
                    throw CancellationException("job cancelled")
                }
                fail("取消异常必须继续上抛，不能被包装成 Result.Error")
            } catch (e: CancellationException) {
                // 预期路径
            }
        }
        assertEquals("取消不能触发重试", 1, attempts)
    }
}
