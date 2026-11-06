package com.example.stockdiary.data.price

import android.content.Context
import com.example.stockdiary.data.repository.AssetRepository
import com.example.stockdiary.data.repository.DividendRepository
import com.example.stockdiary.data.sync.AssetSyncManager
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.network.StalePriceCacheException
import com.example.stockdiary.network.UnifiedPriceService
import com.example.stockdiary.network.model.FundData
import com.example.stockdiary.network.model.AssetPrice
import com.example.stockdiary.network.model.StockData
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * 阶段 0 ④「缓存旧价回落区分 TTL」回归。
 *
 * ## 钉死的问题
 *
 * [StockDataManager.getStockData] 原来在「无网络 + 内存缓存命中」时无脑
 * `Result.success(旧价)`（连 TTL 都不校验）；旧价回到
 * [AssetSyncManager.syncSingleAsset] 被判 `isSuccess = true` 并重新落库，
 * `AssetDao.updatePriceBySymbol` 把该行 `lastSyncTime` 刷成「刚刚」——
 * 三天前的收盘价在卡片上伪装成刚抓的新价。
 *
 * ## 取舍（刻意保留）
 *
 * - 复用 [PriceCacheManager.isCacheValid] 的既有 TTL 口径，不改它的
 *   「交易时段 5min / 16 点后与周末 MAX_VALUE 收盘价复用」设计；
 * - TTL 内：按原语义继续以 success 返回旧价（收盘价复用合理）；
 * - TTL 外：改抛 [StalePriceCacheException]，同步编排器归到「跳过」
 *   （不算失败、不重新落库），库里旧价原样保留——不清空旧价、不写 0。
 */
class StalePriceCacheFallbackTest {

    companion object {
        private const val SYMBOL = "sh600519"
        private const val OLD_PRICE = 1688.0

        @BeforeClass
        @JvmStatic
        fun setupMain() {
            Dispatchers.setMain(Dispatchers.Unconfined)
        }

        @AfterClass
        @JvmStatic
        fun resetMainDispatcher() {
            Dispatchers.resetMain()
        }
    }

    private val context = mockk<Context>(relaxed = true)
    private val repository = mockk<AssetRepository>(relaxed = true)
    private val stockManager = mockk<StockDataManager>(relaxed = true)
    private val dividendRepository = mockk<DividendRepository>(relaxed = true)
    private lateinit var manager: StockDataManager

    private fun oldPrice(): StockData = StockData(
        code = SYMBOL,
        name = "贵州茅台",
        price = OLD_PRICE,
        change = 0.0,
        changePercent = 0.0,
        timestamp = System.currentTimeMillis() - 3L * 24 * 60 * 60 * 1000
    )

