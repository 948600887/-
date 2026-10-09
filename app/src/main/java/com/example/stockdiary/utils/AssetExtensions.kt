package com.example.stockdiary.utils

import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType

val Asset.totalValueCny: Double
    get() = ExchangeRateManager.toCny(totalValue, currency)

// App 去掉成本概念后，界面不再展示成本/盈亏；
// 下面这个扩展只被 AssetSnapshot.fromAssets 用于写入历史遗留列，不再对外提供 sumCost/sumProfit。
val Asset.costCny: Double
    get() = if (type == AssetType.BANK_DEPOSIT) {
        // 银行存款的参考单价为本金（balance），purchasePrice 对存款无意义恒为 0
        ExchangeRateManager.toCny(balance, currency)
    } else {
        ExchangeRateManager.toCny(balance * purchasePrice, currency)
    }

val Asset.profitCny: Double
    get() = totalValueCny - costCny

fun List<Asset>.sumTotalValueCny(): Double = sumOf { it.totalValueCny }

/**
 * 分红口径下的份额单位。
 *
 * 股票叫「股」，基金 / ETF / 其它一律叫「份」——ETF 是场内基金，份额单位本来就是「份」。
 * 之前整个 App 把基金分红也写成「每10股」，对持有 ETF 的人来说这个单位是错的。
 */
val Asset.shareUnitWord: String
    get() {
        val stockLike = when (type) {
            AssetType.STOCK -> true
            AssetType.FUND -> false
            // 用户把类型填错时（比如 ETF 记成股票），退回用代码判断
            else -> StockCodeManager.isStock(symbol)
        }
        return if (stockLike) "股" else "份"
    }
