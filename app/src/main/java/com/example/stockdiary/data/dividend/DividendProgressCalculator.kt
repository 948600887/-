package com.example.stockdiary.data.dividend

import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.database.amountFor
import com.example.stockdiary.database.perTenSharesToAmount
import com.example.stockdiary.utils.ExchangeRateManager
import com.example.stockdiary.utils.totalValueCny
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/**
 * 「收息进度」计算器——借鉴同类股息 App 的头号卖点：
 * **今年已经收到多少股息 / 今年预计能收多少**，外加月度现金流节奏与下一笔除息倒计时。
 *
 * 设计原则（与全项目一致）：**只统计真实存在的分红记录，不编造数字**。
 * 某个月没有派息记录就是 0，绝不按平均值把柱子抹平。
 *
 * 口径说明（分子分母同一把日历尺，都是自然年，**且一律用除息日**）：
 * - 今年预计 = 今年已宣告（除息日在今年，含已排定未到账的）→ 按这些记录的金额合计；
 *   今年还没宣告的标的 → 退回 TTM（最近 12 个月）每十股分红 × 持仓数量 ÷ 10 预估，
 *   否则年初分母会是 0，进度条显得空。
 *   从前这里是纯 TTM，与按日历年统计的今年已收不同口径：
 *   进度条可能永远填不满，TTM 窗口横跨两年时还会算出「已收 > 预计」。
 * - 今年已收 = **除息日**落在「今年 1 月 1 日 ~ 今天」之间的记录 × 持仓数量 ÷ 10。
 *   从前按**到账日/发放日**（缺失才退回除息日），与分母的除息日不是同一把尺子：
 *   「去年 12 月除息、今年 1 月发放」的跨年分红只进分子不进分母，
 *   于是算出「今年已收 > 今年预计」并弹出「请检查分红数据」的提示。
 *   现在分子分母同源：已收集合恒为预计集合的子集，已收不可能反超预计。
 * - 月度柱图只认**今年**的记录：已过的是实收，未来的已排定派息是预计。
 *   归月同样按**除息日**（与今年预计同源），不再按到账日——
 *   按到账日归月会把跨年发放的那笔画进「今年某月」，与按除息日统计的分母错位。
 *   （日历页仍按到账日显示，那是另一处有意保留的口径，见 `DividendData.cashDateOrNull`，勿混）
 * - 下一笔：优先数据源已排定的未来除息（**不限年份**，下一次除息就该显示）；
 *   没有才按历史同期推算，且**最后一次派息距今超过 12 个月就不再推算**（见 [STALE_MONTHS]）。
 *   倒计时也按除息日算，不再推算发放日（[NextPayout.payDate] 恒为 null，界面显示除息日）。
 *
 * 例外——货币基金：货基不发现金分红、没有除息记录，走不了上面这套口径，
 * 仍按「日收益 × 今年已过天数」估算今年已收、按「日收益 × 当年天数」估算今年预计
 * （见 [moneyFundIncome]，那里按日计息、与除息日无关）。
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
         * 收息进度统一按除息日，本计算器恒传 null。
         */
        val payDate: String? = null,
        /** 距离**除息日**的天数（有 payDate 时按 payDate 算） */
        val daysUntil: Int,
        val amount: Double,
        /** true=按历史同期推算出的日期；false=数据源已排定的派息日 */
        val isEstimated: Boolean
    ) {
        /** 界面上要显示的日子：优先到账日，没有就是除息日 */
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
            // moneyFundIncome 只负责往 yearReceived 与月度柱图填货基的「已收/待收」；
            // 它的 projected 不再累加到 yearProjected —— 今年预计统一在循环外由
            // naturalYearAnnualIncome 一次性算出（与 DividendIncomeLoader.totalAnnualIncome 同源），
            // 这里重复加会重复计入货基。
            val moneyFund = moneyFundIncome(asset, today, monthlyReceived, monthlyScheduled)
            yearReceived += moneyFund.received

            if (records.isEmpty()) return@assetLoop

            val balance = asset.balance

            // 「今年预计」的现金部分不再在这里累加：统一由循环外的 naturalYearAnnualIncome 负责，
            // 保证与 Dashboard/组合/日历/FIRE 的「年度收入」头条数字严格同源一致。
            // 下面「已排定未来派息」循环只填 yearReceived / 月度柱图 / nextPayout，不影响 yearProjected。

            // 已排定的未来除息：用于倒计时（数据源给的日期比推算更可信）
            // 二元组 = 除息日 / 金额。新口径不再用到账日，排序键就是除息日自身，
            // 不存在「拿 A 日期去比 B 日期」的错位。
            var earliestScheduled: Pair<String, Double>? = null

            records.forEach recordLoop@{ record ->
                val exDate = record.exDividendDate.take(10)
                if (exDate.length != 10) return@recordLoop
                // 折算成人民币：单笔分红按标的币种换算后再归月 / 计入已收
                val amount = ExchangeRateManager.toCny(record.amountFor(balance), asset.currency)

                // 归月与「已到账 / 待到账」一律按**除息日**算，与今年预计同源。
                // 从前按到账日（发放日）算：「去年 12 月除息、今年 1 月发放」的跨年分红
                // 会混进今年的已收，而分母（按除息日）根本不含它 → 算出「已收 > 预计」。
                // 3/28 除息、4/10 到账的那笔，现在归 3 月（除息日所在月）。
                val month = exDate.substring(5, 7).toIntOrNull() ?: return@recordLoop

                when {
                    exDate >= yearStart && exDate <= todayStr -> {
                        yearReceived += amount
                        receivedCount += 1
                        monthlyReceived[month] += amount
                    }
                    exDate > todayStr -> {
                        // 柱图只有今年 12 个月：跨年除息的那笔不画进今年的图
                        if (exDate.startsWith("$year-")) {
                            monthlyScheduled[month] += amount
                        }
                        // 「下一笔」找的是下一次除息什么时候发生，与年份无关——
                        // 年末排定、次年初除息的那笔恰恰最该显示。
                        // 排序键与比较对象同为除息日，避免挑错。
                        if (earliestScheduled == null || exDate < earliestScheduled!!.first) {
                            earliestScheduled = Pair(exDate, amount)
                        }
                    }
                }
            }

            // payDate 恒为 null：收息进度按除息日，界面显示的就是 [date]（除息日）
            val candidate = earliestScheduled?.let { (date, amount) ->
                NextPayout(
                    assetName = asset.name,
                    symbol = symbol,
                    date = date,
                    payDate = null,
                    daysUntil = daysBetween(today, date),
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

        // 今年预计 = 自然年口径的年度收入（单一数据源）：与 DividendIncomeLoader.totalAnnualIncome
        // 同源，保证「收息进度今年预计」与「预计年度收入/年度分红/年收入」两个头条数字严格一致。
        // 循环里只算过 yearReceived / 月度柱图 / nextPayout，yearProjected 在此一次性算出。
        yearProjected = naturalYearAnnualIncome(assets, recordsBySymbol, today)

        return Progress(
            yearReceived = yearReceived,
            yearProjected = yearProjected,
            receivedCount = receivedCount,
            nextPayout = next,
            monthly = (1..12).map { MonthCash(it, monthlyReceived[it], monthlyScheduled[it]) }
        )
    }

    /**
     * 自然年口径的「年度收入」——**单一数据源**，同时喂给：
     * - [DividendIncomeLoader.IncomeResult.totalAnnualIncome]（驱动 Dashboard 预计年度收入、
     *   Portfolio 年度分红、Calendar 年收入、FIRE 月均）；
     * - [calculate] 的 [Progress.yearProjected]（收息进度「今年预计」）。
     *
     * 与收息进度的「今年预计」逐字节同源：同一把自然年尺子，保证这两个头条数字严格相等。
     *
     * 口径（自然年 = 除息日落在今年 1/1 ~ 12/31；与 [Progress.yearProjected] 完全一致）：
     * - 银行存款：年化利息 = totalValueCny × 利率/100（存款无现金分红，只有利息）；
     * - 股票/基金且为货币基金（[Asset.millionCopiesIncome] > 0）：日收益 × 当年天数
     *   （闰年 366、平年 365，与 [moneyFundIncome] 的 projected 同一公式，避免两处差 365/366）；
     * - 股票/基金非货基：今年已宣告（除息日 ≥ 今年 1/1）的记录实额 × 持仓累加；
     *   今年还没宣告的 → 退回 TTM（[DividendYieldCalculator.getTTMDividend]）每十股分红 × 持仓，
     *   否则年初分母会塌成 0，进度条显得空。
     * - 其它类型（虚拟货币、负债等）→ 0。
     *
     * 注意：货币基金「同时带分红记录」的极少见情况下只走货基分支（不重复累加现金），
     * 与 [calculate] 经本函数算出的 [Progress.yearProjected] 保持同源一致。
     */
    fun naturalYearAnnualIncome(
        assets: List<Asset>,
        recordsBySymbol: Map<String, List<DividendRecord>>,
        today: LocalDate
    ): Double {
        val year = today.year
        val yearStart = "$year-01-01"
        val daysInYear = Year.of(year).length()

        return assets.sumOf { asset ->
            when (asset.type) {
                AssetType.BANK_DEPOSIT -> {
                    // 存款：年化利息 = 本金（totalValue=balance）× 利率/100
                    val rate = asset.interestRate ?: 0.0
                    asset.totalValueCny * (rate / 100.0)
                }
                AssetType.STOCK, AssetType.FUND -> {
                    val balance = asset.balance
                    val mci = asset.millionCopiesIncome
                    // 货币基金：日收益 = 份额 × 每万份收益 ÷ 10000，年化 = 日收益 × 当年天数
                    if (mci != null && mci > 0.0 && balance > 0.0) {
                        val daily = ExchangeRateManager.toCny(balance * mci / COPIES_PER_UNIT, asset.currency)
                        daily * daysInYear
                    } else {
                        val records = recordsBySymbol[asset.symbol ?: ""].orEmpty()
                        // 今年已宣告（除息日 ≥ 今年 1/1，含已排定未到账）→ 这些记录实额 × 持仓
                        val thisYear = records.filter { record ->
                            val exDate = record.exDividendDate.take(10)
                            exDate.length == 10 && exDate >= yearStart
                        }
                        if (thisYear.isNotEmpty()) {
                            thisYear.sumOf { record ->
                                ExchangeRateManager.toCny(record.amountFor(balance), asset.currency)
                            }
                        } else {
                            // 今年还没宣告 → 退回 TTM 每十股分红 × 持仓
                            ExchangeRateManager.toCny(
                                perTenSharesToAmount(
                                    DividendYieldCalculator.getTTMDividend(records, today),
                                    balance
                                ),
                                asset.currency
                            )
                        }
                    }
                }
                else -> 0.0
            }
        }
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

        // payDate 恒为 null：收息进度统一按除息日，不再拿历史 ex→pay 间隔去推算发放日
        // （那是编造出来的日期，还会让倒计时与「今年已收」用上不同的尺子）
        return NextPayout(
            assetName = assetName,
            symbol = symbol,
            date = date.toString(),
            payDate = null,
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
