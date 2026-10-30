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

/** 非欠款部分：算「总资产」时用，借款不计入资产端（见 [Asset.isDebt]） */
fun List<Asset>.excludingDebts(): List<Asset> = filter { !it.isDebt }

/** 欠款部分：负债类型 + 负余额持仓（币安借币），算「总负债」时用 */
fun List<Asset>.debts(): List<Asset> = filter { it.isDebt }

/**
 * 负债合计（人民币）。
 *
 * 必须取绝对值：币安借币以**负余额**存储（-17.48 表示欠 17.48），直接求和会把总负债
 * 算成负数；而负债类型本身存正数（欠多少写多少）。两者混在一个列表里，只有取绝对值
 * 才能让「总负债」始终是「一共欠多少钱」。
 */
fun List<Asset>.sumDebtValueCny(): Double = sumOf { kotlin.math.abs(it.totalValueCny) }

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
