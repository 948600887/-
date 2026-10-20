package com.example.stockdiary.data

import com.example.stockdiary.data.sync.SyncBudget
import com.example.stockdiary.data.sync.shouldContinueAfterChildCancel
import com.example.stockdiary.data.sync.shouldEmitHeartbeat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 刷新链路「时间预算 + 心跳节流」的回归。
 *
 * ## 为什么这些纯函数值得单独钉死
 *
 * 上一轮卡死（点刷新后黑屏、112 秒无日志、进程重启）暴露两个空白：
 * 刷新没有总时长上限（无限转圈直到系统杀进程），中间没有任何日志
 * （静默到无法定位）。这两个补丁都是这次修复的核心承诺，而它们的规则
 * 全是边界型判断：
 *
 * - 预算判定差一个等号，就是「还差一个标的没同步」时提前/滞后收手；
 * - 预取子预算的比例算错，价格同步（用户点刷新真正要看的东西）会被
 *   分红级联吃光预算，一个价格都同步不完就被判超时；
 * - 心跳节流失效，5 秒一条会变成一毫秒一条，把日志渠道本身打爆。
 *
 * 规则本身不碰 Context / 网络 / 时钟（时钟由入参传入），所以能直接 JVM 单测。
 */
class SyncBudgetTest {

    // ---------- 时间预算判定 ----------

    @Test
    fun `不设预算时永远不截断`() {
        // timeLimitMs <= 0 是「不设限」的约定：后台 Worker 传自己的上限，
        // 显式传 0 的调用方（如分红收入计算）保持改造前的行为
        assertFalse(SyncBudget.isExceeded(startTime = 1_000L, timeLimitMs = 0L, now = 999_999_999L))
        assertFalse(SyncBudget.isExceeded(startTime = 1_000L, timeLimitMs = -1L, now = 999_999_999L))
    }

    @Test
    fun `未达到预算不截断_达到即截断`() {
        val start = 10_000L
        // 差 1 毫秒到点：不截断，让最后一个标的有机会跑
        assertFalse(SyncBudget.isExceeded(start, 90_000L, start + 89_999L))
        // 正好到点：截断。刷新不该「再跑一个就好」
        assertTrue(SyncBudget.isExceeded(start, 90_000L, start + 90_000L))
        // 早已过点：截断
        assertTrue(SyncBudget.isExceeded(start, 90_000L, start + 120_000L))
    }

    @Test
    fun `时钟异常回拨不会误判超时`() {
        // now 早于 startTime（用户改系统时间）：差值为负，不截断。
        // 宁可多跑一会，也不能把「没超时」误判成超时把用户的数据截半
        assertFalse(SyncBudget.isExceeded(startTime = 100_000L, timeLimitMs = 90_000L, now = 50_000L))
    }

    // ---------- 分红预取子预算 ----------

    @Test
    fun `预取子预算_占总预算四成且不超过硬顶`() {
        // 90s 总预算：四成 36s，但硬顶 30s，取小
        assertEquals(30_000L, SyncBudget.prefetchPhaseBudget(90_000L))
        // 75s：四成正好 30s，撞上硬顶
        assertEquals(30_000L, SyncBudget.prefetchPhaseBudget(75_000L))
        // 50s：四成 20s，没到顶
        assertEquals(20_000L, SyncBudget.prefetchPhaseBudget(50_000L))
        // 12.5s：四成 5s（整数除法不能丢）
        assertEquals(5_000L, SyncBudget.prefetchPhaseBudget(12_500L))
    }

    @Test
    fun `不设总预算时预取也不设预算`() {
        assertEquals(0L, SyncBudget.prefetchPhaseBudget(0L))
        assertEquals(0L, SyncBudget.prefetchPhaseBudget(-1L))
    }

    @Test
    fun `预取子预算永远不超过总预算本身`() {
        // 总预算比硬顶还小的时候，四成必须仍然小于总预算，
        // 否则预取阶段就能把整个刷新预算吃光
        val tiny = 4_000L
        val prefetch = SyncBudget.prefetchPhaseBudget(tiny)
        assertTrue("预取预算 $prefetch 超过总预算 $tiny", prefetch in 1 until tiny)
    }

    // ---------- 心跳节流 ----------

    @Test
    fun `心跳按间隔节流_间隔内不重发_到点必发`() {
        assertFalse(shouldEmitHeartbeat(startedAt = 0L, now = 4_999L, intervalMs = 5_000L))
        assertTrue(shouldEmitHeartbeat(startedAt = 0L, now = 5_000L, intervalMs = 5_000L))
        assertTrue(shouldEmitHeartbeat(startedAt = 0L, now = 12_000L, intervalMs = 5_000L))
    }

    @Test
    fun `心跳间隔为零视为每条都发`() {
        // 防御极端配置：intervalMs=0 时不能一条都不发（静默比刷屏更糟）
        assertTrue(shouldEmitHeartbeat(startedAt = 0L, now = 0L, intervalMs = 0L))
        assertTrue(shouldEmitHeartbeat(startedAt = 0L, now = 1L, intervalMs = -1L))
    }

    // ---------- 看门狗取消子任务后的吞/抛判定 ----------

    @Test
    fun `看门狗取消子任务_父协程仍活则吞掉继续收尾`() {
        // 只有子任务被看门狗砍了、收集方（父协程）还活着 → 吞掉这次取消，
        // 继续收尾其余标的，最终照常返回带 truncatedByTimeLimit=true 的 SyncResult
        assertTrue(shouldContinueAfterChildCancel(parentActive = true))
    }

    @Test
    fun `外部取消_父协程也死了则必须重抛`() {
        // 父协程也死了（退后台取消 / VM 销毁）→ 不吞，原样重抛（项目铁律：取消不吞），
        // 否则取消会被误当成「刷新失败」弹提示、刷新态错乱
        assertFalse(shouldContinueAfterChildCancel(parentActive = false))
    }
}
