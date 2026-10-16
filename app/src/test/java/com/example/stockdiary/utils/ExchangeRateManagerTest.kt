package com.example.stockdiary.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ExchangeRateManager] 取值 / 缺省回退 的回归。
 *
 * 说明：当前实现是 **静态汇率表**（`object` + `Map`），没有缓存层、也没有 TTL。
 * 因此「缓存 TTL（过期回源 / 未过期命中）」这条在只读评审里被点名的需求，
 * 落到现网代码上等价于「同一币种多次取值稳定、可重复」（即一个永不过期的缓存）。
 * 这里用「重复调用返回一致值」来钉死这一行为；TTL 过期回源逻辑当前不存在，
 * 不应为测试凭空造一个（那会改业务行为）。见结尾 `repeatCallStable` 注释。
 */
class ExchangeRateManagerTest {

    @Test
    fun `getRate 取 USD 与 CNY 基准汇率`() {
        assertEquals(7.25, ExchangeRateManager.getRate("USD"), 1e-9)
        assertEquals(1.0, ExchangeRateManager.getRate("CNY"), 1e-9)
    }

    @Test
    fun `getRate 大小写不敏感`() {
        assertEquals(7.25, ExchangeRateManager.getRate("usd"), 1e-9)
        assertEquals(0.93, ExchangeRateManager.getRate("Hkd"), 1e-9)
    }

    @Test
    fun `getRate 取不到币种时回退到 1_0 不崩`() {
        // 未知币种 / 空串都应安全回退到 1.0，而不是抛异常
        assertEquals(1.0, ExchangeRateManager.getRate("XYZ"), 1e-9)
        assertEquals(1.0, ExchangeRateManager.getRate(""), 1e-9)
        assertEquals(1.0, ExchangeRateManager.getRate("   "), 1e-9)
    }

    @Test
    fun `toCny 按汇率折算`() {
        assertEquals(725.0, ExchangeRateManager.toCny(100.0, "USD"), 1e-9)
        assertEquals(100.0, ExchangeRateManager.toCny(100.0, "CNY"), 1e-9)
        // 未知币种回退 1.0 → 原值
        assertEquals(100.0, ExchangeRateManager.toCny(100.0, "ZZZ"), 1e-9)
    }

    @Test
    fun `fromCny 反向折算且对零汇率安全`() {
        assertEquals(100.0, ExchangeRateManager.fromCny(725.0, "USD"), 1e-9)
        assertEquals(100.0, ExchangeRateManager.fromCny(100.0, "CNY"), 1e-9)
        // 汇率 > 0 才除，否则原值返回，避免除零
        assertEquals(100.0, ExchangeRateManager.fromCny(100.0, "ZZZ"), 1e-9)
    }

    @Test
    fun `getDisplayName 与 codeOfDisplayName 互转`() {
        assertEquals("美元", ExchangeRateManager.getDisplayName("USD"))
        assertEquals("人民币", ExchangeRateManager.getDisplayName("CNY"))
        // 未知代码原样返回大写
        assertEquals("XYZ", ExchangeRateManager.getDisplayName("xyz"))

        assertEquals("USD", ExchangeRateManager.codeOfDisplayName("美元"))
        assertEquals("CNY", ExchangeRateManager.codeOfDisplayName("人民币"))
        // 认不出返回 null，让调用方决定怎么办（不默默当成人民币）
        assertNull(ExchangeRateManager.codeOfDisplayName("离岸人民币"))
        assertNull(ExchangeRateManager.codeOfDisplayName(""))
    }

    @Test
    fun `supportedCurrencies 含主要币种`() {
        val cur = ExchangeRateManager.supportedCurrencies()
        assertTrue(cur.contains("CNY"))
        assertTrue(cur.contains("USD"))
        assertTrue(cur.contains("HKD"))
    }

    @Test
    fun `repeatCallStable 同一币种多次取值一致（等价永不过期缓存）`() {
        // 当前实现无 TTL 缓存，是静态 Map；重复调用返回同一确定值。
        // 这钉死了「未过期命中」的语义：不会因调用次数多而漂移或触发回源。
        repeat(5) {
            assertEquals(7.25, ExchangeRateManager.getRate("USD"), 1e-9)
            assertEquals(1.0, ExchangeRateManager.getRate("CNY"), 1e-9)
            assertEquals(1.0, ExchangeRateManager.getRate("UNKNOWN"), 1e-9)
        }
    }
}
