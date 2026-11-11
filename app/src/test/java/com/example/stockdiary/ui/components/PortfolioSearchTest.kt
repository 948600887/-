package com.example.stockdiary.ui.components

import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [matchesSearchQuery] 的纯函数单测：覆盖 trim（半角 + 全角空格）、多字段匹配、
 * null 安全与大小写不敏感。
 */
class PortfolioSearchTest {

    private fun asset(
        name: String,
        account: String = "默认账户",
        symbol: String? = null,
        notes: String? = null,
        type: AssetType = AssetType.STOCK
    ) = Asset(name = name, type = type, account = account, symbol = symbol, notes = notes)

    @Test
    fun blankQueryMatchesAll() {
        val a = asset("贵州茅台", symbol = "600519", account = "华泰")
        assertTrue(matchesSearchQuery(a, ""))
        assertTrue(matchesSearchQuery(a, "   "))
    }

    @Test
    fun nameSubstringMatch() {
        val a = asset("贵州茅台")
        assertTrue(matchesSearchQuery(a, "茅台"))
        assertFalse(matchesSearchQuery(a, "五粮液"))
    }

    @Test
    fun symbolMatchCaseInsensitive() {
        val a = asset("Apple", symbol = "AAPL", account = "美股")
        assertTrue(matchesSearchQuery(a, "aapl"))
        assertFalse(matchesSearchQuery(a, "GOOG"))
    }

    @Test
    fun nullSymbolDoesNotCrash() {
        val a = asset("现金", symbol = null)
        assertFalse(matchesSearchQuery(a, "AAPL"))
    }

    @Test
    fun accountMatch() {
        val a = asset("某基金", account = "天天基金")
        assertTrue(matchesSearchQuery(a, "天天"))
        assertFalse(matchesSearchQuery(a, "支付宝"))
    }

    @Test
    fun notesMatchNullSafe() {
        val withNotes = asset("比特币", notes = "冷钱包长期持有")
        assertTrue(matchesSearchQuery(withNotes, "冷钱包"))
        val noNotes = asset("以太坊", notes = null)
        assertFalse(matchesSearchQuery(noNotes, "冷钱包"))
    }

    @Test
    fun typeDisplayNameMatch() {
        val crypto = asset("BTC", type = AssetType.CRYPTO, account = "币安")
        assertTrue(matchesSearchQuery(crypto, "虚拟货币"))
        assertTrue(matchesSearchQuery(crypto, "币")) // 子串命中「虚拟货币」
        val fund = asset("某基金", type = AssetType.FUND)
        assertTrue(matchesSearchQuery(fund, "基金"))
        assertFalse(matchesSearchQuery(fund, "股票"))
    }

    @Test
    fun leadingTrailingSpacesTrimmed() {
        val a = asset("贵州茅台", account = "华泰证券")
        assertTrue(matchesSearchQuery(a, "  茅台  "))
        assertTrue(matchesSearchQuery(a, " 华泰 "))
        assertFalse(matchesSearchQuery(a, "  五粮液  "))
    }

    @Test
    fun fullWidthSpacesAreTrimmed() {
        // 全角空格 U+3000：默认 trim() 只去 ≤ U+0020，会漏；改用 trim { isWhitespace() } 后覆盖。
        val a = asset("贵州茅台", account = "华泰证券")
        assertTrue(matchesSearchQuery(a, "\u3000茅台\u3000"))
        assertTrue(matchesSearchQuery(a, "\u3000华泰\u3000"))
        assertFalse(matchesSearchQuery(a, "\u3000五粮液\u3000"))
        // 纯全角空白查询等价于空查询，匹配全部
        assertTrue(matchesSearchQuery(a, "\u3000\u3000"))
    }

    @Test
    fun trimmedQueryReuseKeepsSameSemantics() {
        // 列表路径复用同一份已 trim 的查询：与 matchesSearchQuery 结论必须一致。
        val a = asset("贵州茅台", account = "华泰证券")
        val raw = "\u3000茅台\u3000"
        val normalized = raw.trim { it.isWhitespace() }
        assertEquals(
            "纯函数入口与列表复用入口结论必须一致",
            matchesSearchQuery(a, raw),
            matchesTrimmedQuery(a, normalized)
        )
        assertTrue(matchesTrimmedQuery(a, ""))
        assertTrue(matchesTrimmedQuery(a, "   "))
        assertFalse(matchesTrimmedQuery(a, "五粮液"))
    }
}
