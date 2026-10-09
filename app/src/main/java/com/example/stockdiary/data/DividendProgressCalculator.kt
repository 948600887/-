package com.example.stockdiary.data

import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.database.amountFor
import com.example.stockdiary.database.perTenSharesToAmount
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
 * - 下一笔：优先数据源已排定的未来派息；没有才按历史同期推算，
 *   且**最后一次派息距今超过 12 个月就不再推算**（见 [STALE_MONTHS]）
 */
object DividendProgressCalculator {

    /**
     * 新鲜度闸门：最后一次派息距今超过这个月数，就不再外推「下一笔」。
     *
     * 业界（Dividend Wealth 的 quality gate）会把陈旧的外推行直接丢掉——
     * 一只三年没分红的标的，拿 2023 年那次派息去预告今年的到账日是自欺欺人。
     * 定 12 个月是为了和「全年预计」的 TTM 口径一致：**连 TTM 都统计不到的标的，
     * 就不该出现在预计里**。数据源自己排定的未来派息不受此限制（那是真数据，不是推算）。
     *
     * 判定用 `lastDate.isBefore(today - 12个月)`，与 `getTTMDividend` 的闭区间窗口对齐。
     */
    private const val STALE_MONTHS = 12L

    data class NextPayout(
        val assetName: String,
        val symbol: String?,
        /** yyyy-MM-dd，除息日：决定你有没有资格拿这笔钱 */
        val date: String,
        /**
         * yyyy-MM-dd，分红发放日——钱真正到账的日子。
         * 拿不到就是 null，此时界面退回显示 [date]（除息日）并注明。
         */
        val payDate: String? = null,
        /** 距离**到账**的天数（没有 payDate 时按除息日算） */
        val daysUntil: Int,
        val amount: Double,
        /** true=按历史同期推算出的日期；false=数据源已排定的派息日 */
        val isEstimated: Boolean
    ) {
        /** 界面上要显示的日子：优先到账日 */
        val displayDate: String get() = payDate ?: date
    }

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

        // 显式标签：外层遍历资产、内层遍历分红记录，两个都叫 @forEach 会让 return@forEach 产生歧义
        assets.filter { it.type == AssetType.STOCK || it.type == AssetType.FUND }.forEach assetLoop@{ asset ->
            val symbol = asset.symbol ?: return@assetLoop
            val records = recordsBySymbol[symbol] ?: return@assetLoop
            if (records.isEmpty()) return@assetLoop

            val balance = asset.balance
            val ttmPerTenShares = DividendYieldCalculator.getTTMDividend(records, today)
            yearProjected += perTenSharesToAmount(ttmPerTenShares, balance)

            // 已排定的未来派息：用于倒计时（数据源给的日期比推算更可信）
            // 三元组 = 除息日 / 到账日 / 金额
            var earliestScheduled: Triple<String, String?, Double>? = null

            records.forEach recordLoop@{ record ->
                val date = record.exDividendDate.take(10)
                if (date.length != 10) return@recordLoop
                val month = date.substring(5, 7).toIntOrNull() ?: return@recordLoop
                val amount = record.amountFor(balance)

                when {
                    date >= yearStart && date <= todayStr -> {
                        yearReceived += amount
                        receivedCount += 1
                        monthlyReceived[month] += amount
                    }
                    date > todayStr && date.startsWith("$year-") -> {
                        monthlyScheduled[month] += amount
                        // 比谁更早，要比「到账日」——用户关心的是钱什么时候进来
                        val payDate = record.paymentDate
                        val sortKey = payDate ?: date
                        if (earliestScheduled == null || sortKey < earliestScheduled!!.first) {
                            earliestScheduled = Triple(date, payDate, amount)
                        }
                    }
                }
            }

            val candidate = earliestScheduled?.let { (date, payDate, amount) ->
                NextPayout(
                    assetName = asset.name,
                    symbol = symbol,
                    date = date,
                    payDate = payDate,
                    daysUntil = daysBetween(today, payDate ?: date),
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
        val lastRecord = records.sortedByDescending { it.exDividendDate.take(10) }.firstOrNull()
            ?: return null

        // 新鲜度闸门：与 TTM 同一判定 —— 窗口是 [今天-12个月, 今天]，
        // 所以只有「严格早于 12 个月前」才算陈旧（边界那天 TTM 算得到，这里就还能推）
        // 这里不打印日志 —— 计算器保持纯函数，才好吃单测
        val lastDate = parseDate(lastRecord.exDividendDate.take(10)) ?: return null
        if (lastDate.isBefore(today.minusMonths(STALE_MONTHS))) return null

        val predicted = DividendIncomeCalculator.predictNextDividendDate(records) ?: return null
        val base = parseDate(predicted) ?: return null
        val date = if (!base.isBefore(today)) base else base.plusYears(1)

        val amount = lastRecord.amountFor(balance)
        // 到账日 = 推算出的除息日 + 这只标的历史上「除息→到账」的天数
        val payDate = paymentLagDays(lastRecord)?.let { date.plusDays(it) }

        return NextPayout(
            assetName = assetName,
            symbol = symbol,
            date = date.toString(),
            payDate = payDate?.toString(),
            daysUntil = daysBetween(today, (payDate ?: date).toString()),
            amount = amount,
            isEstimated = true
        )
    }

    /**
     * 这只标的历史上「除息日 → 到账日」隔了多少天。
     *
     * 业界（Dividend Wealth 的 forecast）用的是每只标的自己的 ex→pay 间隔，
     * 而不是拍一个通用值——不同公司/基金差得挺多。
     * 派息到账通常在 0~60 天内，超出这个范围说明数据源的日期对不上，宁可不用。
     */
    private fun paymentLagDays(record: DividendRecord): Long? {
        val pay = record.paymentDate?.let { parseDate(it) } ?: return null
        val ex = parseDate(record.exDividendDate.take(10)) ?: return null
        val days = ChronoUnit.DAYS.between(ex, pay)
        return if (days in 0..60) days else null
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
