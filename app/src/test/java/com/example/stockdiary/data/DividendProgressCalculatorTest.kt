package com.example.stockdiary.data

import com.example.stockdiary.data.dividend.DividendProgressCalculator
import com.example.stockdiary.data.dividend.DividendRecord
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.utils.ExchangeRateManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DividendProgressCalculatorTest {

    private val today: LocalDate = LocalDate.now()
    private val year: Int = today.year

    private fun asset(name: String, symbol: String, balance: Double) = Asset(
        name = name,
        type = AssetType.STOCK,
        symbol = symbol,
        account = "默认",
        balance = balance
    )

    private fun record(date: String, perTenShares: Double) = DividendRecord(
        exDividendDate = date,
        dividendPerShare = perTenShares.toString(),
        announceDate = date,
        fiscalYear = date.take(4)
    )

    private fun dateIn(month: Int, day: Int): String =
        "$year-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"

    // ===== 货币基金（每万份收益）=====

    /**
     * 固定「今天」= 2026-03-01。2026 不是闰年（365 天），3/1 是当年第 60 天。
     * 于是 10000 份 × 每万份收益 1.0 → 日收益 1.0 元：
     * 全年预计 = 1 × 365 = 365，今年已收 = 1 × 60 = 60。
     * 用固定日期而不是 LocalDate.now()，用例才不会跑着跑着就漂。
     */
    private val moneyFundToday: LocalDate = LocalDate.of(2026, 3, 1)

    private fun moneyFundAsset(
        name: String,
        symbol: String,
        balance: Double,
        millionCopiesIncome: Double
    ) = Asset(
        name = name,
        type = AssetType.FUND,
        symbol = symbol,
        account = "默认",
        balance = balance,
        millionCopiesIncome = millionCopiesIncome
    )

    private fun plainFund(name: String, symbol: String, balance: Double) = Asset(
        name = name,
        type = AssetType.FUND,
        symbol = symbol,
        account = "默认",
        balance = balance
    )

    @Test
    fun `今年已收只统计今年1月1日到今天之间的派息`() {
        val assets = listOf(asset("长江电力", "600900", 1000.0))
        val records = mapOf(
            "600900" to listOf(
                record("${year - 1}-06-20", 5.0),   // 去年，不算
                record(dateIn(1, 15), 3.0),          // 今年已过，算
                record(dateIn(12, 20), 2.0)          // 今年未到，不算已收
            )
        )
        val progress = DividendProgressCalculator.calculate(assets, records, today)

        // 每十股 3 元 × 1000 股 ÷ 10 = 300
        assertEquals(300.0, progress.yearReceived, 0.001)
        assertEquals(1, progress.receivedCount)
    }

    @Test
    fun `今年预计按今年已宣告分红的金额合计`() {
        val assets = listOf(asset("长江电力", "600900", 2000.0))
        val records = mapOf(
            "600900" to listOf(record(dateIn(3, 10), 4.0))
        )
        val progress = DividendProgressCalculator.calculate(assets, records, today)

        // 今年有宣告 → 按宣告金额合计，不再走 TTM：
        // 每十股 4 元 × 2000 股 ÷ 10 = 800
        assertEquals(800.0, progress.yearProjected, 0.001)
    }

    @Test
    fun `月度柱图区分已到账与待到账`() {
        val lastMonthDate = today.minusDays(20)
        val nextMonthDate = today.plusDays(20)
        val assets = listOf(asset("长江电力", "600900", 1000.0))
        val records = mapOf(
            "600900" to listOf(
                record(lastMonthDate.toString(), 2.0),
                record(nextMonthDate.toString(), 6.0)
            )
        )
        val progress = DividendProgressCalculator.calculate(assets, records, today)

        val past = progress.monthly[lastMonthDate.monthValue - 1]
        val future = progress.monthly[nextMonthDate.monthValue - 1]
        assertEquals(200.0, past.received, 0.001)
        assertEquals(0.0, past.scheduled, 0.001)
        assertEquals(0.0, future.received, 0.001)
        assertEquals(600.0, future.scheduled, 0.001)
    }

    @Test
    fun `下一笔优先取已排定的派息日而不是推算值`() {
        val scheduledDate = today.plusDays(12)
        val assets = listOf(asset("长江电力", "600900", 1000.0))
        val records = mapOf(
            "600900" to listOf(
                record(dateIn(2, 1), 5.0),
                record(scheduledDate.toString(), 3.0)
            )
        )
        val progress = DividendProgressCalculator.calculate(assets, records, today)

        val next = progress.nextPayout
        assertEquals(scheduledDate.toString(), next?.date)
        assertFalse(next?.isEstimated ?: true)
        assertEquals(300.0, next?.amount ?: 0.0, 0.001)
        assertEquals(12, next?.daysUntil)
    }

    @Test
    fun `推算的下一笔不会给出已经过去的日期`() {
        // 历史派息都在今年 1 月，今天肯定已经过了 1 月 → 推算必须跨到明年
        val assets = listOf(asset("长江电力", "600900", 1000.0))
        val records = mapOf(
            "600900" to listOf(record(dateIn(1, 10), 5.0))
        )
        val progress = DividendProgressCalculator.calculate(assets, records, today)

        val next = progress.nextPayout
        assertEquals(true, next?.isEstimated)
        assertTrue(next!!.date >= today.toString())
        assertTrue(next.daysUntil >= 0)
    }

    @Test
    fun `没有分红记录时不编造数据`() {
        val assets = listOf(asset("长江电力", "600900", 1000.0))
        val progress = DividendProgressCalculator.calculate(assets, emptyMap(), today)

        assertEquals(0.0, progress.yearReceived, 0.001)
        assertEquals(0.0, progress.yearProjected, 0.001)
        assertNull(progress.nextPayout)
        assertFalse(progress.hasData)
        assertEquals(0.0, progress.ratio, 0.001)
        assertTrue(progress.monthly.all { it.received == 0.0 && it.scheduled == 0.0 })
    }

    @Test
    fun `进度比例与剩余额计算正确且不超过100%`() {
        val assets = listOf(asset("长江电力", "600900", 1000.0))
        val records = mapOf(
            "600900" to listOf(record(dateIn(4, 10), 5.0))  // 预计 500，且这笔已到账
        )
        val progress = DividendProgressCalculator.calculate(assets, records, today)

        assertEquals(500.0, progress.yearReceived, 0.001)
        assertEquals(500.0, progress.yearProjected, 0.001)
        assertEquals(1.0, progress.ratio, 0.001)
        assertEquals(0.0, progress.remaining, 0.001)
    }

    @Test
    fun `超过12个月没派息的标的不推算下一笔`() {
        // 510210 的真实场景：最后一次派息在 2023 年，2026 年早已过了两年多
        val staleDate = today.minusMonths(34)
        val assets = listOf(asset("综指ETF", "sh510210", 1000.0))
        val records = mapOf("sh510210" to listOf(record(staleDate.toString(), 0.63)))

        val progress = DividendProgressCalculator.calculate(assets, records, today)

        assertNull(progress.nextPayout)
    }

    @Test
    fun `正好12个月仍在TTM窗口内，闸门不杀它`() {
        // TTM 窗口是闭区间 [今天-12个月, 今天]，边界那天必须算进去；
        // 新鲜度闸门用同一把尺子，所以边界那天也还能推算下一笔。
        // 注意 edgeDate 在**去年** → 今年无已宣告分红，预计走的是 TTM 退回分支
        // （自然年口径下今年没有宣告时才退回 TTM，否则年初分母会是 0），
        // 边界那天仍算进 TTM → 预计 500。
        val edgeDate = today.minusMonths(12)
        val assets = listOf(asset("长江电力", "600900", 1000.0))
        val records = mapOf("600900" to listOf(record(edgeDate.toString(), 5.0)))

        val progress = DividendProgressCalculator.calculate(assets, records, today)

        assertEquals(500.0, progress.yearProjected, 0.001)
        assertNotNull(progress.nextPayout)
    }

    @Test
    fun `早于12个月哪怕只早一天也会被闸门拦下`() {
        val justStale = today.minusMonths(12).minusDays(1)
        val assets = listOf(asset("长江电力", "600900", 1000.0))
        val records = mapOf("600900" to listOf(record(justStale.toString(), 5.0)))

        val progress = DividendProgressCalculator.calculate(assets, records, today)

        // justStale 在去年 → 今年无已宣告分红，预计走 TTM 退回分支；
        // 它又比 12 个月窗口早一天，TTM 也统计不到 → 预计 0
        assertEquals(0.0, progress.yearProjected, 0.001)
        assertNull(progress.nextPayout)
    }

    @Test
    fun `11个月前刚派息的标的仍然推算下一笔`() {
        val recentDate = today.minusMonths(11)
        val assets = listOf(asset("长江电力", "600900", 1000.0))
        val records = mapOf("600900" to listOf(record(recentDate.toString(), 5.0)))

        val progress = DividendProgressCalculator.calculate(assets, records, today)

        val next = progress.nextPayout
        assertNotNull(next)
        assertTrue(next!!.isEstimated)
        assertTrue(next.date >= today.toString())
    }

    @Test
    fun `新鲜度闸门不拦数据源已排定的未来派息`() {
        // 数据源明确给出未来某天要派息，这是真数据不是推算，闸门不该误杀
        val scheduledDate = today.plusDays(20)
        val assets = listOf(asset("长江电力", "600900", 1000.0))
        val records = mapOf(
            "600900" to listOf(
                record(today.minusMonths(30).toString(), 5.0), // 很久没派了
                record(scheduledDate.toString(), 3.0)          // 但已经排定了下一笔
            )
        )

        val progress = DividendProgressCalculator.calculate(assets, records, today)

        val next = progress.nextPayout
        assertEquals(scheduledDate.toString(), next?.date)
        assertFalse(next?.isEstimated ?: true)
    }

    @Test
    fun `下一笔显示到账日而不是除息日`() {
        // 除息日只是「有没有资格」的界点，钱真正到账要晚几天
        val exDate = today.plusDays(10)
        val payDate = exDate.plusDays(5)
        val assets = listOf(asset("长江电力", "600900", 1000.0))
        val records = mapOf(
            "600900" to listOf(
                record(exDate.toString(), 5.0).copy(paymentDate = payDate.toString())
            )
        )

        val progress = DividendProgressCalculator.calculate(assets, records, today)

        val next = progress.nextPayout
        assertEquals(exDate.toString(), next?.date)          // 资格日
        assertEquals(payDate.toString(), next?.displayDate)   // 界面显示到账日
        assertEquals(15, next?.daysUntil)                     // 倒计时按到账日算
    }

    @Test
    fun `推算下一笔时会沿用该标的历史的除息到到账间隔`() {
        // 历史上除息 → 到账隔了 7 天，推算出的那笔也应该加同样的天数
        val lastEx = today.minusMonths(4)
        val lastPay = lastEx.plusDays(7)
        val assets = listOf(asset("长江电力", "600900", 1000.0))
        val records = mapOf(
            "600900" to listOf(
                record(lastEx.toString(), 5.0).copy(paymentDate = lastPay.toString())
            )
        )

        val progress = DividendProgressCalculator.calculate(assets, records, today)

        val next = progress.nextPayout
        assertNotNull(next?.payDate)
        assertEquals(
            7,
            java.time.temporal.ChronoUnit.DAYS.between(
                java.time.LocalDate.parse(next!!.date),
                java.time.LocalDate.parse(next.payDate!!)
            )
        )
    }

    @Test
    fun `银行存款不计入收息进度`() {
        val deposit = Asset(
            name = "定期存款",
            type = AssetType.BANK_DEPOSIT,
            symbol = null,
            account = "银行",
            balance = 100_000.0,
            interestRate = 3.0
        )
        val progress = DividendProgressCalculator.calculate(listOf(deposit), emptyMap(), today)

        assertFalse(progress.hasData)
        assertNull(progress.nextPayout)
    }

    // 下面三个用例用固定的 10/14 作「今天」：前后挪十几天都不会跨年，
    // 避免用例在年末运行时因跨年而失效。
    private val midYear: LocalDate = LocalDate.of(year, 10, 14)

    @Test
    fun `跨月到账的分红归到到账日所在月而不是除息日所在月`() {
        // 3/28 除息、4/10 到账：钱是 4 月进来的，柱子必须画在 4 月。
        // 改之前按除息日归月，这笔会画进 3 月 —— 整张节奏图前移、跨月的那笔还画错月份。
        val assets = listOf(asset("红利LOF", "sh510880", 1000.0))
        val records = mapOf(
            "sh510880" to listOf(
                record("$year-03-28", 2.0).copy(paymentDate = "$year-04-10")
            )
        )

        val progress = DividendProgressCalculator.calculate(assets, records, midYear)

        // 每十股 2 元 × 1000 份 ÷ 10 = 200
        assertEquals(200.0, progress.yearReceived, 0.001)
        assertEquals(0.0, progress.monthly[2].received, 0.001)   // 3 月：空的
        assertEquals(200.0, progress.monthly[3].received, 0.001) // 4 月：200
    }

    @Test
    fun `已除息但钱还没到账不算已收而算待到账`() {
        // 10/04 除息、10/29 到账：今天 10/14 只是「有资格」，钱还没进来
        val assets = listOf(asset("红利LOF", "sh510880", 1000.0))
        val records = mapOf(
            "sh510880" to listOf(
                record("$year-10-04", 2.0).copy(paymentDate = "$year-10-29")
            )
        )

        val progress = DividendProgressCalculator.calculate(assets, records, midYear)

        assertEquals(0.0, progress.yearReceived, 0.001)           // 还没收到
        assertEquals(0, progress.receivedCount)
        assertEquals(0.0, progress.monthly[9].received, 0.001)    // 10 月已到账：0
        assertEquals(200.0, progress.monthly[9].scheduled, 0.001) // 10 月待到账：200
    }

    @Test
    fun `年末排定次年初到账的派息仍算下一笔但不画进今年柱图`() {
        // 12/20 除息、次年 1/10 到账：柱图只有今年 12 个月，这笔不画进今年；
        // 但它是钱下一次真正进来的日子，必须作为「下一笔」显示（数据源排定的真数据）
        val assets = listOf(asset("红利LOF", "sh510880", 1000.0))
        val records = mapOf(
            "sh510880" to listOf(
                record("$year-12-20", 2.0).copy(paymentDate = "${year + 1}-01-10")
            )
        )

        val progress = DividendProgressCalculator.calculate(assets, records, midYear)

        val next = progress.nextPayout
        assertNotNull(next)
        assertEquals("$year-12-20", next?.date)               // 资格日
        assertEquals("${year + 1}-01-10", next?.displayDate)  // 界面显示到账日
        assertFalse(next?.isEstimated ?: true)                // 排定的，不是推算
        assertEquals(0.0, progress.monthly[0].scheduled, 0.001) // 今年 1 月柱图：不画
    }

    @Test
    fun `货币基金_全年预计等于日收益乘当年天数`() {
        val assets = listOf(moneyFundAsset("汇添富货币A", "519518", 10_000.0, 1.0))
        val progress = DividendProgressCalculator.calculate(assets, emptyMap(), moneyFundToday)

        // 10000 份 × 每万份 1 元 ÷ 10000 = 1 元/天；2026 年 365 天
        assertEquals(365.0, progress.yearProjected, 0.001)
    }

    @Test
    fun `货币基金_今年已收按已过天数估算`() {
        val assets = listOf(moneyFundAsset("汇添富货币A", "519518", 10_000.0, 1.0))
        val progress = DividendProgressCalculator.calculate(assets, emptyMap(), moneyFundToday)

        // 2026-03-01 是当年第 60 天 → 1 元/天 × 60 = 60。
        // 这是估算：我们只存最新的万份收益，没有历史每日序列，只能单点外推。
        assertEquals(60.0, progress.yearReceived, 0.001)
    }

    @Test
    fun `货币基金_月度柱图已过进已收剩余进待到账`() {
        val assets = listOf(moneyFundAsset("汇添富货币A", "519518", 10_000.0, 1.0))
        val progress = DividendProgressCalculator.calculate(assets, emptyMap(), moneyFundToday)

        val monthly = progress.monthly
        assertEquals(31.0, monthly[0].received, 0.001)  // 1 月整月
        assertEquals(28.0, monthly[1].received, 0.001)  // 2 月整月
        assertEquals(1.0, monthly[2].received, 0.001)   // 3 月只过了 1 天
        assertEquals(30.0, monthly[2].scheduled, 0.001) // 3 月剩下 30 天算待到账

        // 已收 + 待到账 = 全年预计，柱图与「全年预计」不打架
        assertEquals(365.0, monthly.sumOf { it.received + it.scheduled }, 0.001)
        assertEquals(60.0, monthly.sumOf { it.received }, 0.001)
    }

    @Test
    fun `货币基金_不增加已到账笔数`() {
        // 「已到账笔数」按笔算；货基按日计息，逐日计数会把笔数冲到几百
        val assets = listOf(moneyFundAsset("汇添富货币A", "519518", 10_000.0, 1.0))
        val progress = DividendProgressCalculator.calculate(assets, emptyMap(), moneyFundToday)

        assertEquals(0, progress.receivedCount)
    }

    @Test
    fun `货币基金_多只持仓累加`() {
        val assets = listOf(
            moneyFundAsset("汇添富货币A", "519518", 10_000.0, 1.0),   // 日收益 1 元
            moneyFundAsset("长城收益宝A", "004972", 20_000.0, 1.0)    // 日收益 2 元
        )
        val progress = DividendProgressCalculator.calculate(assets, emptyMap(), moneyFundToday)

        assertEquals(1095.0, progress.yearProjected, 0.001)  // (1+2) × 365
        assertEquals(180.0, progress.yearReceived, 0.001)    // (1+2) × 60
    }

    @Test
    fun `货币基金_份额为零时不计入`() {
        val assets = listOf(moneyFundAsset("汇添富货币A", "519518", 0.0, 1.0))
        val progress = DividendProgressCalculator.calculate(assets, emptyMap(), moneyFundToday)

        assertEquals(0.0, progress.yearProjected, 0.001)
        assertEquals(0.0, progress.yearReceived, 0.001)
    }

    @Test
    fun `普通基金_没有万份收益时仍不计入收息进度`() {
        // 普通基金既无万份收益又无分红记录 → 与改之前行为完全一致（0）
        val assets = listOf(plainFund("某某混合", "000001", 1000.0))
        val progress = DividendProgressCalculator.calculate(assets, emptyMap(), moneyFundToday)

        assertEquals(0.0, progress.yearProjected, 0.001)
        assertEquals(0.0, progress.yearReceived, 0.001)
        assertFalse(progress.hasData)
    }

    @Test
    fun `货币基金_与现金分红资产混合时两者都计入`() {
        val assets = listOf(
            moneyFundAsset("汇添富货币A", "519518", 10_000.0, 1.0), // 预计 365 / 已收 60
            Asset(name = "长江电力", type = AssetType.STOCK, symbol = "600900", account = "默认", balance = 1000.0)
        )
        val records = mapOf("600900" to listOf(record("2026-01-15", 3.0)))

        val progress = DividendProgressCalculator.calculate(assets, records, moneyFundToday)

        // 股票：每十股 3 元 × 1000 ÷ 10 = 300（已到账，1 笔）
        // 货基：365 / 60
        assertEquals(665.0, progress.yearProjected, 0.001)
        assertEquals(360.0, progress.yearReceived, 0.001)   // 300 + 60
        assertEquals(1, progress.receivedCount)             // 只有股票那 1 笔
    }

    // ===== 今年预计 = 自然年口径（分子分母同一把日历尺）=====
    //
    // 改之前分母是纯 TTM（滚动 12 个月），与按日历年统计的今年已收不同口径：
    // 进度条可能永远填不满，TTM 窗口横跨两年时还会算出「已收 > 预计」。
    // 现在：今年已宣告（除息日在今年）→ 按宣告金额合计；今年未宣告 → 退回 TTM。
    // 下面几个用例固定用 midYear（10/14）作「今天」，断言不随运行日期漂移。

    @Test
    fun `今年预计只含今年已宣告的分红不含去年那笔TTM记录`() {
        // 去年 11/20 那笔落在 TTM 窗口 [去年-10-14, 今年-10-14] 内：
        // 改之前按纯 TTM 它会被算进预计（5+3=8 元/十股 → 800）；
        // 现在分母是自然年，只有今年 4/10 宣告的那笔算数 → 300
        val assets = listOf(asset("长江电力", "600900", 1000.0))
        val records = mapOf(
            "600900" to listOf(
                record("${year - 1}-11-20", 5.0),
                record(dateIn(4, 10), 3.0)
            )
        )

        val progress = DividendProgressCalculator.calculate(assets, records, midYear)

        // 每十股 3 元 × 1000 股 ÷ 10 = 300，去年那笔的 500 不计入
        assertEquals(300.0, progress.yearProjected, 0.001)
    }

    @Test
    fun `今年无宣告时预计退回TTM而不是变成0`() {
        // 唯一一笔在去年 11/20（TTM 窗口内）：今年还没宣告 → 退回 TTM 预估，
        // 否则年初分母是 0，进度条显得空
        val assets = listOf(asset("长江电力", "600900", 1000.0))
        val records = mapOf(
            "600900" to listOf(record("${year - 1}-11-20", 5.0))
        )

        val progress = DividendProgressCalculator.calculate(assets, records, midYear)

        // TTM = 5 元/十股 → 5 / 10 × 1000 = 500
        assertEquals(500.0, progress.yearProjected, 0.001)
    }

    @Test
    fun `混合多标的时预计各走各的分支再累加`() {
        // 600900 今年有宣告 → 走自然年分支；000001 今年没有 → 走 TTM 退回分支
        val assets = listOf(
            asset("长江电力", "600900", 1000.0),
            asset("某红利ETF", "000001", 2000.0)
        )
        val records = mapOf(
            "600900" to listOf(record(dateIn(4, 10), 3.0)),       // 今年宣告 → 3/10×1000 = 300
            "000001" to listOf(record("${year - 1}-11-20", 5.0))  // 今年无宣告 → TTM 5/10×2000 = 1000
        )

        val progress = DividendProgressCalculator.calculate(assets, records, midYear)

        assertEquals(1300.0, progress.yearProjected, 0.001)
    }

    @Test
    fun `12月除息次年到账的分红计入今年预计但不计入今年已收`() {
        // 12/20 除息、次年 1/10 到账：宣告发生在今年 → 计入今年预计；
        // 钱还没到账 → 不计入今年已收（进度不到 100%，符合预期）
        val assets = listOf(asset("红利LOF", "sh510880", 1000.0))
        val records = mapOf(
            "sh510880" to listOf(
                record("$year-12-20", 2.0).copy(paymentDate = "${year + 1}-01-10")
            )
        )

        val progress = DividendProgressCalculator.calculate(assets, records, midYear)

        // 每十股 2 元 × 1000 份 ÷ 10 = 200
        assertEquals(200.0, progress.yearProjected, 0.001)
        assertEquals(0.0, progress.yearReceived, 0.001)
        assertEquals(0, progress.receivedCount)
        assertEquals(0.0, progress.ratio, 0.001)
    }

    @Test
    fun `今年已宣告且全部到账时进度不会超额`() {
        // 今年宣告的两笔都已到账：已收 = 预计 = 500，分子不会超过分母
        // （改之前 TTM 横跨两年时这里会出现「已收 > 预计」）
        val assets = listOf(asset("长江电力", "600900", 1000.0))
        val records = mapOf(
            "600900" to listOf(
                record(dateIn(2, 10), 3.0).copy(paymentDate = dateIn(2, 20)),
                record(dateIn(5, 10), 2.0).copy(paymentDate = dateIn(5, 20))
            )
        )

        val progress = DividendProgressCalculator.calculate(assets, records, midYear)

        assertEquals(500.0, progress.yearProjected, 0.001)
        assertEquals(500.0, progress.yearReceived, 0.001)
        assertEquals(2, progress.receivedCount)
        assertEquals(1.0, progress.ratio, 0.001)
        assertEquals(0.0, progress.remaining, 0.001)
        assertTrue(progress.yearReceived <= progress.yearProjected + 0.001)
    }

    // ===== 多币种折算（calc-P1-1 回归）=====
    //
    // 外币持仓（如美股/港币基金）的分红金额必须按标的币种换成人民币后再汇总，
    // 否则会把美元金额当人民币数值直接相加，钱数被严重低估。
    // 这里用默认汇率表 USD=7.25：每十股派 10 美元 × 100 股 ÷ 10 = 100 美元 → 折 725 人民币。

    @Test
    fun `外币持仓的分红金额按汇率折算成人民币后再汇总`() {
        ExchangeRateManager.resetForTesting()
        try {
            val usdAsset = Asset(
                name = "Apple",
                type = AssetType.STOCK,
                symbol = "AAPL",
                account = "美股",
                balance = 100.0,
                currency = "USD"
            )
            val records = mapOf("AAPL" to listOf(record(dateIn(1, 15), 10.0)))
            // 用固定的 midYear（10/14）作「今天」：dateIn(1,15) 必在其前，断言不随运行日期漂移
            val progress = DividendProgressCalculator.calculate(listOf(usdAsset), records, midYear)

            // 今年已宣告 → 预计走自然年分支；1/15 已过 → 已收同数。
            // 100 美元 × 7.25 = 725 人民币，而不是把 100 当人民币（少算 625）
            assertEquals(725.0, progress.yearProjected, 0.001)
            assertEquals(725.0, progress.yearReceived, 0.001)
            assertEquals(1, progress.receivedCount)
        } finally {
            ExchangeRateManager.resetForTesting()
        }
    }

    @Test
    fun `多币种与人民币持仓混合时各自换汇后加总`() {
        ExchangeRateManager.resetForTesting()
        try {
            val cnyAsset = asset("长江电力", "600900", 1000.0) // 每十股 3 元 → 300 人民币
            val usdAsset = Asset(
                name = "Apple", type = AssetType.STOCK, symbol = "AAPL",
                account = "美股", balance = 100.0, currency = "USD"
            ) // 每十股 10 美元 → 100 美元 × 7.25 = 725 人民币
            val records = mapOf(
                "600900" to listOf(record(dateIn(4, 10), 3.0)),
                "AAPL" to listOf(record(dateIn(5, 10), 10.0))
            )
            val progress = DividendProgressCalculator.calculate(listOf(cnyAsset, usdAsset), records, midYear)

            // 300 + 725 = 1025 人民币，而非 300 + 100 = 400（后者把美元当人民币）
            assertEquals(1025.0, progress.yearProjected, 0.001)
            assertEquals(1025.0, progress.yearReceived, 0.001)
            assertEquals(2, progress.receivedCount)
        } finally {
            ExchangeRateManager.resetForTesting()
        }
    }
}
