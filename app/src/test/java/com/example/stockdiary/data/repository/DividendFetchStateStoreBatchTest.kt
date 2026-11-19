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
 * 核心命题：预取轮次内写入**立即对读取可见**（否则同一轮会重抓），但整轮只 `commit()` 一次；
 * 非批次内的零星写入仍走原有 `apply()`。另外钉死失败计数累加、clearFailure、空批次、
 * commit 失败的兜底行为，以及键名 / 前缀不变。
 */
class DividendFetchStateStoreBatchTest {

    private val prefs = FakeSharedPreferences()
    private val context = mockk<Context>(relaxed = true)

    private val store: DividendFetchStateStore

    init {
        // 必须先桩好 getSharedPreferences，再构造 store（构造函数会立即取 prefs）
        every { context.getSharedPreferences(any(), any()) } returns prefs
        store = DividendFetchStateStore(context)
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
    fun `commit失败不抛出且待落盘表被清空`() {
        prefs.nextCommitResult = false
        store.beginBatch()
        store.markFetched("sh600519")

        store.endBatch()

        assertEquals(1, prefs.commitCount)
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

            override fun commit(): Boolean {
                commitCount++
                applyChanges()
                return nextCommitResult
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
