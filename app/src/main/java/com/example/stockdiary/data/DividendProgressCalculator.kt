package com.example.stockdiary.data

import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 「收息进度」计算器——借鉴同类股息 App 的头号卖点：
 * **今年已经收到多少股息 / 全年预计能收多少**，外加月度现金流节奏与下一笔到账倒计时。
 *
 * 设计原则（与全项目一致）：**只统计真实存在的分红记录，不编造数字**。
 * 某个月没有派息记录就是 0，绝不按平均值把柱子抹平。
 *
 * 口径说明：
 * - 全年预计 = TTM（最近 12 个月）每十股分红 × 持仓数量 ÷ 10
 * - 今年已收 = 除息日落在「今年 1 月 1 日 ~ 今天」之间的记录 × 持仓数量 ÷ 10
 * - 月度柱图只认**今年**的记录：已过的是实收，未来的已排定派息是预计
 */
object DividendProgressCalculator {

    data class NextPayout(
        val assetName: String,
        val symbol: String?,
        /** yyyy-MM-dd */
        val date: String,
        val daysUntil: Int,
        val amount: Double,
        /** true=按历史同期推算出的日期；false=数据源已排定的派息日 */
        val isEstimated: Boolean
    )

    data class MonthCash(
        val month: Int,
        /** 该月已到账金额 */
        val received: Double,
        /** 该月已排定但尚未到账的金额 */
        val scheduled: Double
    )

    data class Progress(
        val yearReceived: Double,
        val yearProjected: Double,
        /** 今年已到账的笔数 */
        val receivedCount: Int,
        val nextPayout: NextPayout?,
        /** 固定 12 项，1 月在前 */
        val monthly: List<MonthCash>
    ) {
        /** 0 ~ 1，预计为 0 时返回 0（界面上按「无预计」处理） */
        val ratio: Double
            get() = if (yearProjected > 0) (yearReceived / yearProjected).coerceIn(0.0, 1.0) else 0.0

        val remaining: Double
            get() = (yearProjected - yearReceived).coerceAtLeast(0.0)

        val hasData: Boolean
            get() = yearProjected > 0 || yearReceived > 0
    }

    val EMPTY = Progress(
        yearReceived = 0.0,
        yearProjected = 0.0,
        receivedCount = 0,
        nextPayout = null,
        monthly = (1..12).map { MonthCash(it, 0.0, 0.0) }
    )

    fun calculate(
        assets: List<Asset>,
        recordsBySymbol: Map<String, List<DividendRecord>>,
        today: LocalDate = LocalDate.now()
    ): Progress {
        val year = today.year
        val yearStart = "$year-01-01"
        val todayStr = today.toString()

        var yearReceived = 0.0
        var yearProjected = 0.0
        var receivedCount = 0
        val monthlyReceived = DoubleArray(13)
        val monthlyScheduled = DoubleArray(13)
        var next: NextPayout? = null

        assets.filter { it.type == AssetType.STOCK || it.type == AssetType.FUND }.forEach { asset ->
            val symbol = asset.symbol ?: return@forEach
            val records = recordsBySymbol[symbol] ?: return@forEach
            if (records.isEmpty()) return@forEach

            val balance = asset.balance
            val ttmPerTenShares = DividendYieldCalculator.getTTMDividend(records)
            yearProjected += ttmPerTenShares / 10.0 * balance

            // 已排定的未来派息：用于倒计时（数据源给的日期比推算更可信）
            var earliestScheduled: Pair<String, Double>? = null

            records.forEach { record ->
                val date = record.exDividendDate.take(10)
                if (date.length != 10) return@forEach
                val month = date.substring(5, 7).toIntOrNull() ?: return@forEach
                val perShare = record.dividendPerShare.toDoubleOrNull() ?: 0.0
                val amount = perShare / 10.0 * balance

                when {
                    date >= yearStart && date <= todayStr -> {
                        yearReceived += amount
                        receivedCount += 1
                        monthlyReceived[month] += amount
                    }
                    date > todayStr && date.startsWith("$year-") -> {
                        monthlyScheduled[month] += amount
                        if (earliestScheduled == null || date < earliestScheduled!!.first) {
                            earliestScheduled = date to amount
                        }
                    }
                }
            }

            val candidate = earliestScheduled?.let { (date, amount) ->
                NextPayout(
                    assetName = asset.name,
                    symbol = symbol,
                    date = date,
                    daysUntil = daysBetween(today, date),
                    amount = amount,
                    isEstimated = false
                )
            } ?: predictNext(asset.name, symbol, records, balance, today)

            if (candidate != null && candidate.daysUntil >= 0 &&
                (next == null || candidate.daysUntil < next!!.daysUntil)
            ) {
                next = candidate
            }
        }

        return Progress(
            yearReceived = yearReceived,
            yearProjected = yearProjected,
            receivedCount = receivedCount,
            nextPayout = next,
            monthly = (1..12).map { MonthCash(it, monthlyReceived[it], monthlyScheduled[it]) }
        )
    }

    /**
     * 按历史同期推算下一次派息。
     * `DividendIncomeCalculator.predictNextDividendDate` 只会套用「今年」，
     * 今年那个月已经过去时它会给出一个过去的日期 —— 这里补上跨年修正。
     */
    private fun predictNext(
        assetName: String,
        symbol: String,
        records: List<DividendRecord>,
        balance: Double,
        today: LocalDate
    ): NextPayout? {
        val predicted = DividendIncomeCalculator.predictNextDividendDate(records) ?: return null
        val base = parseDate(predicted) ?: return null
        val date = if (!base.isBefore(today)) base else base.plusYears(1)

        val lastRecord = records.sortedByDescending { it.exDividendDate.take(10) }.firstOrNull()
        val amount = (lastRecord?.dividendPerShare?.toDoubleOrNull() ?: 0.0) / 10.0 * balance

        return NextPayout(
            assetName = assetName,
            symbol = symbol,
            date = date.toString(),
            daysUntil = daysBetween(today, date.toString()),
            amount = amount,
            isEstimated = true
        )
    }

    private fun parseDate(value: String): LocalDate? = try {
        LocalDate.parse(value.take(10))
    } catch (e: Exception) {
        null
    }

    private fun daysBetween(from: LocalDate, to: String): Int {
        val target = parseDate(to) ?: return Int.MAX_VALUE
        return ChronoUnit.DAYS.between(from, target).toInt().coerceAtLeast(0)
    }
}
