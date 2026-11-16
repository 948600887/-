package com.example.stockdiary.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FailureClassifier] 的口径契约（纯函数）。
 *
 * 重点钉住两类历史坑：
 * - HTTP 状态码只认**独立的 3 位 token**：成交额 14035、错误码 4291、金额 40300、
 *   股票代码 sz000403 都不能被当成 403 / 429；
 * - 「限流」与「4xx」不是同一件事：403/429 在请求级不重试，但轮次级仍按 4xx 归类。
 */
class FailureClassifierTest {

    @Test
    fun `识别独立的三位状态码_常见写法都能命中`() {
        assertEquals(listOf(403), FailureClassifier.httpStatusCodesIn("API响应错误: 403"))
        assertEquals(listOf(403), FailureClassifier.httpStatusCodesIn("服务器返回 HTTP 403"))
        assertEquals(listOf(429), FailureClassifier.httpStatusCodesIn("status=429"))
        assertEquals(listOf(451), FailureClassifier.httpStatusCodesIn("响应 451"))
        assertEquals(listOf(503), FailureClassifier.httpStatusCodesIn("HTTP 503 Service Unavailable"))
    }

    @Test
    fun `长数字里的 403_429 不能被误认成状态码`() {
        listOf(
            "拉取流水失败，成交额 14035 元",
            "东方财富错误码: 14035",
            "笔数 4291 超限",
            "金额 40300.00 解析失败",
            "股票代码 sz000403 行情为空"
        ).forEach { message ->
            assertTrue("$message 不应命中任何状态码", FailureClassifier.httpStatusCodesIn(message).isEmpty())
            assertFalse("$message 不应被判成限流", FailureClassifier.isRateLimited(message))
        }
    }

    @Test
    fun `一条文案里同时出现多个状态码时全部返回`() {
        assertEquals(listOf(500, 403), FailureClassifier.httpStatusCodesIn("upstream 500 then 403"))
        assertTrue(FailureClassifier.hasServerErrorStatus("upstream 500 then 403"))
        assertTrue(FailureClassifier.isRateLimited("upstream 500 then 403"))
    }

    @Test
    fun `服务端错误只认 5xx_客户端错误只认 4xx`() {
        assertTrue(FailureClassifier.hasServerErrorStatus("HTTP 502"))
        assertFalse(FailureClassifier.hasClientErrorStatus("HTTP 502"))
        assertTrue(FailureClassifier.hasClientErrorStatus("HTTP 404"))
        assertFalse(FailureClassifier.hasServerErrorStatus("HTTP 404"))
        // 关键词不含数字时，状态码判定本身必须为 false，由上层的关键词分支负责
        assertFalse(FailureClassifier.hasServerErrorStatus("Internal Server Error"))
    }

    @Test
    fun `限流判定只认 403 与 429`() {
        assertTrue(FailureClassifier.isRateLimited("HTTP 403"))
        assertTrue(FailureClassifier.isRateLimited("429 Too Many Requests"))
        assertFalse(FailureClassifier.isRateLimited("HTTP 404"))
        assertFalse(FailureClassifier.isRateLimited("HTTP 500"))
        assertFalse(FailureClassifier.isRateLimited(null))
    }

    @Test
    fun `确定性业务失败_限流词组与无效代码词组`() {
        listOf(
            "API请求频率过高，请稍后再试",
            "您的请求过于频繁，请稍后重试",
            "Rate limit exceeded",
            "未获取到股票数据，请检查股票代码",
            "代码不存在或已退市",
            "无效的代码: abcd",
            "HTTP 403",
            "响应异常 code=429"
        ).forEach { message ->
            assertTrue("$message 应判为确定性失败", FailureClassifier.isDeterministicBusinessFailure(message))
        }
    }

    @Test
    fun `可恢复的网络文案不算确定性失败`() {
        listOf(
            "连接被重置",
            "timeout waiting for response",
            "拉取流水失败，成交额 14035 元"
        ).forEach { message ->
            assertFalse("$message 应可重试", FailureClassifier.isDeterministicBusinessFailure(message))
        }
        assertFalse(FailureClassifier.isDeterministicBusinessFailure(null))
        assertFalse(FailureClassifier.isDeterministicBusinessFailure("   "))
    }
}
