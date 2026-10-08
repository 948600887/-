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
