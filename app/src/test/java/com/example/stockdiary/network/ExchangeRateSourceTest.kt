package com.example.stockdiary.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 汇率第二源与降级策略的离线回归。
 *
 * 重点钉三件事：
 * 1. [FallbackRateSource] 正常链路**不请求兜底源**（请求数不增）；
 * 2. 主源失败（null / 空 Map / 抛异常）才兜底，取消必须原样抛出；
 * 3. [ErApiExchangeRateSource] 解析只接受有限正数，缺失/0/NaN 的币种直接跳过，
 *    绝不把 0 汇率交给管理器（0 会让该币种资产折算成 0）。
 */
class ExchangeRateSourceTest {

    // ---------- FallbackRateSource ----------

    @Test
    fun `主源成功时不请求兜底源`() = runTest {
        val primary = CountingRateSource(mapOf("USD" to 7.2))
        val fallback = CountingRateSource(mapOf("USD" to 7.0))

        val result = FallbackRateSource(primary, fallback).fetch()

        assertEquals(7.2, result!!["USD"]!!, 1e-9)
        assertEquals(1, primary.callCount)
        assertEquals("正常链路必须保持一次请求，不能并联兜底源", 0, fallback.callCount)
    }

    @Test
    fun `主源返回null时才请求兜底源`() = runTest {
        val primary = CountingRateSource(null)
        val fallback = CountingRateSource(mapOf("USD" to 7.0))

        val result = FallbackRateSource(primary, fallback).fetch()

        assertEquals(7.0, result!!["USD"]!!, 1e-9)
        assertEquals(1, primary.callCount)
        assertEquals(1, fallback.callCount)
    }

    @Test
    fun `主源返回空Map时也兜底`() = runTest {
        val primary = CountingRateSource(emptyMap())
        val fallback = CountingRateSource(mapOf("HKD" to 0.92))

        val result = FallbackRateSource(primary, fallback).fetch()

        assertEquals(0.92, result!!["HKD"]!!, 1e-9)
        assertEquals(1, fallback.callCount)
    }

    @Test
    fun `主源抛异常时兜底且不向外抛`() = runTest {
        val primary = CountingRateSource(error = java.io.IOException("sina down"))
        val fallback = CountingRateSource(mapOf("EUR" to 7.8))

        val result = FallbackRateSource(primary, fallback).fetch()

        assertEquals(7.8, result!!["EUR"]!!, 1e-9)
        assertEquals(1, fallback.callCount)
    }

    @Test
    fun `主源取消必须原样抛出且不触发兜底`() = runBlocking {
        val primary = CountingRateSource(error = CancellationException("stop"))
        val fallback = CountingRateSource(mapOf("USD" to 7.0))

        var cancelled = false
        try {
            FallbackRateSource(primary, fallback).fetch()
        } catch (e: CancellationException) {
            cancelled = true
        }

        assertTrue("取消不能被当成『主源失败』吞掉", cancelled)
        assertEquals(0, fallback.callCount)
    }

    @Test
    fun `兜底源也失败时返回null`() = runTest {
        val primary = CountingRateSource(null)
        val fallback = CountingRateSource(error = java.io.IOException("er-api down"))

        val result = FallbackRateSource(primary, fallback).fetch()

        assertNull(result)
    }

    // ---------- ErApiExchangeRateSource.parse ----------

    @Test
    fun `从USD基准换算成外币兑人民币`() {
        val rates = ErApiExchangeRateSource().parse(
            """
            {"result":"success","rates":{"USD":1.0,"CNY":7.2,"EUR":0.9,"HKD":7.8,"JPY":0}}
            """.trimIndent()
        )

        assertEquals(7.2, rates!!["USD"]!!, 1e-9)
        assertEquals(8.0, rates["EUR"]!!, 1e-9)
        assertEquals(7.2 / 7.8, rates["HKD"]!!, 1e-9)
        // JPY 给了 0：必须跳过，不能写 0 汇率
        assertFalse(rates.containsKey("JPY"))
    }

    @Test
    fun `缺失币种与NaN负数一律跳过`() {
        val rates = ErApiExchangeRateSource().parse(
            """
            {"result":"success","rates":{"CNY":7.2,"USD":1.0,"GBP":-1,"AUD":0.0000000001}}
            """.trimIndent()
        )

        assertEquals(7.2, rates!!["USD"]!!, 1e-9)
        assertFalse(rates.containsKey("GBP"))
        // 很小的正数仍是正数（护栏由管理器统一做），这里只保证不是 0/负数
        assertTrue(rates["AUD"]!! > 0.0)
        assertFalse(rates.containsKey("EUR"))
        assertFalse(rates.containsKey("HKD"))
    }

    @Test
    fun `result非success或缺少CNY时返回null`() {
        assertNull(
            ErApiExchangeRateSource().parse(
                """{"result":"error","rates":{"CNY":7.2,"USD":1.0}}"""
            )
        )
        assertNull(
            ErApiExchangeRateSource().parse(
                """{"result":"success","rates":{"USD":1.0}}"""
            )
        )
        assertNull(ErApiExchangeRateSource().parse(""))
        assertNull(ErApiExchangeRateSource().parse("not-json"))
    }

    @Test
    fun `CNY为0或NaN时拒绝整份响应`() {
        assertNull(
            ErApiExchangeRateSource().parse(
                """{"result":"success","rates":{"CNY":0,"USD":1.0}}"""
            )
        )
        assertNull(
            ErApiExchangeRateSource().parse(
                """{"result":"success","rates":{"CNY":null,"USD":1.0}}"""
            )
        )
    }
}

/** 记录调用次数的假源；result/error 二选一，全程离线 */
private class CountingRateSource(
    private val result: Map<String, Double>? = null,
    private val error: Throwable? = null
) : RateSource {

    var callCount = 0
        private set

    override suspend fun fetch(): Map<String, Double>? {
        callCount++
        error?.let { throw it }
        return result
    }
}
