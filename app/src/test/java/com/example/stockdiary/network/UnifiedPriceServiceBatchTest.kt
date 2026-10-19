package com.example.stockdiary.network

import com.example.stockdiary.common.Result
import com.example.stockdiary.data.price.StockDataManager
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.network.model.AssetPrice
import com.example.stockdiary.network.model.StockData
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P3（STOCK/场内基金批量取价）的安全不变量回归。
 *
 * P3 把「逐标的各打一次」的 N 次请求，合并成走新浪批量接口的 ~⌈N/20⌉ 次请求。
 * 这一改动的唯一红线是：**绝不增加请求量**（项目历史上有「静默请求风暴」把进程
 * 顶到被系统回收、零崩溃报告的先例）。下面三个用例从两个方向钉死这条红线：
 *
 * 1. [getPrice] 收到批量回填的现成价（batchResult）时必须直接短路返回，
 *    完全不碰 [StockDataManager]——否则逐标的扇出里 N 个已批量取回的标的
 *    又会各自联网，请求量瞬间回到 N，P3 等于没做。
 * 2. [UnifiedPriceService.prewarmStockPrices] 在网络不可用 / 代码为空时
 *    必须零请求返回空 map（不能去打新浪接口），离线时批量预取不会凭空制造请求。
 * 3. 网络可用时 prewarm 整批只委托 [StockDataManager.getBatchStockDataThroughSina]
 *    一次（而非每码一次），这是「N→⌈N/20⌉」的落地点。
 *
 * 全部走 JVM 单测：短路用例根本不读 stockDataManager 字段，离线/空码用例在触达
 * 数据源前就返回，happy-path 用例用 relaxed mock 把 Sina 链路替换掉，三种情况都不碰
 * 真实网络与 Room。
 */
class UnifiedPriceServiceBatchTest {

    @Before
    fun setUp() {
        mockkObject(StockDataManager.Companion)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun sampleStockData(code: String) = StockData(
        code = code,
        name = code,
        price = 1.0,
        change = 0.0,
        changePercent = 0.0
    )

    private fun sampleStockPrice(code: String) = AssetPrice.Stock(sampleStockData(code))

    /**
     * 核心安全不变量：batchResult 命中时 getPrice 直接返回，不读 60s 缓存、
     * 不联网、不走多源。getInstance 应一次都不被调用。
     */
    @Test
    fun getPrice_shortCircuitsOnBatchResult_withoutTouchingStockDataManager() = runBlocking {
        val code = "sh600519"
        val batch = sampleStockPrice(code)
        val result = UnifiedPriceService.getPrice(
            code,
            AssetType.STOCK,
            forceRefresh = true,
            batchResult = batch
        )
        assertTrue(result.isSuccess)
        assertEquals(batch, result.getOrNull())
        // 短路发生在读取 stockDataManager 之前，getInstance 不应被调用
        verify(exactly = 0) { StockDataManager.getInstance(any()) }
    }

    /**
     * 安全护栏：离线时 prewarm 必须零请求、返回空 map，绝不打新浪接口。
     */
    @Test
    fun prewarmStockPrices_offline_returnsEmptyMapWithoutRequests() = runBlocking {
        val result = UnifiedPriceService.prewarmStockPrices(
            listOf("sh600519", "sz000001"),
            forceRefresh = true,
            networkAvailable = false
        )
        assertTrue(result.isSuccess)
        assertTrue(result.getOrDefault(emptyMap()).isEmpty())
        verify(exactly = 0) { StockDataManager.getInstance(any()) }
    }

    /**
     * 安全护栏：空代码列表时 prewarm 同样零请求、返回空 map。
     */
    @Test
    fun prewarmStockPrices_emptyCodes_returnsEmptyMapWithoutRequests() = runBlocking {
        val result = UnifiedPriceService.prewarmStockPrices(
            emptyList(),
            forceRefresh = true,
            networkAvailable = true
        )
        assertTrue(result.isSuccess)
        assertTrue(result.getOrDefault(emptyMap()).isEmpty())
        verify(exactly = 0) { StockDataManager.getInstance(any()) }
    }

    /**
     * 请求量落地点：网络可用时整批只委托 getBatchStockDataThroughSina 一次，
     * 内部把 N 个代码分块成 ~⌈N/20⌉ 次新浪请求（该项在 SinaStockAPI 层校验），
     * 上层编排器对每个标的只调用一次预取。
     */
    @Test
    fun prewarmStockPrices_online_delegatesToBatchMethodOnce() = runBlocking {
        val sdm = mockk<StockDataManager>(relaxed = true)
        val batch = mapOf("sh600519" to sampleStockData("sh600519"))
        every { StockDataManager.getInstance(any()) } returns sdm
        // init 经被 mock 的 getInstance 把 StockDataManager 注入门面字段，
        // 否则 prewarm 会读到 null 字段、提前以 IllegalStateException 短路返回。
        UnifiedPriceService.init(mockk(relaxed = true))
        coEvery { sdm.getBatchStockDataThroughSina(any(), any()) } returns Result.Success(batch)

        val result = UnifiedPriceService.prewarmStockPrices(
            listOf("sh600519"),
            forceRefresh = true,
            networkAvailable = true
        )
        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrNull()?.size ?: 0)
        coVerify(exactly = 1) { sdm.getBatchStockDataThroughSina(any(), any()) }
    }
}
