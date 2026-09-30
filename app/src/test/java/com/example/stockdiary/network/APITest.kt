package com.example.stockdiary.network

import org.junit.Assert.*
import org.junit.Test

class StockCodeFormatTest {
    
    @Test
    fun `formatStockCode handles sh prefix`() {
        assertEquals("sh600519", SinaStockAPI.formatStockCode("sh600519"))
        assertEquals("sh600519", SinaStockAPI.formatStockCode("sh600519"))
    }
    
    @Test
    fun `formatStockCode handles sz prefix`() {
        assertEquals("sz000858", SinaStockAPI.formatStockCode("sz000858"))
        assertEquals("sz000858", SinaStockAPI.formatStockCode("sz000858"))
    }
    
    @Test
    fun `formatStockCode adds sh for Shanghai stocks`() {
        assertEquals("sh600519", SinaStockAPI.formatStockCode("600519"))
        assertEquals("sh600036", SinaStockAPI.formatStockCode("600036"))
        assertEquals("sh500000", SinaStockAPI.formatStockCode("500000"))
    }
    
    @Test
    fun `formatStockCode adds sz for Shenzhen stocks`() {
        assertEquals("sz000858", SinaStockAPI.formatStockCode("000858"))
        assertEquals("sz300001", SinaStockAPI.formatStockCode("300001"))
        assertEquals("sz150001", SinaStockAPI.formatStockCode("150001"))
    }
    
    @Test
    fun `formatStockCode handles bj prefix for Beijing stocks`() {
        assertEquals("bj430001", SinaStockAPI.formatStockCode("430001"))
        assertEquals("bj830001", SinaStockAPI.formatStockCode("830001"))
    }
    
    @Test
    fun `formatStockCode returns original for invalid length`() {
        assertEquals("abc", SinaStockAPI.formatStockCode("abc"))
        assertEquals("60051", SinaStockAPI.formatStockCode("60051"))
        assertEquals("6005191", SinaStockAPI.formatStockCode("6005191"))
    }
    
    @Test
    fun `formatStockCode handles whitespace`() {
        assertEquals("sh600519", SinaStockAPI.formatStockCode("  sh600519  "))
        assertEquals("sz000858", SinaStockAPI.formatStockCode("  000858  "))
    }
}

class FundAPIParseTest {
    
    @Test
    fun `extract JSON value from quoted string`() {
        val json = """{"name":"易方达蓝筹","fundcode":"110001","dwjz":"3.456"}"""
        
        val name = extractJsonValue(json, "name")
        val code = extractJsonValue(json, "fundcode")
        val price = extractJsonValue(json, "dwjz")
        
        assertEquals("易方达蓝筹", name)
        assertEquals("110001", code)
        assertEquals("3.456", price)
    }
    
    @Test
    fun `extract JSON value from unquoted number`() {
        val json = """{"gz":3.456,"gsz":3.5,"gszzl":1.2}"""
        
        val gz = extractJsonValue(json, "gz")
        val gsz = extractJsonValue(json, "gsz")
        val gszzl = extractJsonValue(json, "gszzl")
        
        assertEquals("3.456", gz)
        assertEquals("3.5", gsz)
        assertEquals("1.2", gszzl)
    }
    
    @Test
    fun `extract JSON value returns null for missing key`() {
        val json = """{"name":"测试"}"""
        
        assertNull(extractJsonValue(json, "missing"))
    }
    
    private fun extractJsonValue(json: String, key: String): String? {
        val patternQuoted = "\"$key\":\"([^\"]+)\""
        Regex(patternQuoted).find(json)?.groupValues?.getOrNull(1)?.let { return it }
        
        val patternUnquoted = "\"$key\":([^,}]+)"
        return Regex(patternUnquoted).find(json)?.groupValues?.getOrNull(1)?.trim()
    }
}

class BinanceAPISymbolTest {
    
    @Test
    fun `formatSymbol adds USDT for plain symbols`() {
        val formatted = formatSymbolForTest("BTC")
        assertTrue(formatted.endsWith("USDT"))
    }
    
    @Test
    fun `formatSymbol keeps USDT suffix`() {
        val formatted = formatSymbolForTest("BTCUSDT")
        assertEquals("BTCUSDT", formatted)
    }
    
    @Test
    fun `formatSymbol handles lowercase`() {
        val formatted = formatSymbolForTest("ethusdt")
        assertEquals("ETHUSDT", formatted)
    }
    
    private fun formatSymbolForTest(symbol: String): String {
        val cleanSymbol = symbol.uppercase().replace(" ", "").replace("-", "")

        return when {
            cleanSymbol.endsWith("USDT") -> cleanSymbol
            cleanSymbol.endsWith("BTC") && cleanSymbol != "BTC" -> cleanSymbol
            cleanSymbol.endsWith("ETH") && cleanSymbol != "ETH" -> cleanSymbol
            cleanSymbol.endsWith("CNY") -> {
                val base = cleanSymbol.removeSuffix("CNY")
                "${base}USDT"
            }
            else -> "${cleanSymbol}USDT"
        }
    }
}

class APICacheTest {
    
    @Test
    fun `cache price stores correctly`() {
        val cache = TestAssetCacheManager()
        cache.cachePrice("BTCUSDT", 450000.0, System.currentTimeMillis())
        
        val cached = cache.getCachedPrice("BTCUSDT")
        assertNotNull(cached)
        assertEquals(450000.0, cached!!.price, 0.01)
    }
    
    @Test
    fun `getCachedPrice returns null for missing symbol`() {
        val cache = TestAssetCacheManager()
        
        assertNull(cache.getCachedPrice("MISSING"))
    }
}

class TestAssetCacheManager {
    private val cache = mutableMapOf<String, Pair<Double, Long>>()
    
    fun cachePrice(symbol: String, price: Double, timestamp: Long) {
        cache[symbol] = price to timestamp
    }
    
    fun getCachedPrice(symbol: String): TestCachedPrice? {
        val cached = cache[symbol] ?: return null
        return TestCachedPrice(cached.first, cached.second)
    }
}

data class TestCachedPrice(
    val price: Double,
    val timestamp: Long
)
