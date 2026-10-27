package com.example.stockdiary.network

import com.example.stockdiary.database.FundNavCache
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

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
 *
 * 后半部分是**持久化**净值缓存（Room，跨进程复用）的复用 / 节流策略：
 * [shouldReusePersistentNav] 与 [shouldThrottleNavDownload]。
 */
class FundNavCachePolicyTest {

    private val today = "2026-10-14"

    // 用例里的日期与星期是配套的，先钉死「我认为的星期」，
    // 免得将来有人改日期却没改星期，测试悄悄测错了分支。
    private val wednesday = LocalDate.parse("2026-10-14").dayOfWeek
    private val saturday = LocalDate.parse("2026-10-17").dayOfWeek
    private val sunday = LocalDate.parse("2026-10-18").dayOfWeek

    /** 指定日期 + 时刻 → 毫秒时间戳（系统时区），用于构造节流用例。 */
    private fun epochAt(date: String, hour: Int, minute: Int): Long {
        return LocalDate.parse(date)
            .atTime(hour, minute)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }

    // ==================== 原 isTodaysNav ====================

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

    // ==================== T1：同日即复用（零下载） ====================

    @Test
    fun `用例日期的星期与预期一致`() {
        assertEquals(DayOfWeek.WEDNESDAY, wednesday)
        assertEquals(DayOfWeek.SATURDAY, saturday)
        assertEquals(DayOfWeek.SUNDAY, sunday)
    }

    @Test
    fun `T1_工作日库里已是今日净值_复用零下载`() {
        assertTrue(shouldReusePersistentNav("2026-10-14", "2026-10-14", wednesday, 21))
    }

    @Test
    fun `T1_周末库里已是今日净值_同样复用`() {
        assertTrue(shouldReusePersistentNav("2026-10-17", "2026-10-17", saturday, 21))
        assertTrue(shouldReusePersistentNav("2026-10-18", "2026-10-18", sunday, 21))
    }

    @Test
    fun `T1_进程被杀后重启的场景_库里是今日净值就该直接复用`() {
        // 这个 case 就是本次修复的主场景：早上第一次刷新把今日净值落库，
        // 进程随后被系统回收，再次打开刷新时应直接读库返回，一次网络都不打。
        val row = FundNavCache(
            code = "000306",
            name = "天弘沪深300",
            nav = 1.2345,
            navDate = "2026-10-14",
            fetchedAt = epochAt("2026-10-14", 21, 30)
        )
        assertTrue(
            shouldReusePersistentNav(
                navDate = row.navDate,
                today = row.navDate,
                dayOfWeek = wednesday,
                hourOfDay = 21
            )
        )
    }

    // ==================== T2：周末复用窗口 ====================

    @Test
    fun `T2_周六复用昨天周五的净值`() {
        assertTrue(shouldReusePersistentNav("2026-10-16", "2026-10-17", saturday, 21))
    }

    @Test
    fun `T2_周日复用两天前周五的净值`() {
        assertTrue(shouldReusePersistentNav("2026-10-16", "2026-10-18", sunday, 21))
    }

    @Test
    fun `T2_窗口边界_4 天内认_第 5 天不认`() {
        assertTrue(shouldReusePersistentNav("2026-10-13", "2026-10-17", saturday, 21)) // 4 天
        assertFalse(shouldReusePersistentNav("2026-10-12", "2026-10-17", saturday, 21)) // 5 天
        assertFalse(shouldReusePersistentNav("2026-10-09", "2026-10-17", saturday, 21)) // 8 天
    }

    // ==================== T3：工作日非今日 → 必须下载 ====================

    @Test
    fun `T3_工作日且库里是昨天的净值_不复用必须重下`() {
        assertFalse(shouldReusePersistentNav("2026-10-13", "2026-10-14", wednesday, 21))
    }

    @Test
    fun `T3_工作日且库里是更早的净值_同样不复用`() {
        assertFalse(shouldReusePersistentNav("2026-10-09", "2026-10-14", wednesday, 21))
        assertFalse(shouldReusePersistentNav("2026-09-30", "2026-10-14", wednesday, 21))
    }

