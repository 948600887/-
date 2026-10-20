package com.example.stockdiary.data.dividend

import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.database.amountFor
import com.example.stockdiary.database.perTenSharesToAmount
import com.example.stockdiary.utils.ExchangeRateManager
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/**
 * 「收息进度」计算器——借鉴同类股息 App 的头号卖点：
 * **今年已经收到多少股息 / 今年预计能收多少**，外加月度现金流节奏与下一笔到账倒计时。
 *
 * 设计原则（与全项目一致）：**只统计真实存在的分红记录，不编造数字**。
 * 某个月没有派息记录就是 0，绝不按平均值把柱子抹平。
 *
 * 口径说明（分子分母同一把日历尺，都是自然年）：
 * - 今年预计 = 今年已宣告（除息日在今年，含已排定未到账的）→ 按这些记录的金额合计；
 *   今年还没宣告的标的 → 退回 TTM（最近 12 个月）每十股分红 × 持仓数量 ÷ 10 预估，
 *   否则年初分母会是 0，进度条显得空。
 *   从前这里是纯 TTM，与按日历年统计的今年已收不同口径：
 *   进度条可能永远填不满，TTM 窗口横跨两年时还会算出「已收 > 预计」。
 * - 今年已收 = **到账日**落在「今年 1 月 1 日 ~ 今天」之间的记录 × 持仓数量 ÷ 10
 * - 月度柱图只认**今年**的记录：已过的是实收，未来的已排定派息是预计。
 *   归月一律按**到账日**而非除息日——除息日只是「有资格拿」的日子，
 *   钱真正进来通常晚 1~4 周；用除息日归月会把跨月的那笔画进上个月。
 *   数据源没给发放日时才退回除息日。
 * - 下一笔：优先数据源已排定的未来派息（**不限年份**，钱下一次进来就该显示）；
 *   没有才按历史同期推算，且**最后一次派息距今超过 12 个月就不再推算**（见 [STALE_MONTHS]）
 */
object DividendProgressCalculator {

    /**
     * 新鲜度闸门：最后一次派息距今超过这个月数，就不再外推「下一笔」。
     *
     * 业界（Dividend Wealth 的 quality gate）会把陈旧的外推行直接丢掉——
     * 一只三年没分红的标的，拿 2023 年那次派息去预告今年的到账日是自欺欺人。
     * 定 12 个月是为了和预计口径的 TTM 退回保持一致：今年没有已宣告分红、
     * 只能拿近 12 个月预估时，**连 TTM 都统计不到的标的，就不该出现在预计里**。
     * 数据源自己排定的未来派息不受此限制（那是真数据，不是推算）。
     *
     * 判定用 `lastDate.isBefore(today - 12个月)`，与 `getTTMDividend` 的闭区间窗口对齐。
     */
    private const val STALE_MONTHS = 12L

    /**
     * 每万份收益的「万份」：货币基金按**每一万份**公布当天赚多少，
     * 故日收益 = 持有份额 × 每万份收益 ÷ 10000。
     */
    private const val COPIES_PER_UNIT = 10_000.0

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
            val records = recordsBySymbol[symbol].orEmpty()

            // 货币基金不发现金分红，收益是每日「每万份收益」，必须在这里单独进收息进度：
            // 否则它会撞上下面「无分红记录 → 跳过」，今年预计里永远是 0。
            // 与现金分红分支并存累加：极少数货基可能同时带分红记录，两条路都该算进来。
            val moneyFund = moneyFundIncome(asset, today, monthlyReceived, monthlyScheduled)
            yearProjected += moneyFund.projected
            yearReceived += moneyFund.received

            if (records.isEmpty()) return@assetLoop

            val balance = asset.balance

