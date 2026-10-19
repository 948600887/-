package com.example.stockdiary.network

import com.example.stockdiary.common.Result
import com.example.stockdiary.data.price.SkillDataSource
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 妙想Skill数据源进程级退避（P7）的回归测试。
 *
 * 背景：弱网/限流时每只股票各自走 StockDataManager → NetworkRetryHelper 的 3 次重试，
 * 请求短时间堆叠成雪崩；连续 3 次失败后整源还被禁用 30 分钟、把流量甩给 Sina/Tencent
 * 放大另一源。EastmoneySkillApi 因此增加进程级 60s 退避（skillHostBlockedUntil，
 * 与 FundDividendApiService.rateLimitedUntil 同思路），SkillDataSource 在退避窗口内
 * 顶部短路、零网络请求。
 *
 * 测试手法沿用 FundDividendApiServiceTest：把 client 换成 MockK 假客户端；
 * 「爆炸客户端」一旦被调用就抛 AssertionError，用来证明短路确实拦在请求之前。
 */
class EastmoneySkillApiBackoffTest {

    private val dataSource = SkillDataSource()
    private val originalClient = EastmoneySkillApi.client
    private val originalApiKey = EastmoneySkillApi.apiKey

    @After
    fun resetBackoff() {
        // 防止退避窗口与注入的 client/apiKey 污染相邻用例
        EastmoneySkillApi.client = originalClient
        EastmoneySkillApi.apiKey = originalApiKey
        EastmoneySkillApi.skillHostBlockedUntil = 0L
    }

    /**
     * 退避期间顶部短路：把退避窗口设为未来时间，注入「一旦被调用就抛错」的客户端，
     * 调用必须直接失败，**且绝不发起任何网络请求**（否则 AssertionError 使测试失败）。
     */
    @Test
    fun `getStockData 退避期间短路不发起网络请求`() = runBlocking {
        EastmoneySkillApi.apiKey = "test-key"
        EastmoneySkillApi.skillHostBlockedUntil = System.currentTimeMillis() + 60_000L

        val explodingClient = mockk<OkHttpClient>(relaxed = true)
        every { explodingClient.newCall(any()) } throws AssertionError("退避期间不应发起网络请求")
        EastmoneySkillApi.client = explodingClient

        val result = dataSource.getStockData("sh600519")

        assertTrue("退避期间应直接失败", result is Result.Error)
    }

    /**
     * 命中 403 触发退避，随后调用被短路：
     * 1) 注入返回 403 的假客户端，调用一次 → 断言失败（并设下退避窗口）；
     * 2) 换上「一旦被调用就抛错」的客户端再次调用 → 必须被顶部短路，否则抛 AssertionError。
     */
    @Test
    fun `getStockData 命中403触发退避后后续调用被短路`() = runBlocking {
        EastmoneySkillApi.apiKey = "test-key"
        EastmoneySkillApi.skillHostBlockedUntil = 0L

        val rateLimitResp = Response.Builder()
            .request(Request.Builder().url("https://mkapi2.dgcfs.com/finskillshub/api/claw/query").build())
            .protocol(Protocol.HTTP_1_1)
            .code(403)
            .message("Forbidden")
            .body(ResponseBody.create("application/json".toMediaTypeOrNull(), ""))
            .build()
        val call = mockk<Call>()
        every { call.execute() } returns rateLimitResp
        val client = mockk<OkHttpClient>(relaxed = true)
        every { client.newCall(any()) } returns call
        EastmoneySkillApi.client = client

        // 第一次：真实命中 403
        val first = dataSource.getStockData("sh600519")
        assertTrue("命中 403 应失败", first is Result.Error)
        assertTrue(
            "命中 403 应进入退避窗口",
            EastmoneySkillApi.skillHostBlockedUntil > System.currentTimeMillis()
        )

        // 第二次：换上爆炸客户端，必须被短路（若触达网络则抛 AssertionError 使测试失败）
        val explodingClient = mockk<OkHttpClient>(relaxed = true)
        every { explodingClient.newCall(any()) } throws AssertionError("退避期间不应再次发起网络请求")
        EastmoneySkillApi.client = explodingClient

        val second = dataSource.getStockData("sh600519")
        assertTrue("退避期间应被短路直接失败", second is Result.Error)
    }

    /** 批量路径同样在退避窗口内短路、零网络 */
    @Test
    fun `getStockDataBatch 退避期间短路不发起网络请求`() = runBlocking {
        EastmoneySkillApi.apiKey = "test-key"
        EastmoneySkillApi.skillHostBlockedUntil = System.currentTimeMillis() + 60_000L

        val explodingClient = mockk<OkHttpClient>(relaxed = true)
        every { explodingClient.newCall(any()) } throws AssertionError("退避期间不应发起网络请求")
        EastmoneySkillApi.client = explodingClient

        val result = dataSource.getStockDataBatch(listOf("sh600519", "sz000001"))

        assertTrue("退避期间应直接失败", result is Result.Error)
    }
}
