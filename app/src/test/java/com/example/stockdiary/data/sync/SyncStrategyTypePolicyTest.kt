package com.example.stockdiary.data.sync

import com.example.stockdiary.database.AssetType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 后台同步「按类型分频」的策略纯函数测试：[SyncStrategy.shouldAutoSyncType]。
 *
 * 钉住用户确认的口径：
 * - 股票 / 场内基金：默认仅盘后 / 非交易时段刷，盘中要刷需显式打开 stockIntradaySync；
 * - 虚拟货币：默认不自动刷（24/7 与 A 股时钟错拍），要自动刷需显式打开 cryptoAutoSync；
 * - 其它类型（银行存款等）：后台从不自动同步。
 *
 * 手动刷新不走这里（用户显式要求，照旧全类型刷），故本函数只描述后台周期行为。
 */
class SyncStrategyTypePolicyTest {

    @Test
    fun `股票_盘中且未开盘中同步_不刷`() {
        assertFalse(
            SyncStrategy.shouldAutoSyncType(
                AssetType.STOCK, inTradingHours = true, stockIntradaySync = false, cryptoAutoSync = false
            )
        )
    }

    @Test
    fun `股票_盘中且开了盘中同步_刷`() {
        assertTrue(
            SyncStrategy.shouldAutoSyncType(
                AssetType.STOCK, inTradingHours = true, stockIntradaySync = true, cryptoAutoSync = false
            )
        )
    }

    @Test
    fun `股票_盘后即使没开盘中同步也刷_以捕获收盘价`() {
        assertTrue(
            SyncStrategy.shouldAutoSyncType(
                AssetType.STOCK, inTradingHours = false, stockIntradaySync = false, cryptoAutoSync = false
            )
        )
    }

    @Test
    fun `场内基金_与股票同口径_盘后刷盘中不刷`() {
        assertFalse(
            SyncStrategy.shouldAutoSyncType(
                AssetType.FUND, inTradingHours = true, stockIntradaySync = false, cryptoAutoSync = false
            )
        )
        assertTrue(
            SyncStrategy.shouldAutoSyncType(
                AssetType.FUND, inTradingHours = false, stockIntradaySync = false, cryptoAutoSync = false
            )
        )
    }

    @Test
    fun `虚拟货币_默认不自动刷_与是否交易时段无关`() {
        assertFalse(
            SyncStrategy.shouldAutoSyncType(
                AssetType.CRYPTO, inTradingHours = false, stockIntradaySync = true, cryptoAutoSync = false
            )
        )
        assertFalse(
            SyncStrategy.shouldAutoSyncType(
                AssetType.CRYPTO, inTradingHours = true, stockIntradaySync = true, cryptoAutoSync = false
            )
        )
    }

    @Test
    fun `虚拟货币_开了自动同步才刷_且不受交易时段限制`() {
        assertTrue(
            SyncStrategy.shouldAutoSyncType(
                AssetType.CRYPTO, inTradingHours = true, stockIntradaySync = false, cryptoAutoSync = true
            )
        )
        assertTrue(
            SyncStrategy.shouldAutoSyncType(
                AssetType.CRYPTO, inTradingHours = false, stockIntradaySync = false, cryptoAutoSync = true
            )
        )
    }

    @Test
    fun `银行存款等其它类型_后台从不自动同步`() {
        assertFalse(
            SyncStrategy.shouldAutoSyncType(
                AssetType.BANK_DEPOSIT, inTradingHours = false, stockIntradaySync = true, cryptoAutoSync = true
            )
        )
    }
}
