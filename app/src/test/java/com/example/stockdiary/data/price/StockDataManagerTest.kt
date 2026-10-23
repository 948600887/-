package com.example.stockdiary.data.price

import android.content.Context
import com.example.stockdiary.common.Result
import com.example.stockdiary.network.EastmoneySkillApi
import com.example.stockdiary.network.NetworkRetryHelper
import com.example.stockdiary.network.SinaStockAPI
import com.example.stockdiary.network.TencentStockAPI
import com.example.stockdiary.network.model.StockData
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [StockDataManager] 源级熔断「误禁雪崩」修复回归。
 *
 * 覆盖四件事里的三件半（SourceCircuitBreaker 的三态机械本身由 [SourceCircuitBreakerTest] 钉）：
 *  1. 符号级确定性失败（「未获取到股票数据，请检查股票代码」）不计入源级禁用；
 *  2. 源级失败（限流「频率过高」等）累计达阈值照常禁用；
 *  3. 妙想未配置 Key 移出候选链；用户显式选妙想则不静默移除；
 *  4. 分类纯函数 [isSymbolLevelFailure] 的各分支。
 *
 * 集成用例把 apiKey 置空以过滤妙想，只桩新浪/腾讯两个 object 数据源，避免碰网络。
 */
class StockDataManagerTest {

    private val context = mockk<Context>(relaxed = true)
    private lateinit var manager: StockDataManager
    private var originalKey: String = ""

    @Before
    fun setUp() {
        // PriceCacheManager.init(context) 会读 filesDir；给个真实临时目录，避免 null 路径
        every { context.filesDir } returns File(System.getProperty("java.io.tmpdir"))
        originalKey = EastmoneySkillApi.apiKey
        manager = StockDataManager(context)
        manager.resetForTesting()
    }

    @After
    fun tearDown() {
        EastmoneySkillApi.apiKey = originalKey
        SinaStockAPI.resetHttpClientForTesting()
        SinaStockAPI.invalidateCache()
        unmockkAll()
    }

    // ---------- 符号级 / 源级分类 ----------

    @Test
    fun `符号级确定性失败被判为符号级_源级失败判为源级`() {
        // 符号级：代码不在此源支持范围（源正常应答、只是没这个代码的数据）
        assertTrue(isSymbolLevelFailure(IOException("未获取到股票数据，请检查股票代码")))
        // 源级：源理应有但没给（空返回 / 网络 / 限流 / 无效价格）——要计入禁用
        assertFalse(isSymbolLevelFailure(IOException("API返回空数据")))
        assertFalse(isSymbolLevelFailure(IOException("无法获取股票数据: sh600000")))
        assertFalse(isSymbolLevelFailure(IOException("API请求频率过高，请稍后再试")))
        assertFalse(isSymbolLevelFailure(IllegalStateException("新浪财经返回无效价格: 0.0")))
        // 分不清的（含 null message）按源级计（保守）
        assertFalse(isSymbolLevelFailure(RuntimeException()))
    }

    // ---------- 候选链：全自动顺序（2026-10-23 去掉「行情数据源」设置，不再有用户偏好） ----------

    @Test
    fun `妙想未配置 Key_自动移出候选链_顺序为新浪腾讯`() {
        EastmoneySkillApi.apiKey = ""
        val names = manager.candidateSourceNames()
        assertFalse("未配置 Key 的妙想不该白试", names.contains(ApiManager.API_SKILL))
        assertEquals(listOf(ApiManager.API_SINA, ApiManager.API_TENCENT), names)
    }

    @Test
    fun `妙想已配置 Key_自动置首_顺序为妙想新浪腾讯`() {
        EastmoneySkillApi.apiKey = "test-key"
        val names = manager.candidateSourceNames()
        assertEquals(
            listOf(ApiManager.API_SKILL, ApiManager.API_SINA, ApiManager.API_TENCENT),
            names
        )
    }

    @Test
    fun `候选链顺序稳定_不受任何偏好影响`() {
        // 全自动：同样的环境连查两次结果必须一致。
        // 从前顺序会被 SharedPreferences 里的用户偏好改变（设置页可选新浪/腾讯/妙想），
        // 该设置已去除，这里钉死「顺序只取决于妙想 Key 是否配置」。
        EastmoneySkillApi.apiKey = ""
        val first = manager.candidateSourceNames()
        val second = manager.candidateSourceNames()
        assertEquals(first, second)
        assertEquals(ApiManager.API_SINA, first.first())
    }

