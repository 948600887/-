package com.example.stockdiary.data.dividend

import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.database.perTenSharesToAmount
import com.example.stockdiary.utils.ExchangeRateManager
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.Year

/**
 * 「自然年口径年度收入」单一数据源测试：
 * [DividendProgressCalculator.naturalYearAnnualIncome] 同时是
 * - Dashboard/组合/日历/FIRE 的「预计年度收入/年度分红/年收入」（经 [DividendIncomeLoader]）
 * - 收息进度「今年预计」（[DividendProgressCalculator.calculate] 的 yearProjected）
 * 两路的共同来源，本测试锁定其口径与「两数字严格相等」的不变量。
 */
class NaturalYearIncomeTest {

    private fun stock(symbol: String, balance: Double = 100.0, currency: String = "CNY") = Asset(
        name = symbol, type = AssetType.STOCK, symbol = symbol, account = "默认",
        balance = balance, currency = currency
    )

    private fun fund(symbol: String, balance: Double = 100.0, currency: String = "CNY") = Asset(
        name = symbol, type = AssetType.FUND, symbol = symbol, account = "默认",
        balance = balance, currency = currency
    )

    private fun moneyFund(symbol: String, balance: Double, mci: Double, currency: String = "CNY") = Asset(
        name = symbol, type = AssetType.FUND, symbol = symbol, account = "默认",
        balance = balance, currency = currency, millionCopiesIncome = mci
    )

    private fun deposit(name: String, balance: Double, rate: Double?, currency: String = "CNY") = Asset(
        name = name, type = AssetType.BANK_DEPOSIT, symbol = null, account = "默认",
        balance = balance, currency = currency, interestRate = rate
    )

    private fun record(date: String, amount: String) = DividendRecord(
        exDividendDate = date, dividendPerShare = amount, announceDate = "", fiscalYear = date.take(4)
    )

    /** 货基日收益（与 naturalYearAnnualIncome / moneyFundIncome 同源公式） */
    private fun mmfDaily(asset: Asset): Double =
        ExchangeRateManager.toCny(asset.balance * (asset.millionCopiesIncome ?: 0.0) / 10_000.0, asset.currency)

    /**
     * ① 单只普通股：今年已宣告 2 笔 + 去年 12 月 1 笔（在 TTM 窗内）。
     * 自然年口径只算今年实额，不算去年那笔；且与 Progress.yearProjected 的现金部分相等。
     */
    @Test
    fun `普通股_只算今年已宣告实额_且等于收息进度今年预计现金部分`() {
        val today = LocalDate.of(2026, 6, 1)
        val asset = stock("sh600519", balance = 100.0)
        val records = listOf(
            record("2026-03-10", "30.0"),  // 今年：30/10*100 = 300
            record("2026-09-15", "20.0"),  // 今年（未到账也计入）：20/10*100 = 200
            record("2025-12-05", "99.0")   // 去年（TTM 窗内但非今年）→ 不计入自然年
        )
        val recordsBySymbol = mapOf("sh600519" to records)
        val assets = listOf(asset)

        val total = DividendProgressCalculator.naturalYearAnnualIncome(assets, recordsBySymbol, today)

        // 只算今年两笔 = 300 + 200 = 500，去年那笔不算
        assertEquals(500.0, total, 1e-9)

        // 与收息进度「今年预计」同源：单只普通股无货基/无存款，yearProjected 即现金部分
        val progress = DividendProgressCalculator.calculate(assets, recordsBySymbol, today)
        assertEquals(progress.yearProjected, total, 1e-9)
        assertEquals(500.0, progress.yearProjected, 1e-9)
    }

    /**
     * ② 今年尚未宣告的标的 → 退回 TTM，断言等于旧 TTM 值。
     */
    @Test
    fun `今年未宣告_退回TTM_等于旧TTM值`() {
        val today = LocalDate.of(2026, 6, 1)
        val asset = stock("sh600519", balance = 100.0)
        // 只有去年分红记录，今年 0 笔已宣告
        val records = listOf(
            record("2025-07-01", "40.0"),
            record("2025-09-01", "10.0")
        )
        val recordsBySymbol = mapOf("sh600519" to records)
        val assets = listOf(asset)

        val total = DividendProgressCalculator.naturalYearAnnualIncome(assets, recordsBySymbol, today)

        // TTM（两笔都在 [2025-06-01, 2026-06-01] 窗内）= (40+10)/10 * 100 = 500
        val ttm = DividendYieldCalculator.getTTMDividend(records, today) // = 50.0
        val expected = ExchangeRateManager.toCny(perTenSharesToAmount(ttm, 100.0), asset.currency)
        assertEquals(500.0, expected, 1e-9)
        assertEquals(expected, total, 1e-9)

        // 与收息进度今年预计一致
        val progress = DividendProgressCalculator.calculate(assets, recordsBySymbol, today)
        assertEquals(total, progress.yearProjected, 1e-9)
    }

