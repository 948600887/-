package com.example.stockdiary.data.price

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

    /**
     * 并发回归：trim 拍完快照后、按快照条目删除前，同一 key 被写入新值——
     * 旧实现按 key 删除，会把这条**新价**一起删掉（用户可见：刚刷新的价格消失）。
     *
     * 冻结时序的做法：[BlockingKey.hashCode] 只在 trim 线程上阻塞一次，位置正好是
     * `map.remove(...)` 计算哈希、尚未进入桶锁之前。主线程等它进入删除阶段后再写新值，
     * 然后放行 trim。
     */
    @Test
    fun `trim 不误删快照之后并发写入的新值`() {
        val enteredRemoval = CountDownLatch(1)
        val releaseRemoval = CountDownLatch(1)
        val oldest = BlockingKey("oldest", enteredRemoval, releaseRemoval)

        val cache = BoundedTtlCache<BlockingKey, String>(maxEntries = 2)
        cache.put(oldest, "stale", timestamp = 1L)
        cache.put(BlockingKey("keep1"), "b", timestamp = 2L)
        cache.put(BlockingKey("keep2"), "c", timestamp = 3L)

        val trimmer = Thread({ cache.trim(expireBeforeMillis = Long.MIN_VALUE) }, "trim-thread")
        trimmer.start()
        assertTrue(
            "trim 应已进入删除阶段（快照已固定、正在删除最旧条目）",
            enteredRemoval.await(3, TimeUnit.SECONDS)
        )

        // 此刻 trim 已拍完快照，最旧条目是 (oldest, stale, ts=1)；
        // 同一 key 写入更新价，若按 key 删除就会把它误删。
        cache.put(oldest, "fresh", timestamp = 4L)

        releaseRemoval.countDown()
        trimmer.join(3000)

        assertEquals("并发写入的新值不能被当成旧条目删除", "fresh", cache.getValue(oldest))
        assertEquals(4L, cache.get(oldest)?.timestamp)

        // 保守选择：宁可本轮少删一条（容量短暂超限），也不能删掉刚写入的新价；
        // 下一轮 trim 会按新的时间戳重新收回到上限。
        cache.trim(expireBeforeMillis = Long.MIN_VALUE)
        assertEquals("后续 trim 仍应把容量收回到上限", 2, cache.size())
    }

    /**
     * trim 线程上在删除阶段会调用一次 [hashCode]；用它把 trim 暂停在「快照之后、删除之前」。
     * 其他线程/其他 key 不受影响，避免把正常读写也卡住。
     */
    private class BlockingKey(
        private val name: String,
        private val enteredRemoval: CountDownLatch? = null,
        private val releaseRemoval: CountDownLatch? = null
    ) {
        override fun hashCode(): Int {
            if (enteredRemoval != null && Thread.currentThread().name == "trim-thread") {
                enteredRemoval.countDown()
                releaseRemoval?.await(3, TimeUnit.SECONDS)
            }
            return name.hashCode()
        }

        override fun equals(other: Any?): Boolean = other is BlockingKey && other.name == name
    }
}