            // 预计口径 = 自然年（与分子 yearReceived、月度柱图同一个日历口径）：
            // - 今年已宣告（除息日在今年，含已排定未到账的）→ 按这些记录的金额合计
            // - 今年还没宣告的标的 → 退回 TTM 预估，否则年初分母会是 0，进度条显得空
            // 从前这里是纯 TTM，与按日历年统计的 yearReceived 不同口径：
            // 进度条可能永远填不满，TTM 窗口横跨两年时还会算出「已收 > 预计」。
            // 判定用除息日、不用到账日：12 月除息、次年 1 月到账的分红，
            // 宣告发生在今年，就该计入今年的预计（否则年末分母会突然少一块）。
            val thisYearRecords = records.filter {
                val exDate = it.exDividendDate.take(10)
                // 脏数据保护与该函数里归月处同一风格：日期串不满 10 位的一律不采信
                exDate.length == 10 && exDate >= yearStart
            }
            // 折算成人民币再累加：持仓可能是美元/港币基金，native 金额不能直接和 A 股相加。
            // 默认币种 CNY 时 toCny 是恒等变换，不影响原有的纯人民币组合。
            yearProjected += if (thisYearRecords.isNotEmpty()) {
                thisYearRecords.sumOf { ExchangeRateManager.toCny(it.amountFor(balance), asset.currency) }
            } else {
                ExchangeRateManager.toCny(
                    perTenSharesToAmount(DividendYieldCalculator.getTTMDividend(records, today), balance),
                    asset.currency
                )
            }

            // 已排定的未来派息：用于倒计时（数据源给的日期比推算更可信）
            // 三元组 = 除息日 / 到账日 / 金额
            var earliestScheduled: Triple<String, String?, Double>? = null
            // 上面三元组的排序键（到账日，拿不到到账日时是除息日）。
            // 单独存一份：否则会拿「到账日」去比三元组里存的「除息日」，
            // 两个不是同一个东西，会挑错「下一笔」。
            var earliestCashDate: String? = null

