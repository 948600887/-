package com.example.stockdiary.ui.screens

import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.utils.ExchangeRateManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [filterLowValueCrypto] 的纯函数回归。依赖 [ExchangeRateManager.toCny]，用
 * resetForTesting 把汇率钉成静态默认值（CNY=1.0、USD=7.25）以保证断言可算。
 */
class PortfolioFiltersTest {

    @Before
    fun setUp() {
        ExchangeRateManager.resetForTesting()
    }

    @After
    fun tearDown() {
        ExchangeRateManager.resetForTesting()
    }

    private fun crypto(balance: Double, price: Double?, currency: String = "USD", symbol: String = "BTCUSDT") =
        Asset(
            name = "C",
            type = AssetType.CRYPTO,
            symbol = symbol,
            account = "币安",
            balance = balance,
            currentPrice = price,
            currency = currency
        )

    private fun stock(balance: Double, price: Double) =
        Asset(
            name = "S",
            type = AssetType.STOCK,
            symbol = "sh1",
            account = "",
            balance = balance,
            currentPrice = price,
            currency = "CNY"
        )

    @Test
    fun 未开启时原样返回() {
        val assets = listOf(crypto(1.0, 1.0), crypto(100.0, 1.0))
        assertEquals(assets, filterLowValueCrypto(assets, hideLowValueCrypto = false, thresholdCny = 10.0))
    }

    @Test
    fun 开启时已知价且低于阈值的加密货币被隐藏() {
        // 1 BTC × 1.0 美元 × 7.25 = 7.25 元 < 10 → 藏
        val low = crypto(1.0, 1.0)
        val high = crypto(10.0, 1.0)   // 72.5 元 > 10 → 留
        val result = filterLowValueCrypto(listOf(low, high), hideLowValueCrypto = true, thresholdCny = 10.0)
        assertEquals(listOf(high), result)
    }

    @Test
    fun 价格未知_即使市值算出来为0也不藏() {
        // currentPrice = null → 不藏（避免误丢真实持仓，界面显示「价格待更新」）
        val unknown = crypto(1.0, null)
        val result = filterLowValueCrypto(listOf(unknown), hideLowValueCrypto = true, thresholdCny = 10.0)
        assertTrue(result.contains(unknown))
        assertEquals(1, result.size)
    }

    @Test
    fun 非加密货币不受低价值隐藏影响() {
        val cheapStock = stock(1.0, 0.5)  // 0.5 元，远低于阈值，但它是股票
        val lowCrypto = crypto(1.0, 1.0)  // 7.25 元 < 10 → 藏
        val result = filterLowValueCrypto(listOf(cheapStock, lowCrypto), hideLowValueCrypto = true, thresholdCny = 10.0)
        assertEquals(listOf(cheapStock), result)
    }

    @Test
    fun 阈值边界_恰好等于阈值时保留() {
        // 10 / 7.25 ≈ 1.379 BTC × 1.0 × 7.25 = 10.0 元 == 阈值 → 因是严格小于才藏，故保留
        val atThreshold = crypto(10.0 / 7.25, 1.0)
        val result = filterLowValueCrypto(listOf(atThreshold), hideLowValueCrypto = true, thresholdCny = 10.0)
        assertTrue(result.contains(atThreshold))
    }

    @Test
    fun 空列表安全() {
        assertTrue(filterLowValueCrypto(emptyList(), hideLowValueCrypto = true, thresholdCny = 10.0).isEmpty())
    }

    @Test
    fun 隐藏计数正确_混合场景() {
        // 3 个低于阈值 + 1 个高于 + 1 个未知价（留）：应藏 3 个
        val assets = listOf(
            crypto(0.1, 1.0),   // 0.725 元 → 藏
            crypto(0.5, 1.0),   // 3.625 元 → 藏
            crypto(1.0, 1.0),   // 7.25 元  → 藏
            crypto(100.0, 1.0), // 725 元   → 留
            crypto(5.0, null)   // 未知价   → 留
        )
        val result = filterLowValueCrypto(assets, hideLowValueCrypto = true, thresholdCny = 10.0)
        assertEquals(2, result.size)
        assertFalse(result.any { it.balance == 0.1 || it.balance == 0.5 || it.balance == 1.0 })
    }

    // ── 子开关 hideUnknownPriceCrypto（默认关，仅在 hideLowValueCrypto 开启时才有意义）──

    @Test
    fun 隐藏无价格_两个开关都开时未知价被隐藏() {
        val unknown = crypto(1.0, null)
        val result = filterLowValueCrypto(
            listOf(unknown),
            hideLowValueCrypto = true,
            thresholdCny = 10.0,
            hideUnknownPriceCrypto = true
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun 隐藏无价格_子开关关时未知价保留_已知价仍按阈值() {
        val unknown = crypto(1.0, null)
        val low = crypto(1.0, 1.0)   // 7.25 元 < 10 → 藏
        val high = crypto(100.0, 1.0) // 725 元   > 10 → 留
        val result = filterLowValueCrypto(
            listOf(unknown, low, high),
            hideLowValueCrypto = true,
            thresholdCny = 10.0,
            hideUnknownPriceCrypto = false
        )
        assertEquals(listOf(unknown, high), result)
    }

    @Test
    fun 隐藏无价格_低价值关闭时子开关无效() {
        // 主开关关，子开关开：整体不生效，无价格与低价值都保留
        val unknown = crypto(1.0, null)
        val low = crypto(1.0, 1.0)
        val result = filterLowValueCrypto(
            listOf(unknown, low),
            hideLowValueCrypto = false,
            thresholdCny = 10.0,
            hideUnknownPriceCrypto = true
        )
        assertEquals(listOf(unknown, low), result)
    }

    @Test
    fun 隐藏无价格_两个开关都开_混合场景() {
        val unknown = crypto(5.0, null)   // 无价格 → 藏
        val low = crypto(1.0, 1.0)        // 7.25 < 10 → 藏
        val high = crypto(100.0, 1.0)     // 725 > 10  → 留
        val result = filterLowValueCrypto(
            listOf(unknown, low, high),
            hideLowValueCrypto = true,
            thresholdCny = 10.0,
            hideUnknownPriceCrypto = true
        )
        assertEquals(listOf(high), result)
    }
}