    /**
     * ③ 货基：日收益 × 当年天数。闰年 366 与平年 365 各一例。
     */
    @Test
    fun `货基_日收益乘当年天数_闰年366平年365`() {
        val assetLeap = moneyFund("519518", balance = 10_000.0, mci = 1.0)   // 日收益 = 10000*1/10000 = 1.0 CNY
        val assetCommon = moneyFund("004972", balance = 20_000.0, mci = 0.5) // 日收益 = 20000*0.5/10000 = 1.0 CNY

        val todayLeap = LocalDate.of(2024, 6, 1)   // 2024 闰年
        val todayCommon = LocalDate.of(2025, 6, 1) // 2025 平年

        val totalLeap = DividendProgressCalculator.naturalYearAnnualIncome(
            listOf(assetLeap), emptyMap(), todayLeap
        )
        val totalCommon = DividendProgressCalculator.naturalYearAnnualIncome(
            listOf(assetCommon), emptyMap(), todayCommon
        )

        // 2024 闰年 → 366 天；2025 平年 → 365 天
        assertEquals(1.0 * 366.0, totalLeap, 1e-9)
        assertEquals(1.0 * 365.0, totalCommon, 1e-9)
        assertEquals(366, Year.of(2024).length())
        assertEquals(365, Year.of(2025).length())

        // 与 moneyFundIncome 的 projected 同源（确保两处不会差 365/366）
        assertEquals(mmfDaily(assetLeap) * 366.0, totalLeap, 1e-9)
        assertEquals(mmfDaily(assetCommon) * 365.0, totalCommon, 1e-9)
    }

    /**
     * ④ 跨年边界：去年 12 月 ex-date 不计入今年预计；今年 12 月 ex-date 计入。
     */
    @Test
    fun `跨年边界_去年12月不计_今年12月计入`() {
        val today = LocalDate.of(2026, 12, 20)
        val balance = 100.0

        // 去年 12 月 15 日：非今年已宣告 → 退回 TTM；today 选在 12/20，使该记录落在 TTM 窗外 → 0
        val lastDec = stock("shA", balance = balance)
        val lastDecRecords = listOf(record("2025-12-15", "50.0"))
        val lastDecTotal = DividendProgressCalculator.naturalYearAnnualIncome(
            listOf(lastDec), mapOf("shA" to lastDecRecords), today
        )
        // 2025-12-15 不在 [2025-12-20, 2026-12-20] 窗内 → TTM=0 → 今年收入 0
        assertEquals(0.0, lastDecTotal, 1e-9)

        // 今年 12 月 15 日：今年已宣告 → 计入
        val thisDec = stock("shB", balance = balance)
        val thisDecRecords = listOf(record("2026-12-15", "50.0"))
        val thisDecTotal = DividendProgressCalculator.naturalYearAnnualIncome(
            listOf(thisDec), mapOf("shB" to thisDecRecords), today
        )
        assertEquals(500.0, thisDecTotal, 1e-9) // 50/10*100

        // 与收息进度今年预计一致
        assertEquals(thisDecTotal, DividendProgressCalculator.calculate(
            listOf(thisDec), mapOf("shB" to thisDecRecords), today
        ).yearProjected, 1e-9)
        assertEquals(lastDecTotal, DividendProgressCalculator.calculate(
            listOf(lastDec), mapOf("shA" to lastDecRecords), today
        ).yearProjected, 1e-9)
    }

    /**
     * ⑤ 共享函数被 calculate 与 DividendIncomeLoader 两条路径共用：
     * Progress.yearProjected == naturalYearAnnualIncome(...) 在同输入下相等。
     * 覆盖混合持仓：普通股 + 基金 + 货基 + 存款，确保含存款口径也一致。
     */
    @Test
    fun `共享函数两路同源_yearProjected等于naturalYearAnnualIncome`() {
        val today = LocalDate.of(2026, 8, 1)
        val assets = listOf(
            stock("sh600519", balance = 100.0),
            fund("510300", balance = 200.0),
            moneyFund("519518", balance = 10_000.0, mci = 1.0),
            deposit("我的定存", balance = 50_000.0, rate = 2.0)
        )
        val recordsBySymbol = mapOf(
            "sh600519" to listOf(record("2026-06-01", "50.0")),
            "510300" to listOf(record("2026-03-01", "15.0"))
            // 519518 货基无现金分红记录；定存无记录
        )

        val direct = DividendProgressCalculator.naturalYearAnnualIncome(assets, recordsBySymbol, today)
        val progress = DividendProgressCalculator.calculate(assets, recordsBySymbol, today)

        // 两路必须严格相等（同一把自然年尺子）
        assertEquals(direct, progress.yearProjected, 1e-9)

        // 手算交叉验证 direct 的组成（CNY 组合，toCny 恒等）：
        // 股票 600519: 50/10*100 = 500
        // 基金 510300: 15/10*200 = 300
        // 货基 519518: 日收益 1.0 × 365（2026 平年）= 365
        // 定存: 50000 × 2/100 = 1000
        // 合计 = 500 + 300 + 365 + 1000 = 2165
        assertEquals(2165.0, direct, 1e-9)
    }

    /**
     * 银行存款走自然年函数也计入利息（与旧的 TTM 口径 totalAnnualIncome 一致，
     * 并因此与收息进度今年预计对齐）。
     */
    @Test
    fun `银行存款利息计入年度收入`() {
        val today = LocalDate.of(2026, 5, 1)
        val assets = listOf(deposit("定存A", balance = 100_000.0, rate = 3.0))
        val total = DividendProgressCalculator.naturalYearAnnualIncome(assets, emptyMap(), today)
        // 存款无每十股分红、无货基字段：利息 = totalValueCny × 3/100 = 100000 × 0.03 = 3000
        assertEquals(3000.0, total, 1e-9)
    }
}
