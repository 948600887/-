package com.example.stockdiary.data

import com.example.stockdiary.data.dividend.DividendProgressCalculator
import com.example.stockdiary.data.dividend.DividendRecord
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
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
    fun `全年预计按TTM每十股分红乘持仓`() {
        val assets = listOf(asset("长江电力", "600900", 2000.0))
        val records = mapOf(
            "600900" to listOf(record(dateIn(3, 10), 4.0))
        )
        val progress = DividendProgressCalculator.calculate(assets, records, today)

        // TTM = 4 元/十股 → 4 / 10 × 2000 = 800
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
        // 新鲜度闸门用同一把尺子，所以边界那天也还能推算下一笔
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
}
