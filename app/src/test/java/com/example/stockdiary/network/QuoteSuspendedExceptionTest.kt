package com.example.stockdiary.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * 「停牌 / 无有效报价」的跨层约定测试。
 *
 * 这条约定一旦退化就会变成很难排查的线上故障：停牌标的被当成数据源失败去累计次数，
 * 累满 3 次会禁用整个数据源 30 分钟 —— 表现为全市场行情同步停摆，
 * 而日志里只有一句「所有数据源都失败」，完全看不出是某只停牌股引起的。
 */
class QuoteSuspendedExceptionTest {

    @Test
    fun `停牌异常能被自身类型识别`() {
        assertTrue(QuoteSuspendedException.isSuspended(QuoteSuspendedException("sh600000")))
    }

    @Test
    fun `停牌异常是 IOException，既有 IOException 分支仍能捕获`() {
        assertTrue(QuoteSuspendedException("sh600000") is IOException)
    }

    @Test
    fun `带停牌字样的普通异常也能识别`() {
        // 跨层传递后只剩 message 的场景（例如被重新包装过）
        assertTrue(QuoteSuspendedException.isSuspended(IOException("停牌或无有效报价: sh600000")))
        assertTrue(QuoteSuspendedException.isSuspended(IOException("该标的无有效报价")))
    }

    @Test
    fun `cause 链里藏着的停牌异常也能识别`() {
        val wrapped = IllegalStateException(
            "批量同步失败",
            QuoteSuspendedException("sh600000")
        )
        assertTrue(QuoteSuspendedException.isSuspended(wrapped))
    }

    @Test
    fun `普通网络故障不能被误判为停牌`() {
        assertFalse(QuoteSuspendedException.isSuspended(IOException("timeout")))
        assertFalse(QuoteSuspendedException.isSuspended(IOException("无法获取股票数据: sh600000")))
        assertFalse(QuoteSuspendedException.isSuspended(null))
        assertFalse(QuoteSuspendedException.isSuspended(IllegalStateException("返回数据为空")))
    }
}
