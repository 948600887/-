package com.example.stockdiary.utils

import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType

val Asset.totalValueCny: Double
    get() = ExchangeRateManager.toCny(totalValue, currency)

val Asset.costCny: Double
    get() = if (type == AssetType.BANK_DEPOSIT) {
        // 银行存款的成本为本金（balance），purchasePrice 对存款无意义恒为 0
        ExchangeRateManager.toCny(balance, currency)
    } else {
        ExchangeRateManager.toCny(balance * purchasePrice, currency)
    }

val Asset.profitCny: Double
    get() = totalValueCny - costCny

fun List<Asset>.sumTotalValueCny(): Double = sumOf { it.totalValueCny }

fun List<Asset>.sumCostCny(): Double = sumOf { it.costCny }

fun List<Asset>.sumProfitCny(): Double = sumOf { it.profitCny }
