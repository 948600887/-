package com.example.stockdiary.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A2 回归：刷新期间「年化收入率」分母冻结值的推进。
 *
 * 关键场景是**旋屏/Activity 重建**：`rememberSaveable` 恢复了冻结值，但
 * `LaunchedEffect(isRefreshing)` 在重建后会重新启动（isRefreshing 仍为 true）。
 * 旧实现无条件用当前 netAssets 覆盖 → 恢复值当场失效、分母回退到已被同步改掉一半的实时值，
 * 年化率仍然跳。这里钉死「已有冻结值就保持」。
 */
class DashboardYieldFreezeTest {

    @Test
    fun `进入刷新且尚未冻结_记录当前净资产`() {
        assertEquals("1000.0", nextFrozenYieldNetAssets(current = null, isRefreshing = true, netAssets = 1000.0))
    }

    @Test
    fun `刷新中旋屏重建_恢复的冻结值不被当前净资产覆盖`() {
        // rememberSaveable 恢复的冻结值 vs 重建时已被同步改掉一半的 netAssets
        assertEquals(
            "重建后必须保持恢复的冻结值",
            "1000.0",
            nextFrozenYieldNetAssets(current = "1000.0", isRefreshing = true, netAssets = 1234.5)
        )
    }

    @Test
    fun `刷新结束_释放冻结值回到实时净资产`() {
        assertNull(nextFrozenYieldNetAssets(current = "1000.0", isRefreshing = false, netAssets = 1234.5))
    }

    @Test
    fun `未在刷新且无冻结值_保持为空`() {
        assertNull(nextFrozenYieldNetAssets(current = null, isRefreshing = false, netAssets = 1000.0))
    }
}
