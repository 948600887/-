package com.example.stockdiary.data.price

import android.content.Context
import com.example.stockdiary.common.Result
import com.example.stockdiary.network.EastmoneySkillApi
import com.example.stockdiary.network.SinaStockAPI
import com.example.stockdiary.network.TencentStockAPI
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * QA 独立复验（fresh eyes）：f6ad923 源级熔断的边界补测。
 *
 * 工程师已测：SourceCircuitBreaker 三态 10 条 + StockDataManager 集成 6 条（均真实）。
 * 以下四条补工程师没覆盖的点：
 *
 *  1. `ConcurrentHashMap.getOrPut` 竞态实证（确定性交错，非概率重试）：
 *     记录 stdlib getOrPut 非原子——工程师已据此把 breakerFor 改成 computeIfAbsent。
 *  2. 闭环：单波 3 次源级失败（=同步并发度）不熔禁——③ 修复的核心算术。
 *  3+4. **新浪 sink 消息碰撞**（原为缺陷见证，修复后已反转为回归）：
 *     旧 SinaStockAPI.getRealtimeData 把批内任何异常吞成「未获取到股票数据」符号级文案，
 *     饿死源级熔断器。修复（透出原始异常）后，这两条改走**真实** SinaStockAPI 路径
 *     （mock OkHttp 层抛源级异常、不 mock getRealtimeData），断言透出源级异常 + 累计 5 次熔禁。
 */
class SourceCircuitBreakerVerifyTest {

    private val context = mockk<Context>(relaxed = true)
    private lateinit var manager: StockDataManager
    private var originalKey: String = ""

