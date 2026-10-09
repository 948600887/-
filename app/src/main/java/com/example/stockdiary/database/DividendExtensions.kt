package com.example.stockdiary.database

import com.example.stockdiary.data.DividendRecord

/**
 * 分红金额折算：**「每十份（股）派多少」→「你手上的持仓能拿到多少」**。
 *
 * 这条公式以前散落在 6 个文件的 8 个地方，改一次口径要同时改 8 处 ——
 * 已经因此出过一次 bug。现在全项目只有这一处实现，改口径改这里就行。
 *
 * 口径：`amount` / `dividendPerShare` 存的都是**每十份（股）**的派息金额，
 * 所以真实到手 = 每十份金额 ÷ 10 × 持有份数。
 */
fun perTenSharesToAmount(perTenShares: Double, balance: Double): Double =
    perTenShares / 10.0 * balance

/** 见 [perTenSharesToAmount] */
fun Dividend.amountFor(balance: Double): Double = perTenSharesToAmount(amount, balance)

/** 见 [perTenSharesToAmount] */
fun DividendRecord.amountFor(balance: Double): Double =
    perTenSharesToAmount(dividendPerShare.toDoubleOrNull() ?: 0.0, balance)
