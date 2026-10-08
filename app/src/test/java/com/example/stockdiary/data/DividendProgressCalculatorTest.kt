package com.example.stockdiary.data

import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
