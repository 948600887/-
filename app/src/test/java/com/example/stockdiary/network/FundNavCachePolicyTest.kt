package com.example.stockdiary.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「缓存里已是今日净值」判定的回归。
 *
 * ## 为什么这个判断值得单独钉死
 *
 * 场外基金每只需要下载天天基金 `pingzhongdata/{code}.js`，实测响应
 * **0.5~2MB**（完整历史净值，而我们要的只是今天一个数）。106 个持仓
 * × 同步并发数，峰值内存足以让进程被系统回收 —— 症状就是用户反馈的
 * 「点刷新黑屏然后闪退」，且零崩溃报告。
 *
 * 所以 `forceRefresh` 不能一律绕过缓存：净值每交易日只公布一次，
 * 缓存里已经是今天公布的净值时，重下那份大文件也拿不到更新的数。
 * 这条判断两个方向都错不起：
 *
 * - 该认而不认 → 每次刷新重下 50~200MB，进程被压死（就是本次要修的事故）
 * - 不该认而认 → 同一天拿到旧净值；但当日只公布一次，客观上没有更新，
 *   所以这个方向的代价接近于零。真正要防的是「把脏数据/空日期当成今日净值」，
 *   那会让用户一整天看到错的数 —— 所以空值与格式不符一律返回 false
 */
class FundNavCachePolicyTest {

    private val today = "2026-10-14"

    @Test
    fun `同日净值认定为今日_forceRefresh 也走缓存`() {
        assertTrue(isTodaysNav("2026-10-14", today))
    }

    @Test
    fun `昨日净值不认定为今日_必须重下`() {
        assertFalse(isTodaysNav("2026-10-13", today))
    }

    @Test
    fun `更早的净值不认定为今日`() {
        assertFalse(isTodaysNav("2026-09-30", today))
        assertFalse(isTodaysNav("2025-10-14", today))
    }

    @Test
    fun `空值与空白一律不认定_宁可重下也不把脏数据当今日净值`() {
        assertFalse(isTodaysNav(null, today))
        assertFalse(isTodaysNav("", today))
        assertFalse(isTodaysNav("   ", today))
    }

    @Test
    fun `格式不符不认定_防止把异常字符串当日期`() {
        assertFalse(isTodaysNav("2026/10/14", today))
        assertFalse(isTodaysNav("14-10-2026", today))
        assertFalse(isTodaysNav("--", today))
    }

    @Test
    fun `首尾空白不影响判定_净值日期来自网页解析可能有空格`() {
        assertTrue(isTodaysNav(" 2026-10-14 ", today))
        assertTrue(isTodaysNav("\t2026-10-14\n", today))
    }
}
