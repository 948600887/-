package com.example.stockdiary.ui.viewmodels

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/**
 * [buildAssetCalendarMonth] 的口径契约。
 *
 * 重点钉住「跨午夜重算」场景（2026-10-25 零点后用户实测：App 进程跨天存活时，
 * 25 号格一直「暂无数据」、本月总览停在昨天的值）——VM 侧的修复是午夜翻日协程 +
 * 回前台保鲜检查（见 CalendarViewModel），本测试锁的是它们依赖的纯函数语义：
 * 同一个函数用「新的 today」再算一遍，新的一天那格就必须拿到实时值。
 */
class CalendarMonthTest {

    private val october2026 = YearMonth.of(2026, 10)

    @Test
    fun `今天一格用实时净值覆盖当日快照`() {
        val today = LocalDate.of(2026, 10, 25)
        val data = buildAssetCalendarMonth(
            month = october2026,
            snapshotNetAssets = mapOf(LocalDate.of(2026, 10, 24) to 197773.49, today to 190000.0),
            liveNetAssetsToday = 197827.41,
            today = today
        )
        // 今天以实时值为准（与资产页/首页同口径），即使当日已有快照
        assertEquals(197827.41, data[today]!!, 1e-9)
        // 过去的日子仍定格快照
        assertEquals(197773.49, data[LocalDate.of(2026, 10, 24)]!!, 1e-9)
    }

    @Test
    fun `跨天后按新 today 重算则新的一天从暂无数据变为实时值`() {
        // 症状复现：日历按昨天（10-24）生成，25 号格为 0（界面显示「暂无数据」）
        val generatedYesterday = buildAssetCalendarMonth(
            month = october2026,
            snapshotNetAssets = mapOf(LocalDate.of(2026, 10, 24) to 197773.49),
            liveNetAssetsToday = 197773.49,
            today = LocalDate.of(2026, 10, 24)
        )
        assertEquals(0.0, generatedYesterday[LocalDate.of(2026, 10, 25)]!!, 1e-9)

        // 跨午夜后按新 today 重算：25 号拿到实时值，本月总览的「最新一天」随之跟上
        val regenerated = buildAssetCalendarMonth(
            month = october2026,
            snapshotNetAssets = mapOf(LocalDate.of(2026, 10, 24) to 197773.49),
            liveNetAssetsToday = 197827.41,
            today = LocalDate.of(2026, 10, 25)
        )
        assertEquals(197827.41, regenerated[LocalDate.of(2026, 10, 25)]!!, 1e-9)
    }

    @Test
    fun `翻看非当月时不注入实时值_无快照日保持 0`() {
        val data = buildAssetCalendarMonth(
            month = october2026,
            snapshotNetAssets = emptyMap(),
            liveNetAssetsToday = 197827.41,
            today = LocalDate.of(2026, 11, 1)
        )
        assertEquals(0.0, data[LocalDate.of(2026, 10, 15)]!!, 1e-9)
    }
}
