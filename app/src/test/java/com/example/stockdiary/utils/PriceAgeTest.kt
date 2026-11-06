package com.example.stockdiary.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阶段 0「数据年龄」四档判定回归。
 *
 * [priceAgeLevel] 的 [nowMillis] 可注入，所以 0 / 1h 内 / >1h / >24h 四档与边界
 * 都能在固定时钟上钉死；[priceAgeStamp] 的文案复用既有 [Long.toRelativeTime]
 * （内部取系统时钟），故那一组用例用真实时钟构造 lastSyncTime，生产里两者同源、一致。
 */
class PriceAgeTest {

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val HOUR = 60L * 60 * 1000
        const val DAY = 24L * HOUR
    }

    // ---------- ④ 四档分级（固定注入时钟） ----------

    @Test
    fun `lastSyncTime为0_从未同步_NEVER_SYNCED`() {
        assertEquals(PriceAgeLevel.NEVER_SYNCED, priceAgeLevel(0L, NOW))
    }

    @Test
    fun `lastSyncTime为负_非法值也按从未同步处理`() {
        assertEquals(PriceAgeLevel.NEVER_SYNCED, priceAgeLevel(-1L, NOW))
    }

    @Test
    fun `1小时内_足够新鲜_FRESH`() {
        assertEquals(PriceAgeLevel.FRESH, priceAgeLevel(NOW - 30L * 60 * 1000, NOW))
        assertEquals(PriceAgeLevel.FRESH, priceAgeLevel(NOW - 1L, NOW))
    }

    @Test
    fun `恰好1小时_进入AGED边界`() {
        assertEquals(PriceAgeLevel.AGED, priceAgeLevel(NOW - HOUR, NOW))
    }

    @Test
    fun `超过1小时未到24小时_AGED`() {
        assertEquals(PriceAgeLevel.AGED, priceAgeLevel(NOW - 2 * HOUR, NOW))
        assertEquals(PriceAgeLevel.AGED, priceAgeLevel(NOW - DAY + 1L, NOW))
    }

    @Test
    fun `恰好24小时_进入STALE边界`() {
        assertEquals(PriceAgeLevel.STALE, priceAgeLevel(NOW - DAY, NOW))
    }

    @Test
    fun `超过24小时_STALE`() {
        assertEquals(PriceAgeLevel.STALE, priceAgeLevel(NOW - 3 * DAY, NOW))
    }

    @Test
    fun `时钟回拨_lastSyncTime在未来按FRESH处理_不显示负年龄`() {
        assertEquals(PriceAgeLevel.FRESH, priceAgeLevel(NOW + DAY, NOW))
        assertEquals("", priceAgeStamp(NOW + DAY, NOW))
    }

    // ---------- 统一后缀文案 ----------

    @Test
    fun `stamp_从未同步为空串_卡片保持价格待更新`() {
        assertEquals("", priceAgeStamp(0L, NOW))
    }

    @Test
    fun `stamp_1小时内为空串_不占卡片篇幅`() {
        assertEquals("", priceAgeStamp(NOW - 30L * 60 * 1000, NOW))
    }

    @Test
    fun `stamp_超过1小时显示小时前更新`() {
        // toRelativeTime() 内部读系统时钟，故用真实时钟构造，生产两处同源
        val now = System.currentTimeMillis()
        val twoHoursAgo = now - 2 * HOUR
        assertEquals(PriceAgeLevel.AGED, priceAgeLevel(twoHoursAgo, now))
        val stamp = priceAgeStamp(twoHoursAgo)
        assertTrue(stamp.startsWith(" · "))
        assertTrue(stamp.contains("小时前更新"))
    }

    @Test
    fun `stamp_超过24小时仍用同一格式并触发警示`() {
        val now = System.currentTimeMillis()
        val threeDaysAgo = now - 3 * DAY
        assertEquals(PriceAgeLevel.STALE, priceAgeLevel(threeDaysAgo, now))
        val stamp = priceAgeStamp(threeDaysAgo)
        assertTrue(stamp.startsWith(" · "))
        assertTrue(stamp.contains("天前更新"))
        assertTrue(isPriceStale(threeDaysAgo, now))
    }

    @Test
    fun `isPriceStale_仅STALE为真`() {
        assertFalse(isPriceStale(0L, NOW))
        assertFalse(isPriceStale(NOW - 30L * 60 * 1000, NOW))
        assertFalse(isPriceStale(NOW - 2 * HOUR, NOW))
        assertTrue(isPriceStale(NOW - DAY, NOW))
    }

    @Test
    fun `阈值常量_1小时可见_24小时警示`() {
        assertEquals(HOUR, PRICE_AGE_VISIBLE_THRESHOLD_MS)
        assertEquals(DAY, PRICE_AGE_STALE_THRESHOLD_MS)
        assertTrue(PRICE_AGE_STALE_THRESHOLD_MS > PRICE_AGE_VISIBLE_THRESHOLD_MS)
    }
}
