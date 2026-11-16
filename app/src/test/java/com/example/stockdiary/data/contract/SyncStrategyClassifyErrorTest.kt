package com.example.stockdiary.data.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * [SyncStrategy.classifyError] / [SyncStrategy.isRetryable] 的口径契约。
 *
 * 这一层是**轮次级**分类：DNS 不可达在请求级不重试（见 NetworkRetryHelper），
 * 但下一轮仍然值得再试，所以这里必须归 NETWORK。状态码解析已收口到
 * [com.example.stockdiary.common.FailureClassifier]，本测试确保接入后分类结果不变。
 */
class SyncStrategyClassifyErrorTest {

    @Test
    fun `DNS_超时_连接失败都归 NETWORK`() {
        assertEquals(SyncErrorType.NETWORK, SyncStrategy.classifyError(UnknownHostException("api.example.com")))
        assertEquals(SyncErrorType.NETWORK, SyncStrategy.classifyError(SocketTimeoutException("timeout")))
        assertEquals(SyncErrorType.NETWORK, SyncStrategy.classifyError(ConnectException("refused")))
    }

    @Test
    fun `5xx 文案归 SERVER_4xx 文案归 DATA`() {
        assertEquals(SyncErrorType.SERVER, SyncStrategy.classifyError(IOException("HTTP 503")))
        assertEquals(SyncErrorType.SERVER, SyncStrategy.classifyError(IOException("Bad Gateway")))
        assertEquals(SyncErrorType.DATA, SyncStrategy.classifyError(IOException("HTTP 404")))
        assertEquals(SyncErrorType.DATA, SyncStrategy.classifyError(IOException("404 Not Found")))
        // 403 在轮次级属于 4xx（请求级不重试是另一层策略，不冲突）
        assertEquals(SyncErrorType.DATA, SyncStrategy.classifyError(IOException("HTTP 403")))
    }

    @Test
    fun `含长数字的 IO 异常不会被误判成状态码`() {
        assertEquals(SyncErrorType.UNKNOWN, SyncStrategy.classifyError(IOException("成交额 14035 元")))
        assertEquals(SyncErrorType.UNKNOWN, SyncStrategy.classifyError(IOException("股票代码 sz000403 行情为空")))
    }

    @Test
    fun `解析类异常归 DATA_无法识别归 UNKNOWN`() {
        assertEquals(SyncErrorType.DATA, SyncStrategy.classifyError(IllegalArgumentException("bad")))
        assertEquals(SyncErrorType.DATA, SyncStrategy.classifyError(NumberFormatException("bad")))
        assertEquals(SyncErrorType.DATA, SyncStrategy.classifyError(IndexOutOfBoundsException()))
        assertEquals(SyncErrorType.UNKNOWN, SyncStrategy.classifyError(RuntimeException("boom")))
        assertEquals(SyncErrorType.UNKNOWN, SyncStrategy.classifyError(null))
    }

    @Test
    fun `只有 DATA 不值得重试`() {
        assertFalse(SyncStrategy.isRetryable(SyncErrorType.DATA))
        assertTrue(SyncStrategy.isRetryable(SyncErrorType.NETWORK))
        assertTrue(SyncStrategy.isRetryable(SyncErrorType.SERVER))
        assertTrue(SyncStrategy.isRetryable(SyncErrorType.UNKNOWN))
    }
}
