package com.example.stockdiary.data.prefs

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 仪表盘卡片显示开关的持久化契约测试：
 * [DashboardPreferences.showAssetGrowth]（资产增值）、[DashboardPreferences.showDividendProgress]
 * （收息进度）、[DashboardPreferences.showNextPayout]（下一笔分红）、
 * [DashboardPreferences.showMonthlyCashFlow]（月度现金流）。
 *
 * 注意：早期的 `DashboardComponent` 枚举 + `updateComponentVisibility` / `isComponentVisible` /
 * `getVisibleComponents` 已被确认是**死代码**（生产零消费），相关用例已随死代码一并删除。
 * 本文件只钉住真正生效的 [DashboardPreferences] 数据类 + `savePreferences` / `loadPreferences`
 * 往返契约，避免误删对真实持久化逻辑的保护。
 *
 * 钉住四件事：
 *  1. **默认 true**：这四个都是后加的开关，字段插在既有开关中间。老用户升级后
 *     SharedPreferences 里根本没有对应 key，必须回退成「显示」——否则用户一升级就凭空少卡片。
 *  2. save → load **往返保真**：用户主动关掉之后，下次冷启动必须还是关的（不能被默认值吃掉）。
 *  3. **互不串 key**：一口气补十几个偏好字段全是复制粘贴改名字，两个字段指到同一个
 *     KEY 时编译照样过，只会表现为开关互相牵连——看界面查不出来，只能靠用例钉。
 *  4. **恢复默认**：写回 [DashboardPreferences()] 后重新读取必须回到全显示。
 *
 * 内存版 SharedPreferences：不引入 Robolectric，又能驱动真实的 Manager 逻辑。
 */
class DashboardPreferencesCardVisibilityTest {

    /** 全类型内存版 SharedPreferences：apply 立即生效，行为贴近真机 */
    private class FakePrefs : SharedPreferences {
        val values = LinkedHashMap<String, Any?>()

        override fun getAll(): MutableMap<String, Any?> = LinkedHashMap(values)
        override fun getString(key: String?, defValue: String?): String? =
            if (values.containsKey(key)) values[key] as? String else defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (values[key] as? Set<String>)?.toMutableSet() ?: defValues

        override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            values[key] as? Boolean ?: defValue

        override fun contains(key: String?): Boolean = values.containsKey(key)
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
    fun `数据类默认值_showAssetGrowth 为 true_无需任何存储`() {
        // 这是最容易被后继重构改坏的一行：字段默认值被误写成 false，
        // 新装 App 的用户就看不见资产增值卡片了。
        assertTrue(DashboardPreferences().showAssetGrowth)
    }

    @Test
    fun `空存储_loadPreferences 回退为显示_老用户升级不丢卡片`() {
        val manager = managerWith(FakePrefs())

        assertTrue(manager.loadPreferences().showAssetGrowth)
    }

    @Test
    fun `关掉后_save 再 load 仍为关闭_不被默认值吃掉`() {
        val prefs = FakePrefs()
        val manager = managerWith(prefs)

        manager.savePreferences(DashboardPreferences(showAssetGrowth = false))
        assertFalse("关掉后重新读取必须仍是 false", manager.loadPreferences().showAssetGrowth)

        // 反向：再打开也要能存住
        manager.savePreferences(DashboardPreferences(showAssetGrowth = true))
        assertTrue(manager.loadPreferences().showAssetGrowth)
    }

    @Test
    fun `开关写入不污染其它摘要开关`() {
        val prefs = FakePrefs()
        val manager = managerWith(prefs)

        manager.savePreferences(
            DashboardPreferences(
                showTotalAssets = true,
                showNetAssets = false,
                showAssetGrowth = false
            )
        )

        val loaded = manager.loadPreferences()
        assertFalse(loaded.showAssetGrowth)
        assertFalse(loaded.showNetAssets)
        assertTrue(loaded.showTotalAssets)
        // 没动过的开关保持各自原有默认值
        assertTrue(loaded.showProfit)
        assertTrue(loaded.showDistribution)
    }

    @Test
    fun `四个新卡片开关_数据类默认值全部为 true`() {
        val defaults = DashboardPreferences()
        assertTrue(defaults.showAssetGrowth)
        assertTrue(defaults.showDividendProgress)
        assertTrue(defaults.showNextPayout)
        assertTrue(defaults.showMonthlyCashFlow)
    }

    @Test
    fun `四个新卡片开关_空存储全部回退为显示_老用户升级一张卡都不丢`() {
        val loaded = managerWith(FakePrefs()).loadPreferences()
        assertTrue(loaded.showAssetGrowth)
        assertTrue(loaded.showDividendProgress)
        assertTrue(loaded.showNextPayout)
        assertTrue(loaded.showMonthlyCashFlow)
    }

    @Test
    fun `四个新卡片开关_save 再 load 往返保真_不被默认值吃掉`() {
        val manager = managerWith(FakePrefs())

        manager.savePreferences(
            DashboardPreferences(
                showAssetGrowth = false,
                showDividendProgress = false,
                showNextPayout = false,
                showMonthlyCashFlow = false
            )
        )
        val allOff = manager.loadPreferences()
        assertFalse(allOff.showAssetGrowth)
        assertFalse(allOff.showDividendProgress)
        assertFalse(allOff.showNextPayout)
        assertFalse(allOff.showMonthlyCashFlow)

        manager.savePreferences(DashboardPreferences())
        val allOn = manager.loadPreferences()
        assertTrue(allOn.showAssetGrowth)
        assertTrue(allOn.showDividendProgress)
        assertTrue(allOn.showNextPayout)
        assertTrue(allOn.showMonthlyCashFlow)
    }
}
