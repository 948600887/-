package com.example.stockdiary.data.price

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 五套取价缓存合并为一份实现后的回归：钉住「存储机制」等价，
 * 并明确 TTL 仍由各链路自己传入（这里用 60 秒 / 5 分钟 / 不按过期清理三档做代表）。
 */
class BoundedTtlCacheTest {

    private val sixtySeconds = 60_000L
    private val fiveMinutes = 5 * 60_000L

    @Test
    fun `写入后可读取值和时间戳`() {
        val cache = BoundedTtlCache<String, String>(maxEntries = 200)
        cache.put("sh600519", "1688", timestamp = 1000L)

        assertEquals("1688", cache.getValue("sh600519"))
        assertEquals(1000L, cache.get("sh600519")?.timestamp)
        assertNull(cache.getValue("missing"))
    }

    @Test
    fun `加密档 60 秒_超时条目在超上限时优先清掉`() {
        val now = 10_000L
        val cache = BoundedTtlCache<String, String>(maxEntries = 3)
        cache.put("old", "crypto", timestamp = now - sixtySeconds - 1)
        cache.put("fresh1", "a", timestamp = now)
        cache.put("fresh2", "b", timestamp = now)
        cache.put("fresh3", "c", timestamp = now)

        cache.trim(expireBeforeMillis = now - sixtySeconds)

        // 先清过期，再谈容量：fresh 三只必须全在
        assertEquals(setOf("fresh1", "fresh2", "fresh3"), cache.snapshot().keys)
        assertEquals(3, cache.size())
    }

    @Test
    fun `基金档 5 分钟_未超上限时过期条目不被主动清理`() {
        val now = 10_000L
        val cache = BoundedTtlCache<String, String>(maxEntries = 200)
        cache.put("fund", "1.23", timestamp = now - fiveMinutes - 1)

        cache.trim(expireBeforeMillis = now - fiveMinutes)

        // 原逻辑只在 size > 上限时才做批量淘汰；容量没到就保留（读取方自己判 TTL）
        assertEquals("1.23", cache.getValue("fund"))
    }

    @Test
    fun `不按过期清理档_超上限只淘汰最旧`() {
        val cache = BoundedTtlCache<String, String>(maxEntries = 3)
        cache.put("oldest", "a", timestamp = 1L)
        cache.put("middle", "b", timestamp = 2L)
        cache.put("newer", "c", timestamp = 3L)
        cache.put("newest", "d", timestamp = 4L)

        // Long.MIN_VALUE = 不按 TTL 预清（新浪内存 / StockDataManager 原行为）
        cache.trim(expireBeforeMillis = Long.MIN_VALUE)

        assertEquals(setOf("middle", "newer", "newest"), cache.snapshot().keys)
        assertEquals(3, cache.size())
    }

    @Test
    fun `putIfAbsent 不覆盖已有条目`() {
        val cache = BoundedTtlCache<String, String>(maxEntries = 200)
        cache.put("code", "old", timestamp = 1L)

        val inserted = cache.putIfAbsent("code", "new", timestamp = 2L)

        assertFalse(inserted)
        assertEquals("old", cache.getValue("code"))
        assertEquals(1L, cache.get("code")?.timestamp)
    }

    @Test
    fun `putAll 使用同一时间戳`() {
        val cache = BoundedTtlCache<String, String>(maxEntries = 200)
        cache.putAll(mapOf("a" to "1", "b" to "2"), timestamp = 500L)

        assertEquals(setOf("a", "b"), cache.snapshot().keys)
        assertTrue(cache.snapshot().values.all { it.timestamp == 500L })
    }

    @Test
    fun `快照是拷贝_清空后原快照不受影响`() {
        val cache = BoundedTtlCache<String, String>(maxEntries = 200)
        cache.put("a", "1")
        val snapshot = cache.snapshot()

        cache.clear()

        assertEquals(1, snapshot.size)
        assertTrue(cache.isEmpty())
    }

    @Test
    fun `未超上限时 trim 不做任何删除`() {
        val cache = BoundedTtlCache<String, String>(maxEntries = 200)
        cache.put("old", "1", timestamp = 0L)

        cache.trim(expireBeforeMillis = 10_000L)

        assertEquals(1, cache.size())
    }
}
