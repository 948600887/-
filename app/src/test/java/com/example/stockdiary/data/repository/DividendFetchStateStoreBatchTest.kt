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
 * 核心命题：预取轮次内写入**立即对读取可见**（否则同一轮会重抓），落盘口径按**调用线程**分流：
 *
 * - 后台线程的检查点（预取 Worker / IO）：同步 `commit()`，保住 a9d804b 的
 *   「进程在轮末被 SIGKILL 也最多丢一个检查点区间」——`apply()` 依赖 `QueuedWork`，
 *   而 Worker 进程被杀时没有 Activity/Service 生命周期点兜底；
 * - 主线程的检查点（详情页 `Main.immediate` 里抓单只后 [DividendFetchStateStore.markFetched]）：
 *   退化为 `apply()`，绝不在 UI 线程 fsync；
 * - 轮末 [DividendFetchStateStore.endBatch]：**无条件**同步 `commit()`（常量 true，
 *   不经过线程判定），它已经在 `NonCancellable + Dispatchers.IO` 上。
 *
 * 假件口径（两条都很关键，缺一条这套测试就变假绿）：
 * 1. `commit()` 在 `nextCommitResult = false` 时**不改**内存 values——真实 SharedPreferences
 *    的 commit 失败时内存与磁盘同时不前进；
 * 2. `apply()` **只入队**，不改 values，必须由测试显式 [FakeSharedPreferences.flushQueued]
 *    才落地——否则 commit 与 apply 两条路径不可分辨，「检查点走哪条」根本断言不出来。
 *
 * 另外钉死失败计数累加、clearFailure、空批次、commit 失败的兜底行为，以及键名 / 前缀不变。
 */
class DividendFetchStateStoreBatchTest {

    private val prefs = FakeSharedPreferences()
    private val context = mockk<Context>(relaxed = true)

