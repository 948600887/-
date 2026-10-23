package com.example.stockdiary.network

import com.example.stockdiary.common.Result
import com.example.stockdiary.data.price.isSymbolLevelFailure
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking

/**
 * 「停牌」分类契约的回归。
 *
 * ## 为什么值得钉死
 * 响应里**有这只票但报价无效**（现价与昨收都是 0）时，它必须走「符号级」语义：
 * - 批量响应里混着停牌票时，该代码要进 `suspendedOut`，由 `getStockData` 判成
 *   [QuoteSuspendedException]——那是「不计失败」分支；
 * - 只有它一只、整批无命中时，失败文案必须是 `isSymbolLevelFailure` 认得出的那条，
 *   **不能**被当成源级失败喂给 `SourceCircuitBreaker`（累积 5 次会误禁整个新浪源）。
 *
 * ## 2026-10-23 相关修复
 * 停牌代码集合原是**进程级共享字段**：价格同步 3 并发扇出时，后进来的调用一上来就
 * `clear()`，会把在途调用刚登记的停牌代码抹掉 → 那只票改走 `getStockData` 里另一条
 * 文案（源级）→ 喂熔断器；并发 add/clear 在非线程安全的 LinkedHashSet 上还可能抛 CME。
 * 现改为每次调用一份（`getRealtimeData` 的 `suspendedOut` 参数）。
 */
class SinaStockSuspendedClassificationTest {

    @After
    fun tearDown() {
        SinaStockAPI.resetHttpClientForTesting()
        SinaStockAPI.invalidateCache()
    }

    /** 假 OkHttpClient：把给定响应体当作 HTTP 200 返回，驱动真实的解析与分类链路 */
    private fun fakeClientReturning(body: String): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain: Interceptor.Chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody("text/plain".toMediaType()))
                    .build()
            }
            .build()

    /** 一条 32 字段的新航行情报价行 */
    private fun quoteLine(code: String, name: String, prevClose: String, price: String): String {
        val fields = List(32) { i ->
            when (i) {
                0 -> name
                2 -> prevClose
                3 -> price
                else -> "0"
            }
        }
        return """var hq_str_$code="${fields.joinToString(",")}";"""
    }

    @Test
    fun `批量响应混有停牌票_停牌码进 suspendedOut_不混进报价结果`() = runBlocking {
        val body = quoteLine("sh600001", "PRICED", "10", "12") + "\n" +
            quoteLine("sh600002", "SUSPENDED", "0", "0")

        SinaStockAPI.httpClientForTesting = fakeClientReturning(body)
        SinaStockAPI.invalidateCache()

        val suspendedOut = ConcurrentHashMap.newKeySet<String>()
        val result = SinaStockAPI.getRealtimeData(
            listOf("sh600001", "sh600002"),
            suspendedOut = suspendedOut
        )

        assertTrue("有行情的票应让整批成功", result is Result.Success)
        val map = (result as Result.Success).data
        assertTrue("有行情的票应在结果里", map.containsKey("sh600001"))
        assertTrue("停牌票不该出现在报价结果里", !map.containsKey("sh600002"))
        assertTrue(
            "停牌票必须进 suspendedOut（否则会被当成源级失败喂熔断器）",
            suspendedOut.contains("sh600002")
        )
    }

    @Test
    fun `单只停牌票_失败文案是符号级_不喂熔断器`() = runBlocking {
        SinaStockAPI.httpClientForTesting =
            fakeClientReturning(quoteLine("sh600001", "SUSPENDED", "0", "0"))
        SinaStockAPI.invalidateCache()

        val result = SinaStockAPI.getStockData("sh600001")

        assertTrue("无有效报价应失败", result is Result.Error)
        val error = (result as Result.Error).exception
        val ex = error as? Exception
        assertTrue(
            "单只停牌票的失败必须判为符号级，否则会累计进源级熔断、可能误禁整个新浪源；实际=$error",
            ex != null && isSymbolLevelFailure(ex)
        )
    }
}
