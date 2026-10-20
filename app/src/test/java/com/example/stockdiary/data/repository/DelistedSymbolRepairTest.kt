package com.example.stockdiary.data.repository

import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 老三板（退市股）symbol 形态归一的纯函数回归。
 *
 * 重点是 [computeDelistedSymbolRepairs]：把历史落库的不规范老三板 symbol
 * （`sh400030` / 裸 `400030`）统一归一成 `bj400xxx`，与
 * [com.example.stockdiary.utils.StockCodeManager.normalizeSymbol] 之后的规范形态对齐；
 * 同时不能碰场外基金份额（`fund_400030`）、普通股票（`sh600519`）或其它。
 */
class DelistedSymbolRepairTest {

    private fun asset(id: Long, symbol: String, type: AssetType = AssetType.STOCK) = Asset(
        id = id,
        name = symbol,
        type = type,
        symbol = symbol,
        account = "默认",
        balance = 1.0
    )

    @Test
    fun 带市场前缀的老三板改成bj形态() {
        // 早年被误加 sh 前缀的老三板 symbol，应归一成 bj400xxx
        val assets = listOf(asset(1L, "sh400030"))
        val plan = computeDelistedSymbolRepairs(assets)
        assertEquals(mapOf(1L to "bj400030"), plan.updates)
        assertTrue("无碰撞，skipped 应为空", plan.skipped.isEmpty())
    }

    @Test
    fun 无前缀的裸老三板改成bj形态() {
        // 没有市场前缀的裸 6 位退市代码，应归一成 bj400xxx
        val assets = listOf(asset(1L, "400030"))
        val plan = computeDelistedSymbolRepairs(assets)
        assertEquals(mapOf(1L to "bj400030"), plan.updates)
        assertTrue("无碰撞，skipped 应为空", plan.skipped.isEmpty())
    }

    @Test
    fun 已是规范bj形态的不动() {
        // 已经规范化的 bj400xxx，再跑一遍不应产生任何更新
        val assets = listOf(
            asset(1L, "bj400030"),
            asset(2L, "bj400264")
        )
        val plan = computeDelistedSymbolRepairs(assets)
        assertTrue("已是规范形态，不应有更新", plan.updates.isEmpty())
        assertTrue("无碰撞，skipped 应为空", plan.skipped.isEmpty())
    }

    @Test
    fun 场外基金份额不被误伤() {
        // fund_ 前缀是场外份额的显式标记，isDelistedForSync 已排除它，
        // 400030 虽在老三板号段，但带 fund_ 前缀应完全不动
        val assets = listOf(asset(1L, "fund_400030", type = AssetType.FUND))
        val plan = computeDelistedSymbolRepairs(assets)
        assertTrue("场外基金份额不应被当成老三板", plan.updates.isEmpty())
        assertTrue("无碰撞，skipped 应为空", plan.skipped.isEmpty())
    }

    @Test
    fun 普通股票不动() {
        // 普通股票（非退市）不应被卷入归一
        val assets = listOf(asset(1L, "sh600519"))
        val plan = computeDelistedSymbolRepairs(assets)
        assertTrue("普通股票不应被更新", plan.updates.isEmpty())
        assertTrue("无碰撞，skipped 应为空", plan.skipped.isEmpty())
    }

    @Test
    fun 碰撞时跳过不规范那条_不覆盖() {
        // 这正是隐患的产物：库里同一只退市股两条（bj400030 与 sh400030）。
        // 自动合并持仓与成本需要用户确认，启动时不该偷偷做 —— 跳过并留日志
        val assets = listOf(
            asset(1L, "bj400030"),  // 规范形态，占用目标
            asset(2L, "sh400030")   // 不规范形态，撞上规范形态 → 跳过
        )
        val plan = computeDelistedSymbolRepairs(assets)
        assertTrue("碰撞时不应覆盖、不应更新", plan.updates.isEmpty())
        assertEquals(listOf(2L), plan.skipped)
    }

    @Test
    fun 幂等_已修过的库再跑两遍都没有改动() {
        // 第一遍：把不规范的 sh400030 归一成 bj400030
        val firstPass = listOf(asset(1L, "sh400030"))
        val firstPlan = computeDelistedSymbolRepairs(firstPass)
        assertEquals(mapOf(1L to "bj400030"), firstPlan.updates)

        // 模拟第一遍已落地：库里现在全是 bj400xxx
        val repaired = listOf(asset(1L, "bj400030"))
        val secondPlan = computeDelistedSymbolRepairs(repaired)
        assertTrue("第二遍 updates 应为空（幂等）", secondPlan.updates.isEmpty())
        assertTrue("无碰撞，skipped 应为空", secondPlan.skipped.isEmpty())
    }

    @Test
    fun 混合形态_只归一不规范的退市股() {
        // 一份「鱼龙混杂」的历史数据：应有更新的只有不规范的退市股，
        // 规范退市股、场外基金、普通股票、加密都不动
        val assets = listOf(
            asset(1L, "sh400030"),     // → bj400030
            asset(2L, "400264"),       // → bj400264
            asset(3L, "bj420102"),     // 已是规范形态（420 段），不动，且不与他人碰撞
            asset(4L, "fund_400030"),  // 场外份额，不动
            asset(5L, "sh600519"),     // 普通股票，不动
            asset(6L, "BTCUSDT", type = AssetType.CRYPTO) // 加密，不动
        )
        val plan = computeDelistedSymbolRepairs(assets)
        assertEquals(
            mapOf(1L to "bj400030", 2L to "bj400264"),
            plan.updates
        )
        assertTrue("无碰撞，skipped 应为空", plan.skipped.isEmpty())
    }
}
