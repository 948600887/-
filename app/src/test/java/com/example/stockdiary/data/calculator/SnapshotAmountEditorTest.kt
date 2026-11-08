package com.example.stockdiary.data.calculator

import com.example.stockdiary.database.AssetSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotAmountEditorTest {

    private val snapshot = AssetSnapshot(
        id = 7,
        timestamp = 123L,
        date = "2026-11-01",
        totalAssets = 1000.0,
        totalLiabilities = 100.0,
        netAssets = 900.0,
        totalCost = 800.0,
        totalProfit = 200.0,
        yieldRate = 25.0,
        stockValue = 500.0,
        fundValue = 200.0,
        cryptoValue = 200.0,
        bankDepositValue = 100.0,
        stockCost = 400.0,
        fundCost = 150.0,
        cryptoCost = 180.0,
        bankDepositCost = 70.0
    )

    @Test
    fun `重算总资产与净资产并保留日期时间与历史成本列`() {
        val input = SnapshotAmountInput(
            stock = 600.0,
            fund = 300.0,
            crypto = 100.0,
            deposit = 200.0,
            liabilities = 50.0
        )

        val updated = SnapshotAmountEditor.apply(snapshot, input)

        assertEquals(1200.0, updated.totalAssets, 1e-9)
        assertEquals(1150.0, updated.netAssets, 1e-9)
        assertEquals("快照日期不可改", "2026-11-01", updated.date)
        assertEquals("创建时刻不刷新", 123L, updated.timestamp)
        assertEquals("历史成本列不重算", 800.0, updated.totalCost, 1e-9)
        assertEquals(400.0, updated.stockCost, 1e-9)
        assertEquals(200.0, updated.totalProfit, 1e-9)
    }

    @Test
    fun `原值未改动时判定为零改动`() {
        val input = SnapshotAmountEditor.inputOf(snapshot)

        assertFalse(SnapshotAmountEditor.hasChanges(snapshot, input))
        assertEquals(snapshot, SnapshotAmountEditor.apply(snapshot, input))
    }

    @Test
    fun `修改任意一项即视为有改动`() {
        val input = SnapshotAmountEditor.inputOf(snapshot).copy(liabilities = 100.01)

        assertTrue(SnapshotAmountEditor.hasChanges(snapshot, input))
    }

    @Test
    fun `确认列表固定七行且带新旧值`() {
        val input = SnapshotAmountInput(
            stock = 500.0,
            fund = 200.0,
            crypto = 200.0,
            deposit = 100.0,
            liabilities = 50.0
        )

        val changes = SnapshotAmountEditor.changes(snapshot, input)

        assertEquals(
            listOf("股票", "基金", "加密货币", "存款", "负债", "总资产", "净资产"),
            changes.map { it.label }
        )
        assertEquals(100.0, changes.first { it.label == "负债" }.oldValue, 1e-9)
        assertEquals(50.0, changes.first { it.label == "负债" }.newValue, 1e-9)
        assertEquals(900.0, changes.first { it.label == "净资产" }.oldValue, 1e-9)
        assertEquals(950.0, changes.first { it.label == "净资产" }.newValue, 1e-9)
    }

    @Test
    fun `输入框初值原样保留精度`() {
        assertEquals("1234.5678", SnapshotAmountEditor.amountText(1234.5678))
        assertEquals("0", SnapshotAmountEditor.amountText(0.0))
        assertEquals(1234.5678, SnapshotAmountEditor.parseAmount("1234.5678")!!, 0.0)
    }

    @Test
    fun `空串按零处理负数与非法输入返回空`() {
        assertEquals(0.0, SnapshotAmountEditor.parseAmount("")!!, 0.0)
        assertEquals(0.0, SnapshotAmountEditor.parseAmount("   ")!!, 0.0)
        assertNull(SnapshotAmountEditor.parseAmount("-1"))
        assertNull(SnapshotAmountEditor.parseAmount("abc"))
        assertNull(SnapshotAmountEditor.parseAmount("NaN"))
        assertNull(SnapshotAmountEditor.parseAmount("Infinity"))
    }

    @Test
    fun `五项资产全为零时校验不通过`() {
        assertFalse(SnapshotAmountEditor.isValid(SnapshotAmountInput()))
        assertTrue(SnapshotAmountEditor.isValid(SnapshotAmountInput(deposit = 0.01)))
    }
}