    @Test
    fun `T3_周末那条宽容规则绝不能漏到工作日`() {
        // 同一份「4 天前的净值」（2026-10-13）：周六刷新认，周三刷新不认。
        // 把周末的宽容窗口误用到工作日，用户整周都会看到上周的净值。
        assertTrue(shouldReusePersistentNav("2026-10-13", "2026-10-17", saturday, 21))
        assertFalse(shouldReusePersistentNav("2026-10-10", "2026-10-14", wednesday, 21))
    }

    // ==================== T2：工作日、未到净值公布时点 → 上一交易日即最新 ====================
    //
    // 这条是 2026-10-26 真机诊断后补的：日志显示周一 10:32 那轮把 93 只基金全部重下了一遍，
    // 拿回的仍是周五的 navDate —— 因为旧逻辑只要「工作日 + 库里不是今天」就一律下载，
    // 而场外基金净值 T+1、收盘后才出，下载根本拿不到更新的数。
    // 反讽的是：同样「无新净值可拿」的周末走 T3 分支被复用了，工作日上午反而成了唯一重灾区。

    @Test
    fun `T2_工作日未到公布时点_库里是上一交易日净值_复用零下载`() {
        // 周三 10 点，库里是周二（上一交易日）的净值 = 当前客观最新，不该下载
        assertTrue(shouldReusePersistentNav("2026-10-13", "2026-10-14", wednesday, 10))
    }

    @Test
    fun `T2_周一未到公布时点_库里是上周五净值_复用零下载`() {
        // 周一 10 点，上一交易日要跨过周末回退到上周五
        assertTrue(shouldReusePersistentNav("2026-10-16", "2026-10-19", DayOfWeek.MONDAY, 10))
    }

    @Test
    fun `T2_未到公布时点但库里更早_仍不复用_得下`() {
        // 周三 10 点，库里是 10-09（不是上一交易日 10-13）→ 中间缺了净值，必须下载
        assertFalse(shouldReusePersistentNav("2026-10-09", "2026-10-14", wednesday, 10))
    }

    @Test
    fun `T2_同一份净值_未到公布时点复用_过了就不复用`() {
        // 同一份「周二净值 + 周三刷新」：10 点复用（周三的还没出），21 点必须下载
        assertTrue(shouldReusePersistentNav("2026-10-13", "2026-10-14", wednesday, 10))
        assertFalse(shouldReusePersistentNav("2026-10-13", "2026-10-14", wednesday, 21))
    }

    @Test
    fun `T2_周末不受公布时点影响_仍走周末窗口`() {
        // 周末本来就命中 T3 的 4 天窗口，公布时点那条规则只对工作人生效
        assertTrue(shouldReusePersistentNav("2026-10-16", "2026-10-17", saturday, 10))
        assertTrue(shouldReusePersistentNav("2026-10-16", "2026-10-17", saturday, 21))
    }

    // ==================== 脏数据一律不复用 ====================

    @Test
    fun `空值_空白_格式不符_一律不复用_连周末也不复用`() {
        assertFalse(shouldReusePersistentNav(null, "2026-10-17", saturday, 21))
        assertFalse(shouldReusePersistentNav("", "2026-10-17", saturday, 21))
        assertFalse(shouldReusePersistentNav("   ", "2026-10-17", saturday, 21))
        assertFalse(shouldReusePersistentNav("2026/10/17", "2026-10-17", saturday, 21))
        assertFalse(shouldReusePersistentNav("--", "2026-10-17", saturday, 21))
    }

    @Test
    fun `未来日期不复用_解析出来的脏数据`() {
        assertFalse(shouldReusePersistentNav("2026-10-19", "2026-10-18", sunday, 21))
        assertFalse(shouldReusePersistentNav("2027-01-01", "2026-10-14", wednesday, 21))
    }

