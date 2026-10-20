package com.example.stockdiary.network

import com.example.stockdiary.database.AssetType
import com.example.stockdiary.network.model.AssetPrice
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 行情门面里「稳定币 / 稳定币理财壳」取价路径的回归。
 *
 * 这两条路径在 [UnifiedPriceService.getCryptoPrice] 里**直接返回 1.0、不发网络请求**
 * （稳定币没有自我交易对），所以本测试不需要 MockK、也不需要 Android Context，
 * 纯 JVM 即可跑通，且能锁死「USDT / LDUSDT 价格恒为 1.0 美元」的口径。
 */
class UnifiedPriceServiceTest {

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
        // 修复前：LDUSDT 被当成「理财壳无行情」跳过（NonTradable），存量持仓的
        // currentPrice 永远留着导入前的 0，市值恒为 0（用户看到的「USDT 价格默认为 0」）。
        // 修复后：底层是稳定币，按 1.0 美元计，无需查网络，下一轮同步即自愈。
        val result = UnifiedPriceService.getPrice("LDUSDT", AssetType.CRYPTO)
        assertTrue("LDUSDT 取价应成功（底层为稳定币）", result.isSuccess)
        val price = result.getOrNull()
        assertTrue(price is AssetPrice.Crypto)
        assertEquals(1.0, (price as AssetPrice.Crypto).data.price, 1e-9)
    }
}