    // ---------- 集成：符号级失败不熔禁 ----------

    @Test
    fun `符号级确定性失败不计入_远超阈值也不熔禁`() = runBlocking {
        EastmoneySkillApi.apiKey = "" // 过滤妙想，只桩新浪/腾讯
        mockkObject(SinaStockAPI)
        mockkObject(TencentStockAPI)
        coEvery { SinaStockAPI.getStockData(any()) } returns
            Result.failure(IOException("未获取到股票数据，请检查股票代码"))
        coEvery { TencentStockAPI.getStockData(any()) } returns
            Result.failure(IOException("未获取到股票数据，请检查股票代码"))

        // 6 次远超阈值 5：若符号级被计入，早该熔禁了
        repeat(6) {
            manager.getStockData("sh600000", forceRefresh = true, networkAvailable = true)
        }

        assertEquals(SourcePhase.CLOSED, manager.sourcePhaseForTesting(ApiManager.API_SINA))
        assertEquals(SourcePhase.CLOSED, manager.sourcePhaseForTesting(ApiManager.API_TENCENT))
    }

    // ---------- 集成：源级失败计入并熔禁 ----------

    @Test
    fun `源级失败累计达阈值_熔禁数据源`() = runBlocking {
        EastmoneySkillApi.apiKey = ""
        mockkObject(SinaStockAPI)
        mockkObject(TencentStockAPI)
        // 「频率过高」= 限流：不可重试（跑得快）且是源级失败（要计）
        coEvery { SinaStockAPI.getStockData(any()) } returns
            Result.failure(IOException("API请求频率过高，请稍后再试"))
        coEvery { TencentStockAPI.getStockData(any()) } returns
            Result.failure(IOException("API请求频率过高，请稍后再试"))

        repeat(SourceCircuitBreaker.FAIL_THRESHOLD) {
            manager.getStockData("sh600000", forceRefresh = true, networkAvailable = true)
        }

        // 累计 5 次源级失败 → 熔禁
        assertEquals(SourcePhase.OPEN, manager.sourcePhaseForTesting(ApiManager.API_SINA))
    }

    /**
     * 回归（QA 缺陷见证的工程师侧对应）：新浪无缓存 + **网络超时**，走**真实** SinaStockAPI 路径
     * （mock OkHttp 层抛 SocketTimeoutException，不 mock getStockData/getRealtimeData——内部 sink
     * 转换必须真实执行）→ 透出源级异常 → 累计 5 次熔禁。
     *
     * 这是 ③ 主体测试（mock 在 getStockData 接缝）覆盖不到的点：那绕过了吞异常的批处理代码。
     * 用 NetworkRetryHelper 旁路跳过重试延迟，聚焦「sink 透出 → 分类 → 熔禁」链路本身。
     */
    @Test
    fun `回归_新浪无缓存网络超时_真实Sink路径_5次熔禁`() = runBlocking {
        mockkObject(NetworkRetryHelper)
        coEvery { NetworkRetryHelper.executeWithRetry<StockData>(any(), any(), any(), any(), any()) } coAnswers {
            @Suppress("UNCHECKED_CAST")
            (args[4] as suspend () -> Result<StockData>)()
        }

        val call = mockk<Call>()
        every { call.execute() } throws SocketTimeoutException("timeout")
        val httpClient = mockk<OkHttpClient>()
        every { httpClient.newCall(any()) } returns call
        SinaStockAPI.httpClientForTesting = httpClient
        SinaStockAPI.invalidateCache()
        mockkObject(TencentStockAPI)
        coEvery { TencentStockAPI.getStockData(any()) } returns Result.failure(IOException("腾讯也不通"))

        repeat(5) {
            manager.getStockData("sh600000", forceRefresh = true, networkAvailable = true)
        }

        assertEquals(SourcePhase.OPEN, manager.sourcePhaseForTesting(ApiManager.API_SINA))
    }
}
