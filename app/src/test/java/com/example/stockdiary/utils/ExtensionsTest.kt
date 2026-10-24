package com.example.stockdiary.utils

import com.example.stockdiary.database.AssetType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 加密货币精度相关工具的单测：数量/报价精度给足 [CRYPTO_DECIMALS]，
 * 以及 [formatTrim] 既保住有效位又不给高价币补一堆 0。
 */
class ExtensionsTest {

    @Test
    fun 加密货币数量精度给足10位_其余不变() {
        assertEquals(10, quantityPrecisionFor(AssetType.CRYPTO, "BTCUSDT"))
        // 其余类型口径不变，别顺手统一
        assertEquals(0, quantityPrecisionFor(AssetType.STOCK, "sh600000"))
        assertEquals(2, quantityPrecisionFor(AssetType.FUND, "fund_000001"))
    }

    @Test
    fun 加密货币报价精度给足10位_其余不变() {
        assertEquals(10, pricePrecisionFor(AssetType.CRYPTO, "BTCUSDT"))
        assertEquals(3, pricePrecisionFor(AssetType.FUND, "fund_000001"))
        assertEquals(2, pricePrecisionFor(AssetType.STOCK, "sh600000"))
    }

    @Test
    fun formatTrim_保留有效位并去掉末尾0() {
        // 6 位小数：保住，不丢
        assertEquals("0.123456", 0.123456.formatTrim(CRYPTO_DECIMALS))
        // 整数：不补一堆 0
        assertEquals("1", 1.0.formatTrim(CRYPTO_DECIMALS))
        // 2 位小数：只显示 2 位
        assertEquals("1.5", 1.5.formatTrim(CRYPTO_DECIMALS))
        // 10 位有效数字：全部保住（formatTrim 只去“末尾多余的 0”，不丢有效位）
        assertEquals("0.1234567891", 0.1234567891.formatTrim(CRYPTO_DECIMALS))
    }
}
