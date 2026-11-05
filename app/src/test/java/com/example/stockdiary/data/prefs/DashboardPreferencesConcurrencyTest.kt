package com.example.stockdiary.data.prefs

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [DashboardPreferencesManager.updatePreferences] 并发写原子性回归。
 *
 * 为什么需要它：各 ViewModel 各持一份 [DashboardPreferences] 内存副本，早期直接
 * `savePreferences(过期副本)` 会把别的页面刚改的字段整块覆盖掉（典型症状：日历 FIRE 的
 * 「月度生活费」被持仓页冲回旧值，表现为「改了但不保存」）。[updatePreferences] 用
 * `@Synchronized` + 「先拉磁盘最新值、只套用 mutate 里改的字段」从根上消除这类踩踏。
 *
 * 本用例用**真并发**（Dispatchers.IO + 起跑门）让两个协程同时各改一个不同字段，
 * 重复多轮断言两处改动最终都在。若哪天有人去掉 @Synchronized 或退回到整块覆盖写，
 * 这里会因为 `load → mutate → save` 交错丢失一个字段而失败。
 *
 * 注：`updatePreferences` 是同步方法、不产生 SharedFlow 事件，因此这里用
 * `runBlocking + Dispatchers.IO` 制造真实线程竞争；VM 事件流测试才需要 runTest +
 * `async(start=UNDISPATCHED){ flow.first() }` + withTimeout 那一套。
 */
class DashboardPreferencesConcurrencyTest {

    /** 线程安全内存版 SharedPreferences：apply 立即生效，行为贴近真机 */
    private class FakePrefs : SharedPreferences {
        val values = ConcurrentHashMap<String, Any?>()

        override fun getAll(): MutableMap<String, Any?> = HashMap(values)

        override fun getString(key: String?, defValue: String?): String? =
            if (key != null && values.containsKey(key)) values[key] as? String else defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            if (key != null && values.containsKey(key)) {
                (values[key] as? Set<String>)?.toMutableSet()
            } else {
                defValues
            }

        override fun getInt(key: String?, defValue: Int): Int =
            if (key != null && values.containsKey(key)) values[key] as? Int ?: defValue else defValue

        override fun getLong(key: String?, defValue: Long): Long =
            if (key != null && values.containsKey(key)) values[key] as? Long ?: defValue else defValue

        override fun getFloat(key: String?, defValue: Float): Float =
            if (key != null && values.containsKey(key)) values[key] as? Float ?: defValue else defValue

        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            if (key != null && values.containsKey(key)) values[key] as? Boolean ?: defValue else defValue

        override fun contains(key: String?): Boolean = key != null && values.containsKey(key)

        override fun edit(): SharedPreferences.Editor = FakeEditor(values)

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit
    }

    private class FakeEditor(private val values: MutableMap<String, Any?>) : SharedPreferences.Editor {
        private val pending = LinkedHashMap<String, Any?>()
        private var cleared = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun putStringSet(key: String, value: MutableSet<String>?): SharedPreferences.Editor {
            pending[key] = value?.toSet()
            return this
        }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun remove(key: String): SharedPreferences.Editor {
            pending[key] = null
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            cleared = true
            return this
        }

        override fun commit(): Boolean {
            flush()
            return true
        }

        override fun apply() = flush()

        /** 语义对齐真机：clear 先清整张表，再套用本次 remove/put */
        private fun flush() {
            if (cleared) values.clear()
            pending.forEach { (key, value) ->
                if (value == null) values.remove(key) else values[key] = value
            }
            pending.clear()
            cleared = false
        }
    }

    private fun managerWith(prefs: FakePrefs): DashboardPreferencesManager {
        val context = mockk<Context>()
        every { context.getSharedPreferences(any(), any()) } returns prefs
        return DashboardPreferencesManager(context)
    }

    @Test
    fun `并发各改不同字段_两处改动最终都在_互不覆盖`() = runBlocking {
        val manager = managerWith(FakePrefs())
        // 多轮 + 每轮不同取值：让「读旧值 → 各自整块写回 → 后写覆盖先写」的竞态
        // 有足够机会暴露，也避免两轮恰好写入相同值而假绿。
        val rounds = 120
        repeat(rounds) { i ->
            val expense = "7${i.toString().padStart(3, '0')}"
            val showGrowth = (i % 2 == 0)
            val gate = CompletableDeferred<Unit>()

            val expenseJob = async(Dispatchers.IO) {
                gate.await()
                manager.updatePreferences { it.copy(monthlyExpense = expense) }
            }
            val visibilityJob = async(Dispatchers.IO) {
                gate.await()
                manager.updatePreferences { it.copy(showAssetGrowth = showGrowth) }
            }
            gate.complete(Unit)
            expenseJob.await()
            visibilityJob.await()

            val loaded = manager.loadPreferences()
            assertEquals(
                "第 $i 轮：月度生活费不能被卡片可见性写入覆盖",
                expense,
                loaded.monthlyExpense
            )
            assertEquals(
                "第 $i 轮：卡片可见性不能被月度生活费写入覆盖",
                showGrowth,
                loaded.showAssetGrowth
            )
        }
    }

    @Test
    fun `updatePreferences 返回写盘后的完整副本_其余字段保持最新值`() {
        val manager = managerWith(FakePrefs())

        val first = manager.updatePreferences { it.copy(monthlyExpense = "8800") }
        assertEquals("8800", first.monthlyExpense)
        assertEquals("8800", manager.loadPreferences().monthlyExpense)

        // 第二次只改卡片可见性，返回值与磁盘都必须仍带着上一次的月度生活费
        val second = manager.updatePreferences { it.copy(showAssetGrowth = false) }
        assertEquals("8800", second.monthlyExpense)
        assertEquals(false, second.showAssetGrowth)
        assertEquals("8800", manager.loadPreferences().monthlyExpense)
        assertEquals(false, manager.loadPreferences().showAssetGrowth)
    }
}
