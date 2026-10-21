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
 * 以下四条补工程师没覆盖的点，其中两条是**缺陷见证**（witness）：
 *
 *  1. `ConcurrentHashMap.getOrPut` 竞态实证（确定性交错，非概率重试）：
 *     StockDataManager.breakerFor 用 getOrPut 建每源 breaker，stdlib 的 getOrPut
 *     非原子（get→defaultValue→putIfAbsent 且返回本地新建实例）——冷图 + 3 并发时
 *     可能出现「两个线程各持一个 breaker 实例」的状态分裂。
 *  2. 闭环：单波 3 次源级失败（=同步并发度）不熔禁——③ 修复的核心算术。
 *  3+4. **新浪 sink 消息碰撞见证**：SinaStockAPI.getRealtimeData 把批内任何异常
 *     （超时/连接失败/403 限流/空 body）都吞成「部分缓存结果」，无缓存时整体落空 →
 *     :213-215 统一抛出「未获取到股票数据，请检查股票代码」；单符号路径
 *     （getStockData :53-64）原样透出该消息。isSymbolLevelFailure 按文本命中 →
 *     判为符号级不计源级 → **新浪真实故障（无缓存场景）熔断器被饿死**。
 *     腾讯的同名消息只在「源正常应答且无此代码」时抛（TencentStockAPI:81-83），不受影响。
 *     这两条用例钉的是**当前（缺陷）行为**；工程师修复 sink 后应改为断言源级累计并熔禁。
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
        unmockkAll()
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
     * 缺陷见证 1（分类层）：新浪 sink 消息当前被判为符号级。
     * 该消息在无缓存场景下同时是「网络超时/连接失败/403 限流/空 body」的归宿
     * （SinaStockAPI.kt:180-190 吞异常 → :213-215 统一文案）——真故障被误判。
     */
    @Test
    fun `缺陷见证_新浪sink消息当前被判为符号级`() {
        // 当前实现：文本命中即符号级
        assertTrue(isSymbolLevelFailure(IOException("未获取到股票数据，请检查股票代码")))
        //  genuinely 源级的消息不受影响
        assertFalse(isSymbolLevelFailure(IOException("API请求频率过高，请稍后再试")))
        assertFalse(isSymbolLevelFailure(IOException("API返回空数据")))
        assertFalse(isSymbolLevelFailure(IOException("API响应错误: 403")))
    }

    /**
     * 缺陷见证 2（集成层）：模拟「新浪无缓存 + 网络故障」在真实链路上的表现形状
     * （getStockData 返回 sink 消息）——6 轮过去新浪熔断器仍是 CLOSED：被饿死。
     * 修复 SinaStockAPI sink（透出原始异常或改用专用异常类型）后，
     * 本用例应反过来断言「累计 5 次后 OPEN」。
     */
    @Test
    fun `缺陷见证_新浪无缓存网络故障_熔断器被饿死`() = runBlocking {
        mockkObject(SinaStockAPI)
        mockkObject(TencentStockAPI)
        // 真实链路形状：新浪批内异常被吞 → allResults 空 → sink 消息（见用例注释）
        coEvery { SinaStockAPI.getStockData(any()) } returns
            Result.failure(IOException("未获取到股票数据，请检查股票代码"))
        coEvery { TencentStockAPI.getStockData(any()) } returns
            Result.failure(IOException("未获取到股票数据，请检查股票代码"))

        repeat(6) {
            manager.getStockData("sh600000", forceRefresh = true, networkAvailable = true, preferredApi = ApiManager.API_SINA)
        }

        assertEquals(
            "缺陷见证：新浪真实故障（无缓存）6 轮仍未熔禁——熔断器被 sink 消息饿死",
            SourcePhase.CLOSED,
            manager.sourcePhaseForTesting(ApiManager.API_SINA)
        )
    }
}
