package com.example.stockdiary.data.dividend

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * [shouldRefetchOtc] 与 [isFetchedToday] 的纯函数单测。
 *
 * 这两段判定是「手动刷新收敛」的核心：场外基金（天天基金）在 forceRefresh 时
 * 只补抓「TTL 过期且今天还没抓过」的，避免请求风暴踩 514。
 * 因为是纯函数，直接 JVM 单测，钉死四种组合 + 跨日/同日边界。
 */
class DividendPrefetchPolicyTest {

    // shouldRefetchOtc 四种组合（均假设 TTL 已过期，否则都不抓）：
    // 非手动刷新维持原 TTL 语义 → ttlExpired 即抓，与「今天是否已抓」无关；
    // 手动刷新(force)才引入「今天已抓就跳过」，避免把全部场外基金打一遍踩 514 请求风暴。
    @Test
    fun `OTC_非force_今日已抓_TTL过期_仍按TTL语义抓`() {
        assertTrue(shouldRefetchOtc(ttlExpired = true, forceRefresh = false, fetchedToday = true))
    }

    @Test
    fun `OTC_非force_今日未抓_TTL过期_按TTL语义抓`() {
        assertTrue(shouldRefetchOtc(ttlExpired = true, forceRefresh = false, fetchedToday = false))
    }

    @Test
    fun `OTC_force_今日已抓_TTL过期_不抓_避免请求风暴`() {
        assertFalse(shouldRefetchOtc(ttlExpired = true, forceRefresh = true, fetchedToday = true))
    }

    @Test
    fun `OTC_force_今日未抓_TTL过期_抓_补抓`() {
        assertTrue(shouldRefetchOtc(ttlExpired = true, forceRefresh = true, fetchedToday = false))
    }

    @Test
    fun `TTL未过期_无论force与否_都不抓`() {
        assertFalse(shouldRefetchOtc(ttlExpired = false, forceRefresh = true, fetchedToday = false))
        assertFalse(shouldRefetchOtc(ttlExpired = false, forceRefresh = false, fetchedToday = true))
    }

    private val zone = ZoneId.systemDefault()
    private fun dayStart(dayOffset: Long): Long =
        LocalDate.now().plusDays(dayOffset).atStartOfDay(zone).toInstant().toEpochMilli()

    @Test
    fun `isFetchedToday_同自然日_返回true`() {
        val today = dayStart(0)
        assertTrue(isFetchedToday(today, today + 3_600_000L))
    }

    @Test
    fun `isFetchedToday_跨日_返回false`() {
        val yesterday = dayStart(-1)
        val today = dayStart(0)
        assertFalse(isFetchedToday(yesterday, today))
    }

    @Test
    fun `isFetchedToday_从未抓过_null_返回false`() {
        assertFalse(isFetchedToday(null, dayStart(0)))
    }

    @Test
    fun `isFetchedToday_零时间戳_返回false`() {
        assertFalse(isFetchedToday(0L, dayStart(0)))
    }

    @Test
    fun `isFetchedToday_未来某日_与今天不同_返回false`() {
        val today = dayStart(0)
        val tomorrow = dayStart(1)
        assertFalse(isFetchedToday(tomorrow, today))
    }
}
