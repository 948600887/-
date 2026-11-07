package com.example.stockdiary.ui.screens

import com.example.stockdiary.data.prefs.SortOrder
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.utils.ExchangeRateManager
import com.example.stockdiary.utils.totalValueCny
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 持仓页一级分组与组间排序的回归。
 *
 * 两个真机问题：
 * 1. 「银行存款」没有代码，被旧的 symbol 形态判定静默归进「其他」；
 * 2. 「金额↓」只排组内资产、组头仍按固定顺序输出，于是其他 ¥71616 排在加密 ¥1106 之后。
 *
 * 这里只测纯函数（不渲染 Compose），钉死「存款按类型归组」与「组间按本币总额排序」。
 */
class PortfolioGroupingTest {

    private fun asset(
        id: Long,
        name: String,
        type: AssetType,
        balance: Double,
        symbol: String? = null,
        currency: String = "CNY",
        purchasePrice: Double = 1.0,
        account: String = "测试账户"
    ) = Asset(
        id = id,
        name = name,
        type = type,
        symbol = symbol,
        account = account,
        balance = balance,
        purchasePrice = purchasePrice,
        currency = currency
    )

    @Test
    fun `银行存款即使没有代码也归入存款分组`() {
        val deposit = asset(1, "三方", AssetType.BANK_DEPOSIT, balance = 71616.93)

        assertEquals(HoldingsMarket.DEPOSIT, marketOf(deposit))
        assertEquals("银行存款", HoldingsMarket.DEPOSIT.label)
    }

    @Test
    fun `非存款的空白代码仍然归入其他`() {
        val anonymous = asset(2, "手工资产", AssetType.STOCK, balance = 100.0)

        assertEquals(HoldingsMarket.OTHER, marketOf(anonymous))
    }

    @Test
    fun `金额降序按分组本币总额排序_其他不再垫底`() {
        val assets = listOf(
            asset(1, "场外基金", AssetType.FUND, balance = 100954.76, symbol = "fund_000001"),
            asset(2, "场内股票", AssetType.STOCK, balance = 97078.43, symbol = "sh600000"),
            asset(3, "加密货币", AssetType.CRYPTO, balance = 1106.30, symbol = "BTCUSDT"),
            asset(4, "三方", AssetType.BANK_DEPOSIT, balance = 71616.93),
            asset(5, "零星资产", AssetType.STOCK, balance = 500.0)
        )

        val groups = groupAssets(assets, SortOrder.BY_VALUE_DESC)

        assertEquals(
            listOf(
                HoldingsMarket.OTC,
                HoldingsMarket.EXCHANGE,
                HoldingsMarket.DEPOSIT,
                HoldingsMarket.CRYPTO,
                HoldingsMarket.OTHER
            ),
            groups.map { it.market }
        )
        assertEquals(
            "组总额必须等于组内资产本币值之和",
            assets.filter { marketOf(it) == HoldingsMarket.DEPOSIT }.sumOf { it.totalValueCny },
            groups.first { it.market == HoldingsMarket.DEPOSIT }.totalValue,
            0.0001
        )
    }

    @Test
    fun `金额升序与降序严格相反`() {
        val assets = listOf(
            asset(1, "场外基金", AssetType.FUND, balance = 1000.0, symbol = "fund_000001"),
            asset(2, "场内股票", AssetType.STOCK, balance = 5000.0, symbol = "sh600000"),
            asset(3, "三方", AssetType.BANK_DEPOSIT, balance = 3000.0)
        )

        assertEquals(
            listOf(HoldingsMarket.OTC, HoldingsMarket.DEPOSIT, HoldingsMarket.EXCHANGE),
            groupAssets(assets, SortOrder.BY_VALUE_ASC).map { it.market }
        )
        assertEquals(
            listOf(HoldingsMarket.EXCHANGE, HoldingsMarket.DEPOSIT, HoldingsMarket.OTC),
            groupAssets(assets, SortOrder.BY_VALUE_DESC).map { it.market }
        )
    }

    @Test
    fun `按类型排序保持固定展示顺序_存款在加密货币之后其他之前`() {
        val assets = listOf(
            asset(1, "场外基金", AssetType.FUND, balance = 1000.0, symbol = "fund_000001"),
            asset(2, "三方", AssetType.BANK_DEPOSIT, balance = 3000.0),
            asset(3, "加密货币", AssetType.CRYPTO, balance = 100.0, symbol = "BTCUSDT"),
            asset(4, "场内股票", AssetType.STOCK, balance = 5000.0, symbol = "sh600000"),
            asset(5, "零星资产", AssetType.STOCK, balance = 500.0)
        )

        assertEquals(
            listOf(
                HoldingsMarket.OTC,
                HoldingsMarket.EXCHANGE,
                HoldingsMarket.CRYPTO,
                HoldingsMarket.DEPOSIT,
                HoldingsMarket.OTHER
            ),
            groupAssets(assets, SortOrder.BY_TYPE).map { it.market }
        )
    }

    @Test
    fun `组间金额排序使用汇率换算后的本币值`() {
        val usdRate = ExchangeRateManager.getRate("USD")
        assertTrue("默认汇率表必须有 USD", usdRate > 0.0)
        // 美元资产原值 100（小于人民币 699），换算后大于 699；只有按本币值排才会排在前面
        val cnyBalance = (100.0 * usdRate - 1.0).coerceAtLeast(0.0)
        val assets = listOf(
            asset(
                id = 1,
                name = "美元加密",
                type = AssetType.CRYPTO,
                balance = 100.0,
                symbol = "BTCUSDT",
                currency = "USD"
            ),
            asset(2, "人民币存款", AssetType.BANK_DEPOSIT, balance = cnyBalance)
        )

        assertEquals(
            listOf(HoldingsMarket.CRYPTO, HoldingsMarket.DEPOSIT),
            groupAssets(assets, SortOrder.BY_VALUE_DESC).map { it.market }
        )
        assertEquals(
            listOf(HoldingsMarket.DEPOSIT, HoldingsMarket.CRYPTO),
            groupAssets(assets, SortOrder.BY_VALUE_ASC).map { it.market }
        )
    }
}
