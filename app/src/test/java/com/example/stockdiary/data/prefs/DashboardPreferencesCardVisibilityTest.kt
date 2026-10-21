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
 * 钉住四件事：
 *  1. **默认 true**：这四个都是后加的开关，字段插在既有开关中间。老用户升级后
 *     SharedPreferences 里根本没有对应 key，必须回退成「显示」——否则用户一升级就凭空少卡片。
 *  2. save → load **往返保真**：用户主动关掉之后，下次冷启动必须还是关的（不能被默认值吃掉）。
 *  3. 新增枚举项（ASSET_GROWTH / DIVIDEND_PROGRESS / NEXT_PAYOUT / MONTHLY_CASH_FLOW）
 *     与各自偏好落到**同一个 key**：[DashboardPreferencesManager] 里两个 `when (component)`
 *     都是穷举且无 else，少补一个分支就是编译错；补错 key 则编译通过但开关静默失效。
 *     这里用 updateComponentVisibility / isComponentVisible / getVisibleComponents 双向验证接线。
 *  4. **四个开关互不串 key**：一口气补 8 个 when 分支全是复制粘贴改名字，两个分支指到同一个
 *     KEY 时编译照样过，只会表现为开关互相牵连——看界面查不出来，只能靠用例钉。
 *
 * 内存版 SharedPreferences：不引入 Robolectric，又能驱动真实的 Manager 逻辑。
 * （同 [com.example.stockdiary.data.dividend.DividendIncomeCacheVerifyTest] 的 FakePrefs 套路，
 * 但那个只实现了 getString，这里需要全类型存储才能跑 savePreferences。）
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

    /** 本次新增的四个卡片开关：枚举项 ↔ 偏好字段的对应关系清单 */
    private val cardSwitches: List<Pair<DashboardComponent, (DashboardPreferences) -> Boolean>> = listOf(
        DashboardComponent.ASSET_GROWTH to { it.showAssetGrowth },
        DashboardComponent.DIVIDEND_PROGRESS to { it.showDividendProgress },
        DashboardComponent.NEXT_PAYOUT to { it.showNextPayout },
        DashboardComponent.MONTHLY_CASH_FLOW to { it.showMonthlyCashFlow }
    )

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
    fun `ASSET_GROWTH 组件接线_默认可见且可与偏好互操作`() {
        val prefs = FakePrefs()
        val manager = managerWith(prefs)

        // 穷举 when 的兜底默认值是 component != TOTAL_ASSETS → 对新项自然 true
        assertTrue(manager.isComponentVisible(DashboardComponent.ASSET_GROWTH))
        assertTrue(manager.getVisibleComponents().contains(DashboardComponent.ASSET_GROWTH))

        manager.updateComponentVisibility(DashboardComponent.ASSET_GROWTH, false)
        assertFalse(manager.isComponentVisible(DashboardComponent.ASSET_GROWTH))
        assertFalse(manager.getVisibleComponents().contains(DashboardComponent.ASSET_GROWTH))
        // 走的是同一个 key，savePreferences 读到的必须也是关
        assertFalse(manager.loadPreferences().showAssetGrowth)

        manager.updateComponentVisibility(DashboardComponent.ASSET_GROWTH, true)
        assertTrue(manager.isComponentVisible(DashboardComponent.ASSET_GROWTH))
        assertTrue(manager.loadPreferences().showAssetGrowth)
    }

    @Test
    fun `恢复默认设置后_资产增值重新可见`() {
        val prefs = FakePrefs()
        val manager = managerWith(prefs)

        manager.savePreferences(DashboardPreferences(showAssetGrowth = false))
        assertFalse(manager.loadPreferences().showAssetGrowth)

        // 设置面板的「恢复默认设置」按钮就是写回 DashboardPreferences()
        manager.savePreferences(DashboardPreferences())
        assertTrue(manager.loadPreferences().showAssetGrowth)
        assertTrue(manager.isComponentVisible(DashboardComponent.ASSET_GROWTH))
    }

    // ============ 收入组件三张卡：收息进度 / 下一笔分红 / 月度现金流 ============

    @Test
    fun `四个新卡片开关_数据类默认值全部为 true`() {
        val defaults = DashboardPreferences()
        cardSwitches.forEach { (component, read) ->
            assertTrue("${component.key} 的默认值必须是 true", read(defaults))
        }
    }

    @Test
    fun `四个新卡片开关_空存储全部回退为显示_老用户升级一张卡都不丢`() {
        val loaded = managerWith(FakePrefs()).loadPreferences()
        cardSwitches.forEach { (component, read) ->
            assertTrue("${component.key} 在空存储下必须是显示", read(loaded))
        }
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
        cardSwitches.forEach { (component, read) ->
            assertFalse("${component.key} 关掉后重读必须仍是 false", read(allOff))
        }

        manager.savePreferences(DashboardPreferences())
        val allOn = manager.loadPreferences()
        cardSwitches.forEach { (component, read) ->
            assertTrue("${component.key} 打开后重读必须仍是 true", read(allOn))
        }
    }

    @Test
    fun `四个新卡片开关互不串 key_关掉一个不影响其它三个`() {
        cardSwitches.forEach { (target, _) ->
            val manager = managerWith(FakePrefs())
            manager.updateComponentVisibility(target, false)

            val loaded = manager.loadPreferences()
            cardSwitches.forEach { (component, read) ->
                val shouldBeVisible = component != target
                assertEquals(
                    "关闭 ${target.key} 后 ${component.key} 的显示状态不对，when 分支可能串了 key",
                    shouldBeVisible,
                    read(loaded)
                )
                assertEquals(
                    "${component.key} 的 isComponentVisible 与偏好字段不一致",
                    shouldBeVisible,
                    manager.isComponentVisible(component)
                )
            }
        }
    }

    @Test
    fun `收入组件三张卡_默认都在可见组件列表里_关掉后全部移出`() {
        val manager = managerWith(FakePrefs())
        cardSwitches.forEach { (component, _) ->
            assertTrue(manager.getVisibleComponents().contains(component))
        }

        manager.savePreferences(
            DashboardPreferences(
                showDividendProgress = false,
                showNextPayout = false,
                showMonthlyCashFlow = false
            )
        )
        val visible = manager.getVisibleComponents()
        assertFalse(visible.contains(DashboardComponent.DIVIDEND_PROGRESS))
        assertFalse(visible.contains(DashboardComponent.NEXT_PAYOUT))
        assertFalse(visible.contains(DashboardComponent.MONTHLY_CASH_FLOW))
        // 资产增值开关没动，必须还在
        assertTrue(visible.contains(DashboardComponent.ASSET_GROWTH))
    }
}
