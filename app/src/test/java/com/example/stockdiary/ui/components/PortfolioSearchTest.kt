package com.example.stockdiary.ui.components

import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [matchesSearchQuery] 的纯函数单测：覆盖 trim、多字段匹配、null 安全与大小写不敏感。
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
}