    @Test
    fun `today 本身格式不对也不复用_不把异常输入当成可判定`() {
        assertFalse(shouldReusePersistentNav("2026-10-14", "2026/10/14", wednesday, 21))
    }

    // ==================== 下载节流（少下载，不是把旧值当新值） ====================

    @Test
    fun `节流_工作日 16 点前 10 分钟刚下过_这次跳过下载`() {
        assertTrue(
            shouldThrottleNavDownload(
                today = "2026-10-14",
                dayOfWeek = wednesday,
                hourOfDay = 10,
                fetchedAt = epochAt("2026-10-14", 10, 0),
                nowMillis = epochAt("2026-10-14", 10, 10)
            )
        )
    }

    @Test
    fun `节流_窗口边界_29 分 59 秒节流_30 分不节流`() {
        val fetchedAt = epochAt("2026-10-14", 9, 0)
        assertTrue(
            shouldThrottleNavDownload(
                today = "2026-10-14",
                dayOfWeek = wednesday,
                hourOfDay = 9,
                fetchedAt = fetchedAt,
                nowMillis = fetchedAt + 29 * 60 * 1000L + 59_000L
            )
        )
        assertFalse(
            shouldThrottleNavDownload(
                today = "2026-10-14",
                dayOfWeek = wednesday,
                hourOfDay = 9,
                fetchedAt = fetchedAt,
                nowMillis = fetchedAt + 30 * 60 * 1000L
            )
        )
    }

    @Test
    fun `节流_16 点及之后不节流_那是净值公布时段`() {
        assertFalse(
            shouldThrottleNavDownload(
                today = "2026-10-14",
                dayOfWeek = wednesday,
                hourOfDay = 16,
                fetchedAt = epochAt("2026-10-14", 15, 55),
                nowMillis = epochAt("2026-10-14", 16, 5)
            )
        )
    }

    @Test
    fun `节流_周末不节流_周末本来就该走 T2 复用`() {
        assertFalse(
            shouldThrottleNavDownload(
                today = "2026-10-17",
                dayOfWeek = saturday,
                hourOfDay = 10,
                fetchedAt = epochAt("2026-10-17", 10, 0),
                nowMillis = epochAt("2026-10-17", 10, 5)
            )
        )
    }

    @Test
    fun `节流_只在同一个自然日内生效_跨天就该重新下载`() {
        // 昨天 23:55 下过，今天 00:05 再刷新：虽然只隔 10 分钟，但已经是新的一天
        assertFalse(
            shouldThrottleNavDownload(
                today = "2026-10-14",
                dayOfWeek = wednesday,
                hourOfDay = 0,
                fetchedAt = epochAt("2026-10-13", 23, 55),
                nowMillis = epochAt("2026-10-14", 0, 5)
            )
        )
    }

    @Test
    fun `节流_从未下载过或时间戳异常_都不节流`() {
        assertFalse(
            shouldThrottleNavDownload(
                today = "2026-10-14",
                dayOfWeek = wednesday,
                hourOfDay = 10,
                fetchedAt = 0L,
                nowMillis = epochAt("2026-10-14", 10, 0)
            )
        )
        // 时钟被回拨：fetchedAt 晚于 now，不能拿它当节流依据
        assertFalse(
            shouldThrottleNavDownload(
                today = "2026-10-14",
                dayOfWeek = wednesday,
                hourOfDay = 10,
                fetchedAt = epochAt("2026-10-14", 12, 0),
                nowMillis = epochAt("2026-10-14", 10, 0)
            )
        )
    }

    // ==================== 未注入 DAO 时的降级 ====================

    @Test
    fun `未注入 DAO 时持久化缓存整体降级为空_不会 NPE`() {
        // 单测不调 FundAPI.init（没有 Context），整个持久化层应当静默失效，
        // FundAPI 退回纯内存缓存 —— 拿不到 DAO 绝不能让同步链路抛异常。
        assertFalse(FundAPI.hasPersistentNavCache())
        assertNull(runBlocking { FundAPI.readPersistentNav("000306") })
    }
}
