package com.example.stockdiary.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.example.stockdiary.database.DividendDao
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `getCachedAllowingStaleBatch` 的 `IN (:symbols)` 分片回归。
 *
 * SQLite 的 IN 子句变量个数有上限：Android ≤11 编译默认 999、12+ 为 32766。
 * 从前这里是一次 `dao.getBySymbols(needDb)`，只写了一行注释「240 个远低于 999」。
 * 标的过千时整批抛 `SQLiteException: too many SQL variables`，而这条链路三层调用
 * 都没有 try/catch —— 「预计收入」与日历分红会整块永久失效。
 *
 * 这条用例把「必须分片」钉死：拿掉 `chunked(SQL_IN_CHUNK)`，DAO 只会被调用一次、
 * 且那一次的实参长度是 1200，两个断言都会红。
 */
class DividendCacheReaderChunkTest {

    @Test
    fun `needDb 超过分片上限时按片查询且结果被合并`() = runBlocking {
        val chunkSizes = mutableListOf<Int>()
        val dao = mockk<DividendDao>(relaxed = true)
        coEvery { dao.getBySymbols(any()) } coAnswers {
            chunkSizes += firstArg<List<String>>().size
            emptyList()
        }

        val prefs = MemoryPrefs()
        val context = mockk<Context> {
            every { getSharedPreferences(any(), any()) } returns prefs
        }
        val store = DividendFetchStateStore(context, { false }) { 0L }
        val reader = DividendCacheReader(DividendMemoryCache(), store, dao)

        val symbols = (1..1200).map { "sh" + it.toString().padStart(6, '0') }
        reader.getCachedAllowingStaleBatch(symbols)

        assertTrue("必须真的查了库", chunkSizes.isNotEmpty())
        assertTrue(
            "每片都不能超过 SQL_IN_CHUNK（否则仍会撞 SQLite 变量上限），实际: $chunkSizes",
            chunkSizes.all { it <= SQL_IN_CHUNK }
        )
        assertEquals("分片后总条数必须等于去重后的标的数", 1200, chunkSizes.sum())
        assertTrue("1200 个标的必须分成多片", chunkSizes.size >= 3)
    }

    /** 最小内存版 SharedPreferences：只需要「读到的键不存在时返回默认值」这一条真实语义。 */
    private class MemoryPrefs : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = HashMap(values)
        override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            defValues

        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean =
            values[key] as? Boolean ?: defValue

        override fun contains(key: String): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val puts = mutableMapOf<String, Any?>()
            private val removes = mutableSetOf<String>()

            override fun putString(key: String, value: String?) = apply { puts[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { puts[key] = values }
            override fun putInt(key: String, value: Int) = apply { puts[key] = value }
            override fun putLong(key: String, value: Long) = apply { puts[key] = value }
            override fun putFloat(key: String, value: Float) = apply { puts[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { puts[key] = value }
            override fun remove(key: String) = apply { removes += key }
            override fun clear() = apply { removes += values.keys }

            override fun commit(): Boolean {
                removes.forEach { values.remove(it) }
                values.putAll(puts)
                return true
            }

            override fun apply() {
                commit()
            }
        }
    }
}