    /** 默认 store：isMainThread 用生产默认实现（JVM 下 Looper 为 null → 视为非主线程） */
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
        assertEquals("轮末走 commit，不走 apply", 0, prefs.applyCount)
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
        assertEquals(0, prefs.applyCount)
    }

    @Test
    fun `非批次写入仍走apply_行为不变`() {
        store.markFetched("sh600519")

        assertEquals(0, prefs.commitCount)
        assertEquals(1, prefs.applyCount)
        // apply 是异步落盘：此刻内存副本还没有该值，flushQueued 之后才落地
        assertNull("apply 不得同步改内存（否则两条落盘路径不可分辨）", prefs.values["sh600519"])
        prefs.flushQueued()
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
        assertEquals(0, prefs.applyCount)
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
    fun `累计达到10条自动检查点_重启后已落盘标记仍在`() {
        store.beginBatch()
        repeat(10) { store.markFetched("s$it") }

        assertEquals("满 10 条必须先落一次盘", 1, prefs.commitCount)
        assertEquals("后台线程的检查点仍走同步 commit", 0, prefs.applyCount)

        // 模拟进程重启：同一份磁盘（FakePrefs）上新建 store
        val restarted = DividendFetchStateStore(context) { now }
        repeat(10) { assertNotNull("检查点前的标记重启后必须仍在: s$it", restarted.lastFetchedAt("s$it")) }

        // 检查点之后写入的还在待落盘表，重启会丢——这正是设计允许的一个区间
        store.markFetched("s10")
        assertEquals(1, prefs.commitCount)
        assertNull(DividendFetchStateStore(context) { now }.lastFetchedAt("s10"))

        // 轮末收尾把它补上
        store.endBatch()
        assertEquals(2, prefs.commitCount)
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
    }

    @Test
    fun `距上次落盘超过30秒触发检查点`() {
        store.beginBatch()
        store.markFetched("s0")
        assertEquals(0, prefs.commitCount)

        now += 30_001L
        store.markFetched("s1")

        assertEquals("低频写入也要按时间窗落盘", 1, prefs.commitCount)
        assertNotNull(DividendFetchStateStore(context) { now }.lastFetchedAt("s0"))
        assertNotNull(DividendFetchStateStore(context) { now }.lastFetchedAt("s1"))
    }

    @Test
    fun `一轮多次检查点_每次最多一个区间且轮末补齐剩余`() {
        store.beginBatch()
        repeat(25) { store.markFetched("s$it") }

        assertEquals("25 条应触发 2 次检查点", 2, prefs.commitCount)

        store.endBatch()
        assertEquals("轮末补第 3 次", 3, prefs.commitCount)
        val restarted = DividendFetchStateStore(context) { now }
        repeat(25) { assertNotNull(restarted.lastFetchedAt("s$it")) }
    }

    @Test
    fun `检查点commit失败不抛出且待落盘表清空`() {
        prefs.nextCommitResult = false
        store.beginBatch()
        repeat(10) { store.markFetched("s$it") }

        assertEquals(1, prefs.commitCount)
        assertFalse("commit 失败后不得留下键", prefs.values.containsKey("s0"))

        // 失败后不跨轮累积：轮末没有新的待落盘值，不再产生 commit
        store.endBatch()
        assertEquals(1, prefs.commitCount)
    }

    // ---------------------------------------------------------------- //
    // 检查点按调用线程分流（P1-2）：主线程 apply / 后台线程 commit
    // ---------------------------------------------------------------- //

    @Test
    fun `主线程触发检查点走apply_不产生同步commit`() {
        val mainStore = DividendFetchStateStore(context, isMainThread = { true }) { now }
        mainStore.beginBatch()
        repeat(10) { mainStore.markFetched("s$it") }

        assertEquals("主线程的检查点必须异步落盘", 1, prefs.applyCount)
        assertEquals("主线程不得同步 commit（UI 线程 fsync 会掉帧）", 0, prefs.commitCount)
        assertTrue("apply 尚未落地（真实 apply 也是异步写盘）", prefs.values.isEmpty())

        prefs.flushQueued()
        repeat(10) { assertNotNull("系统 flush 后值必须正确落地: s$it", mainStore.lastFetchedAt("s$it")) }

        // 轮末仍然是常量 true 的同步 commit
        mainStore.markFetched("s10")
        mainStore.endBatch()
        assertEquals(1, prefs.commitCount)
        assertFalse("任何落盘都不得发生在名为 main 的线程上", prefs.commitThreads.any { it == "main" })
        assertNotNull(mainStore.lastFetchedAt("s10"))
    }

    @Test
    fun `后台线程触发检查点仍走同步commit`() {
        val bgStore = DividendFetchStateStore(context, isMainThread = { false }) { now }
        bgStore.beginBatch()
        repeat(10) { bgStore.markFetched("s$it") }

        assertEquals("后台线程的检查点保持同步 commit，持久性不打折", 1, prefs.commitCount)
        assertEquals("后台线程的检查点不得退化成 apply", 0, prefs.applyCount)
        // 同步 commit 后立即可读（不依赖任何 flush）
        assertNotNull(bgStore.lastFetchedAt("s0"))
    }

    /** 够用的内存版 SharedPreferences：记录 commit / apply 次数与 commit 线程，可模拟 commit 失败 */
    private class FakeSharedPreferences : SharedPreferences {
        val values = mutableMapOf<String, Any?>()
        var commitCount = 0
        var applyCount = 0
        var nextCommitResult = true

        /** 每次 commit 的线程名：用于证明「主线程上没有同步落盘」 */
        val commitThreads = mutableListOf<String>()

        /** apply 的待落盘队列：真实 apply 异步写盘，这里必须由测试显式 flush 才落地 */
        private val queued = mutableListOf<FakeEditor>()

        /** 模拟系统把 apply 队列刷到磁盘（`QueuedWork.waitToFinish` 的等价物） */
        fun flushQueued() {
            val snapshot = queued.toList()
            queued.clear()
            snapshot.forEach { it.applyChanges() }
        }

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
                commitThreads += Thread.currentThread().name
                if (!nextCommitResult) return false
                applyChanges()
                return true
            }

            /**
             * 真实 `apply()` 只同步改内存副本、把写盘丢给后台线程；但**对这套测试而言**
             * 更关键的是「commit 与 apply 必须可分辨」，所以这里连内存都不立刻改，
             * 一律进 [queued]，由 [flushQueued] 显式落地。
             */
            override fun apply() {
                applyCount++
                queued += this
            }

            fun applyChanges() {
                if (clearAll) values.clear()
                removes.forEach { values.remove(it) }
                puts.forEach { (key, value) ->
                    if (value == null) values.remove(key) else values[key] = value
                }
            }
        }
    }
}