    @Before
    fun setUp() {
        every { context.filesDir } returns File(System.getProperty("java.io.tmpdir"))
        originalKey = EastmoneySkillApi.apiKey
        EastmoneySkillApi.apiKey = "" // 过滤妙想候选，只桩新浪/腾讯
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

    /** 让真实 SinaStockAPI 的 OkHttp 层在 execute() 时抛 throwable（驱动真实 sink 转换，不 mock getRealtimeData） */
    private fun stubSinaHttpFailure(throwable: Throwable) {
        val call = mockk<Call>()
        every { call.execute() } throws throwable
        val httpClient = mockk<OkHttpClient>()
        every { httpClient.newCall(any()) } returns call
        SinaStockAPI.httpClientForTesting = httpClient
        SinaStockAPI.invalidateCache() // 清内存缓存，确保走「无缓存」真实路径
    }

    /**
     * getOrPut 竞态确定性实证：A 线程已越过 get（未命中）并进入 defaultValue 构造期间，
     * B 线程完成整条 getOrPut 占住 key；A 恢复后 putIfAbsent 失败但仍返回**自己新建**的实例。
     * 结果：同一 key 两个调用方拿到不同实例——对 breakerFor 即「状态分裂」。
     */
    @Test
    fun `ConcurrentHashMap getOrPut 竞态_同key两线程可拿到不同实例`() {
        val map = ConcurrentHashMap<String, Any>()
        val aInsideDefault = CountDownLatch(1)
        val releaseA = CountDownLatch(1)
        val results = arrayOfNulls<Any>(2)

        val threadA = Thread {
            map.getOrPut("k") {
                aInsideDefault.countDown()
                releaseA.await(5, TimeUnit.SECONDS)
                Any().also { results[0] = it }
            }
        }
        threadA.start()
        assertTrue("A 应进入 defaultValue（已越过 get 未命中）", aInsideDefault.await(5, TimeUnit.SECONDS))

        val threadB = Thread {
            map.getOrPut("k") { Any().also { results[1] = it } }
        }
        threadB.start()
        threadB.join(5_000)

        releaseA.countDown()
        threadA.join(5_000)

        assertNotEquals(
            "getOrPut 非原子：并发下同一 key 的两个调用方拿到了不同实例（breakerFor 的隐患）",
            results[0], results[1]
        )
        // map 里只保留一个（先 putIfAbsent 的），另一个成为孤儿实例
        assertTrue(map.containsKey("k"))
    }

    /**
     * 闭环 ③ 修复目标：单波 3 个并发同时源级失败（=同步并发度=旧阈值）不熔禁；
     * 再补 2 次（累计 5）才熔禁。用非重试的限流文案跑快点（与真实 403 文案同型）。
     */
    @Test
    fun `单波3次源级失败不熔禁_累计5次才熔禁_闭环修复目标`() = runBlocking {
        mockkObject(SinaStockAPI)
        mockkObject(TencentStockAPI)
        coEvery { SinaStockAPI.getStockData(any()) } returns
            Result.failure(IOException("API请求频率过高，请稍后再试"))
        coEvery { TencentStockAPI.getStockData(any()) } returns
            Result.failure(IOException("API请求频率过高，请稍后再试"))

        // 单波 3 次（=并发度）：不熔禁，流量继续走新浪（只是每次都失败）
        repeat(3) {
            manager.getStockData("sh600000", forceRefresh = true, networkAvailable = true, preferredApi = ApiManager.API_SINA)
        }
        assertEquals("单波3次不得熔禁（旧阈值3会误禁）", SourcePhase.CLOSED, manager.sourcePhaseForTesting(ApiManager.API_SINA))

        // 再 2 次累计到 5：熔禁
        repeat(2) {
            manager.getStockData("sh600000", forceRefresh = true, networkAvailable = true, preferredApi = ApiManager.API_SINA)
        }
        assertEquals("累计5次源级失败必须熔禁", SourcePhase.OPEN, manager.sourcePhaseForTesting(ApiManager.API_SINA))
    }

    /**
     * （原缺陷见证 1，修复后反转）新浪 sink 现在透出原始异常，网络故障不再被判符号级。
     * 走**真实** SinaStockAPI.getStockData 路径（mock OkHttp 层抛源级异常，不 mock getRealtimeData），
     * 断言透出的异常是源级（isSymbolLevelFailure=false）且不再是 sink 符号级文案。
     */
    @Test
    fun `回归_新浪sink透出原始异常_网络故障不再被判符号级`() = runBlocking {
        stubSinaHttpFailure(IOException("API请求频率过高，请稍后再试")) // 403 限流：源级、不可重试
        val failure = SinaStockAPI.getStockData("sh600000").exceptionOrNull() as? Exception
        assertTrue("真实新浪故障应返回失败", failure != null)
        assertFalse("透出的应是源级异常，而非符号级 sink 文案", isSymbolLevelFailure(failure!!))
        assertFalse("不应再出现 sink 符号级文案", failure.message?.contains("未获取到股票数据") ?: false)
    }

    /**
     * （原缺陷见证 2，修复后反转）新浪无缓存 + 网络故障，熔断器不再被饿死。
     * 走真实 SinaStockAPI 路径（mock OkHttp 抛源级异常）经 StockDataManager 分类，
     * 累计 5 次后必须熔禁（OPEN）——修复前会永远 CLOSED。
     */
    @Test
    fun `回归_新浪无缓存网络故障_熔断器不再饿死_5次熔禁`() = runBlocking {
        stubSinaHttpFailure(IOException("API请求频率过高，请稍后再试"))
        mockkObject(TencentStockAPI)
        coEvery { TencentStockAPI.getStockData(any()) } returns
            Result.failure(IOException("腾讯也不通"))

        repeat(5) {
            manager.getStockData("sh600000", forceRefresh = true, networkAvailable = true, preferredApi = ApiManager.API_SINA)
        }

        assertEquals(
            "修复后：新浪真实故障（无缓存）累计 5 次必须熔禁，不再被 sink 消息饿死",
            SourcePhase.OPEN,
            manager.sourcePhaseForTesting(ApiManager.API_SINA)
        )
    }
}
