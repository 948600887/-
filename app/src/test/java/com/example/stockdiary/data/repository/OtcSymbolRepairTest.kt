package com.example.stockdiary.data.repository

import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 场外基金 symbol 形态归一的纯函数回归。
 *
 * 重点是 [computeOtcSymbolRepairs]：把历史落库的纯数字形态（`004502`）改写成
 * `fund_` 形态（`fund_004502`），与 [com.example.stockdiary.utils.StockCodeManager.normalizeSymbol]
 * 之后的规范形态对齐；同时不能碰 LOF 场内外份额与老三板。
 */
class OtcSymbolRepairTest {

    private fun asset(id: Long, symbol: String, type: AssetType = AssetType.FUND) = Asset(
        id = id,
        name = symbol,
        type = type,
        symbol = symbol,
        account = "默认",
        balance = 1.0
    )

    @Test
    fun 纯数字场外基金改成fund形态_其它形态不动() {
        val assets = listOf(
            asset(1L, "004502"),       // → fund_004502（场外号段 004xxx-009xxx）
            asset(2L, "010000"),       // → fund_010000（01 开头开放式基金）
            asset(3L, "fund_004503"),  // 已是规范形态，不动
            asset(4L, "sz163415"),     // LOF 场内份额，不动
            asset(5L, "bj400264"),     // 老三板，号段不属场外，不动
            asset(6L, "600519")        // 股票（库里已规范化成 sh600519 形态），不动
        )
        val plan = computeOtcSymbolRepairs(assets)
        assertEquals(mapOf(1L to "fund_004502", 2L to "fund_010000"), plan.updates)
        assertTrue("无碰撞，skipped 应为空", plan.skipped.isEmpty())
    }

    @Test
    fun 规范形态已存在时跳过该条_不覆盖() {
        // 这正是 bug 的产物：库里同一只基金两条（004502 与 fund_004502）。
        // 自动合并持仓与成本需要用户确认，启动时不该偷偷做 —— 跳过并留日志
        val assets = listOf(
            asset(1L, "004502"),
            asset(2L, "fund_004502")
        )
        val plan = computeOtcSymbolRepairs(assets)
        assertTrue("不该自动合并", plan.updates.isEmpty())
        assertEquals(listOf(1L), plan.skipped)
    }

    @Test
    fun 幂等_已修过的库再跑一遍没有改动() {
        val repaired = listOf(
            asset(1L, "fund_004502"),
            asset(2L, "fund_010000")
        )
        val plan = computeOtcSymbolRepairs(repaired)
        assertTrue(plan.updates.isEmpty())
        assertTrue(plan.skipped.isEmpty())
    }

    @Test
    fun 带市场前缀的场外号段也要归一() {
        // 早年被 classifyCode 误判成股票的场外基金可能存成带市场前缀的形态
        val assets = listOf(asset(1L, "sz007467", type = AssetType.STOCK))
        val plan = computeOtcSymbolRepairs(assets)
        assertEquals(mapOf(1L to "fund_007467"), plan.updates)
    }
}