            records.forEach recordLoop@{ record ->
                val exDate = record.exDividendDate.take(10)
                if (exDate.length != 10) return@recordLoop
                // 折算成人民币：单笔分红按标的币种换算后再归月 / 计入已收
                val amount = ExchangeRateManager.toCny(record.amountFor(balance), asset.currency)

                // 归月与「已到账 / 待到账」一律按**到账日**算，不能按除息日。
                // 除息日只决定「你有没有资格拿」，钱真正进来通常晚 1~4 周：
                // 3/28 除息、4/10 到账的那笔，属于 4 月而不是 3 月。
                // 原先按除息日归月，整张节奏图会前移，跨月的那笔还画错月份。
                // 数据源没给发放日（如新浪）时退回除息日。
                val payDate = record.paymentDate?.take(10)?.takeIf { it.length == 10 }
                val cashDate = payDate ?: exDate
                val month = cashDate.substring(5, 7).toIntOrNull() ?: return@recordLoop

                when {
                    cashDate >= yearStart && cashDate <= todayStr -> {
                        yearReceived += amount
                        receivedCount += 1
                        monthlyReceived[month] += amount
                    }
                    cashDate > todayStr -> {
                        // 柱图只有今年 12 个月：跨年到账的那笔不画进今年的图
                        if (cashDate.startsWith("$year-")) {
                            monthlyScheduled[month] += amount
                        }
                        // 「下一笔」找的是钱下一次什么时候进来，与年份无关——
                        // 年末排定、次年初到账的那笔恰恰最该显示。
                        // 排序键与比较对象同为到账日，避免挑错。
                        if (earliestCashDate == null || cashDate < earliestCashDate!!) {
                            earliestCashDate = cashDate
                            earliestScheduled = Triple(exDate, payDate, amount)
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
            } ?: predictNext(asset.name, symbol, records, balance, asset.currency, today)

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

    private data class MoneyFundIncome(val projected: Double, val received: Double) {
        companion object {
            val ZERO = MoneyFundIncome(0.0, 0.0)
        }
    }

    /**
     * 货币基金（每万份收益）对收息进度的贡献。
     *
     * 货币基金不发现金分红，收益**每天**按份额派发：每万份收益
     * （[Asset.millionCopiesIncome]）= 每一万份当天赚多少，
     * 所以持有 balance 份的日收益 = balance × 万份收益 ÷ 10000。
     *
     * 与现金分红口径的差异（务必记住）：
     * - **今年预计** = 日收益 × 当年天数。前向预估，与现金分红的今年预计同为 estimate 性质。
     * - **今年已收** = 日收益 × 今年已过天数。**这是估算，不是真实到账记录**：
     *   我们只存了**最新**的万份收益，没有历史每日序列，只能拿最新值单点外推。
     *   用户已确认接受（否则货基已收恒为 0、进度条永远空着，反而更误导）。
     * - **月度柱图**：已过天数进 received、当年剩余天数进 scheduled，
     *   两段相加正好等于今年预计，柱图与「今年预计」不会打架。
     * - **不进 receivedCount**：那是「派息笔数」；货基按日计息，逐日计数会把笔数冲到几百。
     * - **不设 nextPayout**：货基按日结息，没有「下一笔」概念。
     *
     * 币种：与这个文件里现金分红的口径一致，**统一折算成人民币**（[asset.currency] → CNY），
     * 这样跨币种持仓（如美元货基）的收息进度才能和 A 股 / 人民币基金加总在同一把尺子上。
     * [perTenSharesToAmount] 本身不换，换算在调用处用 [ExchangeRateManager.toCny] 做。
     */
    private fun moneyFundIncome(
        asset: Asset,
        today: LocalDate,
        monthlyReceived: DoubleArray,
        monthlyScheduled: DoubleArray
    ): MoneyFundIncome {
        val mci = asset.millionCopiesIncome
        val balance = asset.balance
        if (mci == null || mci <= 0.0 || balance <= 0.0) return MoneyFundIncome.ZERO

        val daily = ExchangeRateManager.toCny(balance * mci / COPIES_PER_UNIT, asset.currency)
        val daysInYear = Year.of(today.year).length()
        val elapsed = today.dayOfYear.coerceIn(0, daysInYear)

        // 已过天数填进各月 received，当年剩余天数填进 scheduled
        var remaining = elapsed
        for (month in 1..12) {
            val dim = YearMonth.of(today.year, month).lengthOfMonth()
            val days = remaining.coerceAtMost(dim)
            if (days > 0) {
                monthlyReceived[month] += daily * days
                remaining -= days
                // 当月只过了一部分时，该月剩下的天数属未来 → 计入 scheduled
                val rest = dim - days
                if (rest > 0) monthlyScheduled[month] += daily * rest
            } else {
                monthlyScheduled[month] += daily * dim
            }
        }

        return MoneyFundIncome(projected = daily * daysInYear, received = daily * elapsed)
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
        currency: String,
        today: LocalDate
    ): NextPayout? {
        val lastRecord = records.sortedByDescending { it.exDividendDate.take(10) }.firstOrNull()
            ?: return null

        // 新鲜度闸门：与 TTM 同一判定 —— 窗口是 [今天-12个月, 今天]，
        // 所以只有「严格早于 12 个月前」才算陈旧（边界那天 TTM 算得到，这里就还能推）
        // 这里不打印日志 —— 计算器保持纯函数，才好吃单测
        val lastDate = parseDate(lastRecord.exDividendDate.take(10)) ?: return null
        if (lastDate.isBefore(today.minusMonths(STALE_MONTHS))) return null

        // 透传 today：否则预测会退回用真实系统时钟，与这里的 TTM / 已收口径不一致
        val predicted = DividendIncomeCalculator.predictNextDividendDate(records, today) ?: return null
        val base = parseDate(predicted) ?: return null
        val date = if (!base.isBefore(today)) base else base.plusYears(1)

        val amount = ExchangeRateManager.toCny(lastRecord.amountFor(balance), currency)
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
