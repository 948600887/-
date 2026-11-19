package com.example.stockdiary.data.repository

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DividendFetchStateStore] 批量落盘回归。
 *
 * 核心命题：预取轮次内写入**立即对读取可见**（否则同一轮会重抓），但只有轮末走一次
 * `commit()`；分段检查点走异步 `apply()`——因为持批次的不只是后台预取，详情页的
 * 抓取成功回调在**主线程**上也会命中检查点，同步 commit 就是主线程 fsync。
 * 非批次内的零星写入仍走原有 `apply()`。另外钉死失败计数累加、clearFailure、空批次、
 * commit 失败的兜底行为，以及键名 / 前缀不变。
 *
 * 假件口径：`commit()` 在 `nextCommitResult = false` 时**不改**内存 values
 * （真实 SharedPreferences 的 commit 失败时内存与磁盘同时不变），否则「commit 失败」
 * 类用例会因为「读回仍然有值」而假绿。
 */
class DividendFetchStateStoreBatchTest {

    private val prefs = FakeSharedPreferences()
    private val context = mockk<Context>(relaxed = true)

    private val store: DividendFetchStateStore

    /** 可控时钟：用于推进 30 秒检查点窗口 */
    private var now = 1_700_000_000_000L

    init {
        // 必须先桩好 getSharedPreferences，再构造 store（构造函数会立即取 prefs）
        every { context.getSharedPreferences(any(), any()) } returns prefs
        store = DividendFetchStateStore(context) { now }
    }

    @Test
    fun `批次内写入立即可读且不落盘_轮末只commit一次`() {
        store.beginBatch()
        store.markFetched("sh600519")
        store.markFetched("sz000001")

        // 同一轮里必须马上看得到（isFetchedToday / 去重依赖它），但还不能落盘
        val fetchedAt = store.lastFetchedAt("sh600519")
        assertNotNull(fetchedAt)
        assertTrue(fetchedAt!! > 0L)
        assertNotNull(store.lastFetchedAt("sz000001"))
        assertEquals(0, prefs.commitCount)
        assertEquals(0, prefs.applyCount)

        store.endBatch()

        assertEquals("整轮只允许一次同步 commit", 1, prefs.commitCount)
        assertEquals("批次内不应走 apply", 0, prefs.applyCount)
        assertEquals(fetchedAt, prefs.values["sh600519"])
        assertEquals(fetchedAt, store.lastFetchedAt("sh600519"))
    }

    @Test
    fun `批次内失败次数立即累加_下一批基于已落盘值继续`() {
        store.beginBatch()
        store.markFailed("sh600519")
        assertEquals(1, store.failureCount("sh600519"))
        assertNotNull(store.lastFailedAt("sh600519"))
        store.markFailed("sh600519")
        assertEquals(2, store.failureCount("sh600519"))
        store.endBatch()

        assertEquals(1, prefs.commitCount)
        assertEquals(2, prefs.values["failedcnt_sh600519"])
        assertNotNull(prefs.values["failed_sh600519"])

        // 下一轮：基数从已落盘的 2 继续，而不是从 0 重新数
        store.beginBatch()
        store.markFailed("sh600519")
        assertEquals(3, store.failureCount("sh600519"))
        store.endBatch()
        assertEquals(3, prefs.values["failedcnt_sh600519"])
    }

    @Test
    fun `批次内clearFailure立即可见且轮末删除键`() {
        prefs.values["failed_sh600519"] = 1_700_000_000_000L
        prefs.values["failedcnt_sh600519"] = 2

        store.beginBatch()
        assertEquals(2, store.failureCount("sh600519"))
        store.clearFailure("sh600519")
        assertNull("清除后本轮不得再读到失败时间", store.lastFailedAt("sh600519"))
        assertEquals(0, store.failureCount("sh600519"))
        store.endBatch()

        assertEquals(1, prefs.commitCount)
        assertFalse(prefs.values.containsKey("failed_sh600519"))
        assertFalse(prefs.values.containsKey("failedcnt_sh600519"))
    }

    @Test
    fun `空批次不产生commit`() {
        store.beginBatch()
        store.endBatch()
        assertEquals(0, prefs.commitCount)
    }

    @Test
    fun `非批次写入仍走apply_行为不变`() {
        store.markFetched("sh600519")

        assertEquals(0, prefs.commitCount)
        assertEquals(1, prefs.applyCount)
        assertNotNull(store.lastFetchedAt("sh600519"))
    }

