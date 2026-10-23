package com.example.stockdiary.network

import com.example.stockdiary.database.AssetType
import com.example.stockdiary.network.model.AssetPrice
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 行情门面里「稳定币 / 稳定币理财壳」取价路径，以及**币安熔断闸门接线**的回归。
 *
 * 前两条用例只覆盖稳定币（直接返回 1.0、不发网络请求），纯 JVM 即可跑通。
 * 后几条用假 [Interceptor] 驱动 [UnifiedPriceService.getCryptoPrice] 的真实请求路径，
 * 钉死「HTTP 451 地区封锁绝不能被当成成功」这条关键契约——它正是 2026-10-23 真机日志里
 * 暴露的 bug：451 被旧代码在 isSuccessful 检查前当成功处理，导致熔断永远顶不上地区封锁，
 * 地区封锁用户每轮同步都被 451 刷屏。
 */
class UnifiedPriceServiceTest {

    @Before
    fun resetGate() {
        // getCryptoPrice 会真去喂 BinanceHostGate；必须复位，否则漏给同 JVM 的其它测试类
        BinanceHostGate.resetForTesting()
    }

    @After
    fun resetGateAfter() {
        BinanceHostGate.resetForTesting()
    }

    // ---------- 稳定币：直接 1.0，不发网络 ----------

    @Test
    fun 现货稳定币_USDT_按1美元返回_不查网络() = runBlocking {
        val result = UnifiedPriceService.getPrice("USDT", AssetType.CRYPTO)
        assertTrue("USDT 取价应成功", result.isSuccess)
        val price = result.getOrNull()
        assertTrue(price is AssetPrice.Crypto)
        assertEquals(1.0, (price as AssetPrice.Crypto).data.price, 1e-9)
    }

    @Test
    fun 稳定币理财壳_LDUSDT_按1美元返回_存量数据自愈() = runBlocking {
        val result = UnifiedPriceService.getPrice("LDUSDT", AssetType.CRYPTO)
        assertTrue("LDUSDT 取价应成功（底层为稳定币）", result.isSuccess)
        val price = result.getOrNull()
        assertTrue(price is AssetPrice.Crypto)
        assertEquals(1.0, (price as AssetPrice.Crypto).data.price, 1e-9)
    }

    // ---------- 假 Interceptor：模拟币安 ticker/24hr 的各类「网络语义」 ----------

    /** 合法 200：含 lastPrice，能被 [UnifiedPriceService] 正常解析 */
    private class Ticker200Interceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val body = """{"lastPrice":"67000.00","priceChange":"100.00",""" +
                """"priceChangePercent":"0.15","highPrice":"68000.00",""" +
                """"lowPrice":"66000.00","volume":"100.0"}"""
            return Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }

    /** 地区封锁 451：主机可达但本用户被拒，body 带币安的地区限制提示 */
    private class Ticker451Interceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val body = """{"code":0,"msg":"Service unavailable from a restricted location."}"""
            return Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(451)
                .message("Unavailable For Legal Reasons")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }

    /** 业务错误 400：主机可达、符号非法，不应被当成「主机不可用」 */
    private class Ticker400Interceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val body = """{"code":-1121,"msg":"Invalid symbol"}"""
            return Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(400)
                .message("Bad Request")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }

    private fun fakeClient(interceptor: Interceptor): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(interceptor).build()

    // ---------- 契约：200 成功喂 onSuccess，451 喂 onNetworkFailure，400 喂 onSuccess ----------

    @Test
    fun `getCryptoPrice 200成功喂onSuccess并解析价格`() = runBlocking {
        val result = UnifiedPriceService.getCryptoPrice("BTC", client = fakeClient(Ticker200Interceptor()))
        assertTrue("200 应解析成功", result.isSuccess)
        assertEquals(67000.0, result.getOrNull()!!.price, 1e-9)
        // 200 是真正成功：连续失败计数清零（这里本来就是 0，断言 0 是哨兵，防止有人把 200 改成不记账）
        assertEquals(0, BinanceHostGate.consecutiveFailuresForTesting())
        assertEquals(Phase.CLOSED, BinanceHostGate.phaseForTesting())
    }

    @Test
    fun `getCryptoPrice 收到451地区封锁必须喂onNetworkFailure而非onSuccess`() = runBlocking {
        // 关键回归：旧代码在 isSuccessful 检查前就调 onSuccess，把 451 当成功清零计数。
        // 这里断言 451 喂的是 onNetworkFailure（计数 +1，而非清零到 0）。
        val result = UnifiedPriceService.getCryptoPrice("BTC", client = fakeClient(Ticker451Interceptor()))
        assertTrue("451 业务上仍是失败", result.isFailure)

        assertEquals(
            "451 是主机可达但本用户被地区封锁，等同「主机不可用」：必须喂 onNetworkFailure（计数 +1）",
            1,
            BinanceHostGate.consecutiveFailuresForTesting()
        )
        assertEquals(Phase.CLOSED, BinanceHostGate.phaseForTesting()) // 仅 1 次，未达阈值 5，不熔断
        assertFalse("单发 451 未达阈值，UI 不应显示暂不可达", BinanceHostGate.isUnavailable())
    }

    @Test
    fun `getCryptoPrice 400业务错误仍喂onSuccess不误熔断`() = runBlocking {
        // 400（符号非法）是主机可达的正常业务错误，不应被当成「主机不可用」，必须喂 onSuccess。
        val result = UnifiedPriceService.getCryptoPrice("BTC", client = fakeClient(Ticker400Interceptor()))
        assertTrue("400 取不到价，业务上失败", result.isFailure)

        assertEquals(
            "400 主机可达，必须复位计数（喂 onSuccess），绝不能记成网络失败",
            0,
            BinanceHostGate.consecutiveFailuresForTesting()
        )
        assertEquals(Phase.CLOSED, BinanceHostGate.phaseForTesting())
    }

    @Test
    fun `getCryptoPrice 连续451达阈值触发熔断短路`() = runBlocking {
        // 模拟地区封锁用户：连续 5 次 451 必须触发熔断（OPEN），之后 tryAcquireProbe 短路、
        // UI 显示「币安暂不可达」，而不是每轮每个币都白打一次 451、还各计一次同步失败。
        repeat(BinanceHostGate.FAIL_THRESHOLD) {
            UnifiedPriceService.getCryptoPrice("BTC", client = fakeClient(Ticker451Interceptor()))
        }
        assertEquals(Phase.OPEN, BinanceHostGate.phaseForTesting())
        assertTrue("连续 451 达阈值后应显示「币安暂不可达」", BinanceHostGate.isUnavailable())
        assertEquals(1, BinanceHostGate.blockCountForTesting())
    }
}
