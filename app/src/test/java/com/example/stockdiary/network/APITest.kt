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
    fun `新浪实时行情URL不带rn缓存bust参数_否则会被当成股票代码一部分`() {
        // 回归：曾因拼接 &rn=timestamp 且缺少 ? 分隔，新浪把 sh601728&rn=... 当成代码，
        // 返回 41 字节空响应，导致新浪对所有 A 股失效、静默回退腾讯。
        val url = SinaStockAPI.buildRealtimeUrl("sh601728")
        assertEquals("https://hq.sinajs.cn/list=sh601728", url)
        assertFalse("URL 绝不能包含 &rn= 之类的 query 参数", url.contains("&rn="))
        assertFalse("URL 绝不能包含 ?rn= 之类的 query 参数", url.contains("?rn="))
    }

    @Test
    fun `新浪批量实时行情URL多代码用逗号拼接_无query参数`() {
        val url = SinaStockAPI.buildRealtimeUrl("sh601728,sz000001")
        assertEquals("https://hq.sinajs.cn/list=sh601728,sz000001", url)
        assertFalse(url.contains("rn="))
    }

    @Test
    fun `formatSymbol keeps USDT pair unchanged`() {
        assertEquals("BTCUSDT", BinanceAPI.formatSymbol("BTCUSDT"))
    }

    @Test
    fun `formatSymbol normalizes lowercase USDT pair`() {
        assertEquals("ETHUSDT", BinanceAPI.formatSymbol("ethusdt"))
    }

    @Test
    fun `formatSymbol appends USDT to plain base symbol`() {
        assertEquals("SOLUSDT", BinanceAPI.formatSymbol("SOL"))
    }

    @Test
    fun `formatSymbol converts USD suffix to USDT pair`() {
        assertEquals("BTCUSDT", BinanceAPI.formatSymbol("BTCUSD"))
    }

    @Test
    fun `formatSymbol converts CNY suffix to USDT pair`() {
        assertEquals("BTCUSDT", BinanceAPI.formatSymbol("BTCCNY"))
    }

    @Test
    fun `formatSymbol strips spaces and dashes`() {
        assertEquals("BTCUSDT", BinanceAPI.formatSymbol("btc-usdt"))
    }

    @Test
    fun `formatSymbol keeps BTC-quote trade pair`() {
        assertEquals("ADABTC", BinanceAPI.formatSymbol("ADABTC"))
    }

    @Test
    fun `formatSymbol keeps ETH-quote trade pair`() {
        assertEquals("XRPETH", BinanceAPI.formatSymbol("XRPETH"))
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