    @Test
    fun `aux时间戳走同一批次_新鲜度立即可见`() {
        store.beginBatch()
        store.markDataFetched("sh600519")
        store.markEmptyResult("sh600519")

        val freshness = store.freshness("sh600519")
        assertNotNull(freshness.lastDataAt)
        assertNotNull(freshness.lastMissedAt)
        assertTrue("批次内批量读也要看到待落盘值", store.allLongEntries().containsKey("dataAt_sh600519"))
        assertEquals(0, prefs.commitCount)

        store.endBatch()

        assertEquals(1, prefs.commitCount)
        assertNotNull(prefs.values["dataAt_sh600519"])
        assertNotNull(prefs.values["missAt_sh600519"])
    }

    @Test
    fun `commit写入失败不抛出且待落盘表被清空_磁盘值不变`() {
        prefs.nextCommitResult = false
        store.beginBatch()
        store.markFetched("sh600519")

        store.endBatch()

        assertEquals(1, prefs.commitCount)
        // 真实 SP 的 commit 失败时内存副本也不会前进，这条断言才能真正抓到
        // 「失败被当成功」的假绿（旧假件在 commit 失败时照样改了 values）
        assertFalse("commit 失败后不得留下该键", prefs.values.containsKey("sh600519"))
        assertNull("重启后读不到 = 必须靠下一轮重抓", store.lastFetchedAt("sh600519"))

        // 失败后不得跨轮残留：再结束一次空批次不应产生新的 commit
        store.beginBatch()
        store.endBatch()
        assertEquals(1, prefs.commitCount)
    }

    @Test
    fun `嵌套批次只在最外层结束落盘`() {
        store.beginBatch()
        store.beginBatch()
        store.markFetched("sh600519")
        store.endBatch()
        assertEquals("内层结束不落盘", 0, prefs.commitCount)
        store.endBatch()
        assertEquals(1, prefs.commitCount)
    }

    @Test
    fun `累计达到10条自动检查点走异步apply_重启后标记仍在`() {
        store.beginBatch()
        repeat(10) { store.markFetched("s$it") }

        assertEquals("满 10 条必须先落一次盘", 1, prefs.applyCount)
        assertEquals("检查点不得同步 commit（主线程会 fsync）", 0, prefs.commitCount)

        // 模拟进程重启：同一份磁盘（FakePrefs）上新建 store
        val restarted = DividendFetchStateStore(context) { now }
        repeat(10) { assertNotNull("检查点前的标记重启后必须仍在: s$it", restarted.lastFetchedAt("s$it")) }

        // 检查点之后写入的还在待落盘表，重启会丢——这正是设计允许的一个区间
        store.markFetched("s10")
        assertEquals(1, prefs.applyCount)
        assertNull(DividendFetchStateStore(context) { now }.lastFetchedAt("s10"))

        // 轮末收尾把它补上（同步 commit）
        store.endBatch()
        assertEquals(1, prefs.commitCount)
        assertNotNull(DividendFetchStateStore(context) { now }.lastFetchedAt("s10"))
    }

    @Test
    fun `未满10条且未到30秒不提前落盘`() {
        store.beginBatch()
        repeat(3) { store.markFetched("s$it") }
        assertEquals(0, prefs.commitCount)
        assertEquals(0, prefs.applyCount)

        store.endBatch()
        assertEquals(1, prefs.commitCount)
        assertEquals(0, prefs.applyCount)
    }

    @Test
    fun `距上次落盘超过30秒触发检查点走apply`() {
        store.beginBatch()
        store.markFetched("s0")
        assertEquals(0, prefs.commitCount)
        assertEquals(0, prefs.applyCount)

        now += 30_001L
        store.markFetched("s1")

        assertEquals("低频写入也要按时间窗落盘", 1, prefs.applyCount)
        assertEquals("时间窗检查点同样不得走同步 commit", 0, prefs.commitCount)
        assertNotNull(DividendFetchStateStore(context) { now }.lastFetchedAt("s0"))
        assertNotNull(DividendFetchStateStore(context) { now }.lastFetchedAt("s1"))
    }

    @Test
    fun `一轮多次检查点_每次最多一个区间且轮末补齐剩余`() {
        store.beginBatch()
        repeat(25) { store.markFetched("s$it") }

        assertEquals("25 条应触发 2 次检查点(异步 apply)", 2, prefs.applyCount)
        assertEquals("检查点期间不得有同步 commit", 0, prefs.commitCount)

        store.endBatch()
        assertEquals("轮末补一次同步 commit", 1, prefs.commitCount)
        val restarted = DividendFetchStateStore(context) { now }
        repeat(25) { assertNotNull(restarted.lastFetchedAt("s$it")) }
    }

