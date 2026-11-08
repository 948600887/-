package com.example.stockdiary.data

import android.content.Context
import com.example.stockdiary.data.repository.DividendRepository
import com.example.stockdiary.network.FundDividendApiService
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 天天基金 514 限流的「信号链」回归：
 *
 * Service(otcRateLimitSignal 只增计数) → Repository(prefetchDividends 轮首/轮末做差
 * → _otcRateLimitedThisCycle) → ViewModel(consumeOtcRateLimitWarning) → Snackbar。
 *
 * 不做整条 UI 链路的 Instrumented 测试（太重），只钉死「限流发生了 ⇒ consume 返回 true」
 * 与「Service 撞 514 确实推进限流计数」这两点——它们是信号链的两个关键节点。
 * 用只增计数而非共享 Boolean，是为了让并发的两轮预取互不清零。
 */
class DividendOtcRateLimitTest {

    private val context = mockk<Context>(relaxed = true)
    private val repository = spyk(DividendRepository.getInstance(context))

    @After
    fun tearDown() {
        FundDividendApiService.rateLimitedUntil = 0L
        unmockkAll()
    }

    @Test
    fun `prefetchDividends 把场外 514 信号透传到 consumeOtcRateLimitWarning`() = runBlocking {
        // 用桩模拟「场外基金抓取撞 514」：getDividendsBySymbol 被调用时推进限流计数，
        // 这正是 FundDividendApiService 在两个 514/429 分支里做的事。
        coEvery { repository.getDividendsBySymbol(any(), any(), any()) } coAnswers {
            FundDividendApiService.markOtcRateLimitHit()
            emptyList()
        }

        // 全选场外基金：批量预取直接跳过（不走东财），只走 prefetchDividends 的逐条补抓路径，
        // 整条路不碰网络与 Room。
        val symbols = listOf("fund_010001", "fund_010002", "fund_010003")
        repository.prefetchDividends(symbols, forceRefresh = true, timeBudgetMs = 600_000L)

        // 信号链透传：本轮确实撞过限流 ⇒ 消费返回 true
        assertTrue(repository.consumeOtcRateLimitWarning())
        // 消费后清零：再消费为 false，不会重复提示
        assertFalse(repository.consumeOtcRateLimitWarning())
    }

    @Test
    fun `consumeOtcRateLimitWarning 未撞限流时返回 false`() {
        assertFalse(repository.consumeOtcRateLimitWarning())
    }

    @Test
    fun `FundDividendApiService 撞 HTTP 514 时推进 otcRateLimitSignal`() = runBlocking {
        val service = FundDividendApiService()
        // 用拦截器把任何请求都短路成 514，避免真的去打天天基金
        val mockClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(514)
                .message("rate limited")
                .body("".toResponseBody("text/plain".toMediaType()))
                .build()
        }).build()
        service.client = mockClient

        // 信号是只增计数，无法清零；用前后差值判断本轮是否推进。
        val before = FundDividendApiService.otcRateLimitSignal
        val result = service.getFundDividendHistory("110011")

        // 514 ⇒ 抓取失败（保留既有的 60s 进程退避语义由上层负责），且信号计数被推进
        assertFalse(result.isSuccess)
        assertTrue(
            "撞 514 时应推进 otcRateLimitSignal 供 Repository 做差",
            FundDividendApiService.otcRateLimitSignal > before
        )
    }
}