    @Before
    fun setUp() {
        every { context.filesDir } returns File(System.getProperty("java.io.tmpdir"))
        // PriceCacheManager 是 object：桩掉它的 TTL 判定，避免依赖真实时钟 / 交易日。
        // 它的 init() 会被 StockDataManager 的 init 块调用，成为空操作即可。
        mockkObject(PriceCacheManager)
        every { PriceCacheManager.init(any()) } returns Unit
        manager = StockDataManager(context)
        manager.resetForTesting()
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ---------- StockDataManager：过期缓存不再冒充成功 ----------

    @Test
    fun `过期缓存且无网络_不再以success身份返回旧价`() = runBlocking {
        every { PriceCacheManager.isCacheValid(SYMBOL) } returns false
        manager.seedCacheForTesting(SYMBOL, oldPrice())

        val result = manager.getStockData(SYMBOL, forceRefresh = true, networkAvailable = false)

        assertFalse("过期旧价不能再是 Result.success，否则会被重新落库刷新 lastSyncTime", result.isSuccess)
        assertTrue(
            "必须给出可识别的「过期旧价」信号，让同步编排器按跳过处理",
            StalePriceCacheException.isStaleCache(result.exceptionOrNull())
        )
        // 不清空旧价 / 不写 0：旧价仍留在缓存里，等网络恢复后自然更新
        assertEquals(1, manager.getCacheSize())
        assertNotNull(result.exceptionOrNull())
    }

    @Test
    fun `TTL内缓存且无网络_按原语义复用旧价且价格不为0`() = runBlocking {
        every { PriceCacheManager.isCacheValid(SYMBOL) } returns true
        manager.seedCacheForTesting(SYMBOL, oldPrice())

        val result = manager.getStockData(SYMBOL, forceRefresh = true, networkAvailable = false)

        assertTrue("盘后/周末复用收盘价是刻意设计，TTL 内仍按原语义返回 success", result.isSuccess)
        assertEquals(OLD_PRICE, result.getOrNull()!!.price, 0.0)
        assertEquals(1, manager.getCacheSize())
    }

    @Test
    fun `非强制刷新_持久化缓存已过期时根本不去读旧价`() = runBlocking {
        every { PriceCacheManager.isCacheValid(SYMBOL) } returns false
        manager.seedCacheForTesting(SYMBOL, oldPrice())

        val result = manager.getStockData(SYMBOL, forceRefresh = false, networkAvailable = false)

        assertFalse(result.isSuccess)
        assertTrue(StalePriceCacheException.isStaleCache(result.exceptionOrNull()))
        verify(exactly = 0) { PriceCacheManager.getCachedPrice(any()) }
    }

    @Test
    fun `无网络且完全没有缓存_保持原来的失败语义`() = runBlocking {
        every { PriceCacheManager.isCacheValid(SYMBOL) } returns false

        val result = manager.getStockData(SYMBOL, forceRefresh = true, networkAvailable = false)

        assertFalse(result.isSuccess)
        assertFalse(StalePriceCacheException.isStaleCache(result.exceptionOrNull()))
        assertEquals(0, manager.getCacheSize())
    }

    // ---------- 编排器：过期缓存回落不刷新 Asset.lastSyncTime ----------

    /**
     * 与 [AssetSyncManagerTest] 同样的桩法：场外基金（fund_ 前缀）不走 STOCK 批量预取，
     * 整条链路不碰 Room / OkHttp，JVM 单测可跑。
     *
     * `AssetDao.updatePriceBySymbol` / `AssetRepository.updatePricesInTransaction` 是
     * **唯一**会写 lastSyncTime 的出口；断言它们一次都没被调用，等价于断言
     * `Asset.lastSyncTime` 没有被刷成「刚刚」。
     */
    private fun prepareSyncStubs() {
        mockkObject(StockDataManager.Companion)
        every { StockDataManager.getInstance(any()) } returns stockManager
        mockkObject(DividendRepository.Companion)
        every { DividendRepository.getInstance(any()) } returns dividendRepository
        mockkObject(UnifiedPriceService)
    }

    private fun fundAsset(): Asset = Asset(
        name = "测试基金",
        type = AssetType.FUND,
        symbol = "fund_010001",
        account = "默认",
        balance = 100.0,
        currentPrice = 1.5,
        lastSyncTime = 1_700_000_000_000L
    )

    private fun fundPrice(): AssetPrice.Fund = AssetPrice.Fund(
        FundData(code = "010001", name = "测试基金", price = 1.5, change = 0.01, changePercent = 0.67)
    )

    @Test
    fun `过期缓存回落不刷新Asset_lastSyncTime_且不算失败`() = runBlocking {
        prepareSyncStubs()
        // 门面拿到的就是 StockDataManager 在无网络 + 过期缓存时抛出的异常
        coEvery { UnifiedPriceService.getPrice(any(), AssetType.FUND, any()) } returns
            kotlin.Result.failure(StalePriceCacheException("010001"))
        val asset = fundAsset()

        val result = AssetSyncManager.syncAllAssets(
            context = context,
            repository = repository,
            assets = listOf(asset),
            onProgress = {},
            forceRefresh = true
        )

        assertEquals(0, result.successCount)
        assertEquals(0, result.failedCount)
        assertEquals("过期缓存按跳过记账，不能算失败", 1, result.skippedByReason["缓存过期"])
        assertEquals(1, result.skippedCount)
        assertTrue(result.hasNoEffectiveUpdate)
        // 唯一会刷新 lastSyncTime 的两个写库出口都没发生
        coVerify(exactly = 0) { repository.updatePricesInTransaction(any()) }
        coVerify(exactly = 0) { repository.updatePriceBySymbol(any(), any(), any()) }
        // 旧价既没被清空也没被写成 0，时间戳保持三天前的值
        assertEquals(1.5, asset.currentPrice!!, 0.0)
        assertEquals(1_700_000_000_000L, asset.lastSyncTime)
    }

    @Test
    fun `对照组_正常新价确实会走写库出口_证明上面的零调用不是假绿`() = runBlocking {
        prepareSyncStubs()
        coEvery { UnifiedPriceService.getPrice(any(), AssetType.FUND, any()) } returns
            kotlin.Result.success(fundPrice())

        val result = AssetSyncManager.syncAllAssets(
            context = context,
            repository = repository,
            assets = listOf(fundAsset()),
            onProgress = {},
            forceRefresh = true
        )

        assertEquals(1, result.successCount)
        assertEquals(0, result.failedCount)
        coVerify(atLeast = 1) { repository.updatePricesInTransaction(any()) }
    }

    @Test
    fun `skippedHint如实区分过期缓存_不会被说成无需更新`() {
        val result = com.example.stockdiary.data.sync.SyncResult(
            successCount = 0,
            failedCount = 0,
            skippedByReason = mapOf("缓存过期" to 2, "币安熔断" to 1)
        )
        assertTrue(result.skippedHint.contains("2 个仅有过期的旧价"))
        assertTrue(result.skippedHint.contains("本次未更新"))
        assertTrue(result.skippedHint.contains("1 个加密货币行情源不可达"))
        assertFalse(result.skippedHint.contains("2 个无需更新"))
    }
}
