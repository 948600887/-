package com.example.stockdiary.data.repository

import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LD 锁仓脏符号修复的纯函数回归。
 *
 * 重点是 [computeLdRepairs]：把 `LDBCHUSDT` 这类盲加 USDT 的脏符号映射回底层交易对
 * `BCHUSDT`，同时正确处理 LDO 例外（真实现货，不变）与碰撞保护。
 */
class LdSymbolRepairTest {

    private fun asset(id: Long, symbol: String) = Asset(
        id = id,
        name = symbol,
        type = AssetType.CRYPTO,
        symbol = symbol,
        account = "币安",
        balance = 1.0
    )

    @Test
    fun LD脏符号映射到底层交易对_LDO与普通不变() {
        val assets = listOf(
            asset(1L, "LDBCHUSDT"), // → BCHUSDT
            asset(2L, "LDOUSDT"),   // 例外(Lido DAO 真实现货)，不变
            asset(3L, "BTCUSDT")    // 普通交易对，不变
        )
        val plan = computeLdRepairs(assets)
        // 只有 LDBCHUSDT 需要改成 BCHUSDT
        assertEquals(mapOf(1L to "BCHUSDT"), plan.updates)
        assertTrue("无碰撞，skipped 应为空", plan.skipped.isEmpty())
    }

    @Test
    fun 目标底层已存在时跳过该条_不覆盖() {
        val assets = listOf(
            asset(1L, "LDBCHUSDT"), // 想改到 BCHUSDT
            asset(2L, "BCHUSDT")    // 已存在 BCHUSDT → 碰撞，跳过 id=1
        )
        val plan = computeLdRepairs(assets)
        assertTrue("碰撞条目不应出现在 updates 里（不覆盖）", plan.updates.isEmpty())
        assertEquals(listOf(1L), plan.skipped)
    }

    @Test
    fun 剥不出底层的LD壳与例外都不动() {
        val assets = listOf(
            asset(10L, "LDOUSDT"),  // 例外：真实现货，不变
            asset(11L, "LDUSDT"),   // 稳定币壳，earnUnderlyingPair 返回 null，不动
            asset(12L, "ETHUSDT")   // 普通，不动
        )
        val plan = computeLdRepairs(assets)
        assertTrue(plan.updates.isEmpty())
        assertTrue(plan.skipped.isEmpty())
    }

    @Test
    fun 无LD资产时无任何改动() {
        val assets = listOf(
            asset(1L, "BTCUSDT"),
            asset(2L, "ETHUSDT"),
            asset(3L, "USDT")
        )
        val plan = computeLdRepairs(assets)
        assertTrue(plan.updates.isEmpty())
        assertTrue(plan.skipped.isEmpty())
    }
}
