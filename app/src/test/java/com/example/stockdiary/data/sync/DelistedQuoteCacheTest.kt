package com.example.stockdiary.data.sync

import android.content.Context
import android.content.SharedPreferences
import com.example.stockdiary.network.EastmoneyDelistedQuoteApi
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DelistedQuoteCacheTest {

    private val prefs = FakePrefs()
    private val cache = DelistedQuoteCache(
        mockk<Context> {
            every { getSharedPreferences(any(), any()) } returns prefs
        }
    )

    @Test
    fun `同一自然日确认过就不再请求_跨日重新确认`() {
        val day = LocalDate.of(2026, 11, 8)
        assertFalse("没查过时不能短路", cache.isCheckedOn("bj400264", day))

        cache.record(
            "bj400264",
            EastmoneyDelistedQuoteApi.DelistedQuote(0.22, "2026-11-06"),
            day
        )
        assertTrue("当天已确认过", cache.isCheckedOn("bj400264", day))
        assertFalse("跨日要重新确认是否有新收盘", cache.isCheckedOn("bj400264", day.plusDays(1)))
    }

    @Test
    fun `交易日与收盘价按原样落缓存_失败记录不留旧价`() {
        val day = LocalDate.of(2026, 11, 8)
        cache.record(
            "bj400264",
            EastmoneyDelistedQuoteApi.DelistedQuote(0.22, "2026-11-06"),
            day
        )
        val cached = cache.cachedQuote("bj400264")!!
        assertEquals(0.22, cached.price, 1e-9)
        assertEquals("交易日不能被抓取日替代", "2026-11-06", cached.tradeDate)

        // 同一天再查到「无可用历史收盘」：只更新检查日，不能把之前的价格当成功
        cache.record("bj400264", null, day)
        assertTrue(cache.isCheckedOn("bj400264", day))
        assertNull("失败结果不应保留旧收盘价", cache.cachedQuote("bj400264"))
    }

    @Test
    fun `代码大小写归一`() {
        val day = LocalDate.of(2026, 11, 8)
        cache.record(
            "bj400264",
            EastmoneyDelistedQuoteApi.DelistedQuote(0.22, "2026-11-06"),
            day
        )
        assertTrue(cache.isCheckedOn(" BJ400264 ", day))
    }

    private class FakePrefs : SharedPreferences {
        val values = mutableMapOf<String, String>()
        override fun getString(key: String?, defValue: String?): String? = values[key] ?: defValue
        override fun edit(): SharedPreferences.Editor = FakeEditor(values)
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = defValue
        override fun getLong(key: String?, defValue: Long): Long = defValue
        override fun getFloat(key: String?, defValue: Float): Float = defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
        override fun contains(key: String?): Boolean = values.containsKey(key)
        override fun getAll(): MutableMap<String, Any?> = HashMap<String, Any?>(values)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    }

    private class FakeEditor(private val values: MutableMap<String, String>) : SharedPreferences.Editor {
        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            if (value == null) values.remove(key) else values[key] = value
            return this
        }
        override fun putStringSet(key: String, value: MutableSet<String>?): SharedPreferences.Editor = this
        override fun putInt(key: String, value: Int): SharedPreferences.Editor = this
        override fun putLong(key: String, value: Long): SharedPreferences.Editor = this
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = this
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = this
        override fun remove(key: String): SharedPreferences.Editor { values.remove(key); return this }
        override fun clear(): SharedPreferences.Editor { values.clear(); return this }
        override fun commit(): Boolean = true
        override fun apply() = Unit
    }
}
