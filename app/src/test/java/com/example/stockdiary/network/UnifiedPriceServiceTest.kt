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
        // 451 喂的是 REGION_BLOCKED：据此熔断即粘死，之后不再每轮空探（灭掉无限 5min 循环）。
        assertTrue(
            "连续 451 据此熔断后必须打粘性地区封锁标记，永久拒探测",
            BinanceHostGate.regionBlockedForTesting()
        )
    }

    @Test
    fun `getCryptoPrice 地区封锁熔断后冷却过期仍不空探`() = runBlocking {
        // 连续 451 熔断并粘死；即便冷却早已过期，getCryptoPrice 也不应再对各币发 451 探测
        // （否则地区封锁用户每轮同步仍被 451 刷屏）。用一个「先 451 后 200」的拦截器验证：
        // 粘死后，即便探测器后续换成可用节点，本次进程内也不会被放行去探测。
        repeat(BinanceHostGate.FAIL_THRESHOLD) {
            UnifiedPriceService.getCryptoPrice("BTC", client = fakeClient(Ticker451Interceptor()))
        }
        assertTrue(BinanceHostGate.regionBlockedForTesting())

        // blockedUntil 早已过期（用远超冷却的 now 调 tryAcquireProbe，模拟下一轮同步），
        // 粘死状态下必须仍拒绝——这就是「不再无限空探」的钉死点。
        val longAfter = System.currentTimeMillis() + BinanceHostGate.COOLDOWN_MS * 100
        assertFalse(
            "地区封锁粘死后，冷却过期也不该再对币安发探测请求",
            BinanceHostGate.tryAcquireProbe(longAfter)
        )
    }

    // ---------- 候选交易对重试（WBETH 修复的核心契约） ----------

    /**
     * 主候选 400（非法符号）、兜底候选 200 命中：模拟 WBETH 的真实场景——
     * WBETH 被误判成已是交易对，主候选 `WBETH` 在币安回 400，必须自动落到
     * 兜底 `WBETHUSDT` 取到价。
     *
     * 钉死这条：2026-10-24 真机日志里 WBETH 连续多轮同步都失败（币安 -1121 Invalid symbol），
     * 根因就是没有这道兜底。修复后它应成功且只多花一个请求（不会让正常币也重试）。
     */
    private class TickerWbethRetryInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val symbol = chain.request().url.queryParameter("symbol") ?: ""
            return if (symbol == "WBETHUSDT") {
                // 兜底候选命中：返回合法 200
                val body = """{"lastPrice":"2700.00","priceChange":"10.00",""" +
                    """"priceChangePercent":"0.37","highPrice":"2800.00",""" +
                    """"lowPrice":"2600.00","volume":"50.0"}"""
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            } else {
                // 主候选（WBETH 等）非法符号：400，触发重试
                val body = """{"code":-1121,"msg":"Invalid symbol"}"""
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(400)
                    .message("Bad Request")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
        }
    }

    @Test
    fun `getCryptoPrice WBETH主候选400兜底WBETHUSDT200命中`() = runBlocking {
        val result = UnifiedPriceService.getCryptoPrice("WBETH", client = fakeClient(TickerWbethRetryInterceptor()))
        assertTrue("主候选 400 后必须落到 WBETHUSDT 取到价", result.isSuccess)
        assertEquals(2700.0, result.getOrNull()!!.price, 1e-9)
        // 兜底命中是真正的 2xx 成功：连续失败计数清零（不被 400 误判成网络失败）
        assertEquals(0, BinanceHostGate.consecutiveFailuresForTesting())
        assertEquals(Phase.CLOSED, BinanceHostGate.phaseForTesting())
    }

    /**
     * 锁仓路径的 WBETH 兜底：LDWBETH 剥出底层 WBETH，底层同样以报价币 ETH 结尾被误判成交易对，
     * 必须也走 cryptoSymbolCandidates 落到 WBETHUSDT 兜底取到价。钉死 5594703 只修了非锁仓分支、
     * 漏了锁仓分支的回归（2026-10-26 真机日志 LDWBETH 连续多轮同步 400、刷新「失败 3」之一）。
     */
    @Test
    fun `getCryptoPrice LDWBETH锁仓底层WBETH主候选400兜底WBETHUSDT200命中`() = runBlocking {
        val result = UnifiedPriceService.getCryptoPrice("LDWBETH", client = fakeClient(TickerWbethRetryInterceptor()))
        assertTrue("锁仓底层 WBETH 主候选 400 后必须落到 WBETHUSDT 取到价", result.isSuccess)
        assertEquals(2700.0, result.getOrNull()!!.price, 1e-9)
        // 兜底命中是真正的 2xx 成功：连续失败计数清零（不被 400 误判成网络失败）
        assertEquals(0, BinanceHostGate.consecutiveFailuresForTesting())
        assertEquals(Phase.CLOSED, BinanceHostGate.phaseForTesting())
    }

    @Test
    fun `getCryptoPrice 用户填错符号两个候选都400仍真实失败`() = runBlocking {
        // SHIB2USDT 是错误符号（真实是 SHIBUSDT）：primary=SHIB2USDT、fallback=SHIB2USDTUSDT
        // 都非法。两个都 400 时不能误以为成功，必须如实失败，让用户自己改对代码。
        val result = UnifiedPriceService.getCryptoPrice("SHIB2USDT", client = fakeClient(Ticker400Interceptor()))
        assertTrue("填错的符号无法猜对，必须失败", result.isFailure)
        // 400 是主机可达的正常业务错误：不喂 onNetworkFailure，计数保持 0
        assertEquals(0, BinanceHostGate.consecutiveFailuresForTesting())
        assertEquals(Phase.CLOSED, BinanceHostGate.phaseForTesting())
    }
}