    @Test
    fun `检查点走apply_commit失败结果不再影响检查点`() {
        prefs.nextCommitResult = false
        store.beginBatch()
        repeat(10) { store.markFetched("s$it") }

        assertEquals(1, prefs.applyCount)
        assertEquals("检查点已不依赖 commit 返回值，不该产生 commit", 0, prefs.commitCount)
        // apply 会同步改内存副本（真实 SP 亦然），所以同一份磁盘上重启能读到
        assertNotNull(DividendFetchStateStore(context) { now }.lastFetchedAt("s0"))

        // 检查点已把待落盘表清空，轮末没有剩余值，不再产生落盘
        store.endBatch()
        assertEquals(0, prefs.commitCount)
    }

    @Test
    fun `检查点触发时走apply_commitCount不增加`() {
        store.beginBatch()
        repeat(10) { store.markFetched("s$it") }

        assertEquals(1, prefs.applyCount)
        assertEquals("检查点必须在主线程外异步落盘，绝不能同步 commit", 0, prefs.commitCount)

        // 再来一条留在待落盘表里，验证轮末那一次仍然走 commit
        store.markFetched("s10")
        store.endBatch()
        assertEquals("轮末不再重复 apply", 1, prefs.applyCount)
        assertEquals("轮末补齐走同步 commit", 1, prefs.commitCount)
    }

    @Test
    fun `轮末endBatch仍走同步commit_不是apply`() {
        store.beginBatch()
        store.markFetched("sh600519")
        store.endBatch()

        assertEquals("轮末必须是同步 commit", 1, prefs.commitCount)
        assertEquals("轮末不得退化成 apply", 0, prefs.applyCount)
        assertEquals(store.lastFetchedAt("sh600519"), prefs.values["sh600519"])
    }

    /** 够用的内存版 SharedPreferences：记录 commit / apply 次数，可模拟 commit 失败 */
    private class FakeSharedPreferences : SharedPreferences {
        val values = mutableMapOf<String, Any?>()
        var commitCount = 0
        var applyCount = 0
        var nextCommitResult = true

        override fun getAll(): MutableMap<String, *> = values

        override fun getString(key: String, defValue: String?): String? =
            values[key] as? String ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            values[key] as? MutableSet<String> ?: defValues

        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue

        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue

        override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue

        override fun getBoolean(key: String, defValue: Boolean): Boolean =
            values[key] as? Boolean ?: defValue

        override fun contains(key: String): Boolean = values.containsKey(key)

        override fun edit(): SharedPreferences.Editor = FakeEditor()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

        private inner class FakeEditor : SharedPreferences.Editor {
            private val puts = mutableMapOf<String, Any?>()
            private val removes = mutableSetOf<String>()
            private var clearAll = false

            override fun putString(key: String, value: String?): SharedPreferences.Editor {
                puts[key] = value
                return this
            }

            override fun putStringSet(
                key: String,
                values: MutableSet<String>?
            ): SharedPreferences.Editor {
                puts[key] = values
                return this
            }

            override fun putInt(key: String, value: Int): SharedPreferences.Editor {
                puts[key] = value
                return this
            }

            override fun putLong(key: String, value: Long): SharedPreferences.Editor {
                puts[key] = value
                return this
            }

            override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
                puts[key] = value
                return this
            }

            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
                puts[key] = value
                return this
            }

            override fun remove(key: String): SharedPreferences.Editor {
                removes.add(key)
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                clearAll = true
                return this
            }

            /**
             * 真实 `SharedPreferences.Editor.commit()` 在两个层面都是「全有或全无」：
             * 内存副本与磁盘文件同时更新，任一处失败都整体不生效。所以这里
             * `nextCommitResult == false` 时**不得**调用 [applyChanges]——否则
             * 「commit 失败后值仍在」这种假绿会让「失败要留痕、不能当成功」的用例形同虚设。
             */
            override fun commit(): Boolean {
                commitCount++
                if (!nextCommitResult) return false
                applyChanges()
                return true
            }

            override fun apply() {
                applyCount++
                applyChanges()
            }

            private fun applyChanges() {
                if (clearAll) values.clear()
                removes.forEach { values.remove(it) }
                puts.forEach { (key, value) ->
                    if (value == null) values.remove(key) else values[key] = value
                }
            }
        }
    }
}
