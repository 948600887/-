package com.example.stockdiary.network

import com.example.stockdiary.database.AssetType
import com.example.stockdiary.network.model.AssetPrice
import com.example.stockdiary.network.model.CryptoData
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [UnifiedPriceService.getPrice] 批量回填短路回归（支撑修复 #1）。
 *
 * 钉的契约：传入非 null [batchResult] 时，无论资产类型（含 CRYPTO），
 * [getPrice] 必须**直接返回**它而完全跳过下游取价（门面 60s 缓存、`when(type)` 分支、
 * 以及任何 Binance / 新浪 / 腾讯的网络调用）。这是 [AssetSyncManager] 把批量预取的现成价
 * 回填给逐标的取价、从而避免每只加密货币重复打网络的唯一短路点——
 * 修复 #1 后 CRYPTO 分支也会把 `batchResult` 透传进来，本测试证明该短路对 CRYPTO 同样生效。
 *
 * 短路发生在 `when(type)` 之前，故无需 [UnifiedPriceService.init]、亦不触网。
 */
class UnifiedPriceServiceBatchShortCircuitTest {

    @Test
    fun `getPrice CRYPTO 带 batchResult 直接短路返回_不触网`() = runBlocking {
        val prewarmed = AssetPrice.Crypto(
            CryptoData(code = "BTCUSDT", name = "BTC", price = 50000.0, change = null, changePercent = null)
        )
        val result = UnifiedPriceService.getPrice(
            "BTCUSDT",
            AssetType.CRYPTO,
            batchResult = prewarmed
        )
        assertTrue("CRYPTO 带 batchResult 应成功返回回填价", result.isSuccess)
        assertEquals(prewarmed, result.getOrNull())
    }

    @Test
    fun `getPrice STOCK 带 batchResult 同样短路_不改价`() = runBlocking {
        // 共享短路逻辑：股票同样应直接返回回填价，不落回逐标的多源兜底。
        val prewarmed = AssetPrice.Stock(
            com.example.stockdiary.network.model.StockData(
                code = "600519", name = "贵州茅台", price = 1680.0,
                change = null, changePercent = null
            )
        )
        val result = UnifiedPriceService.getPrice(
            "600519",
            AssetType.STOCK,
            batchResult = prewarmed
        )
        assertTrue("STOCK 带 batchResult 应成功返回回填价", result.isSuccess)
        assertEquals(prewarmed, result.getOrNull())
    }
}
