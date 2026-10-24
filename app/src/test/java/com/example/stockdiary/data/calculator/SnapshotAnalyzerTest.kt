package com.example.stockdiary.data.calculator

import com.example.stockdiary.database.AssetSnapshot
import com.example.stockdiary.utils.toTimestamp
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow
import kotlin.math.sqrt

class SnapshotAnalyzerTest {

    private fun snapshot(date: String, netAssets: Double) = AssetSnapshot(
        date = date,
        timestamp = date.toTimestamp(),
        totalAssets = netAssets,
        totalLiabilities = 0.0,
        netAssets = netAssets
    )

    /** 从 startDate 起连续 days+1 天、步长 step 的等间隔日快照 */
    private fun dailySnapshots(
        startDate: String,
        days: Int,
        startValue: Double,
        step: Double
    ): List<AssetSnapshot> {
        val start = LocalDate.parse(startDate)
        return (0..days).map { i ->
            snapshot(start.plusDays(i.toLong()).toString(), startValue + i * step)
        }
    }

    @Test
    fun `等间隔日快照的日均增值额等于总增量除以天数`() {
        // 11 份快照跨 10 天，每天 +100
        val snapshots = dailySnapshots("2024-03-01", days = 10, startValue = 10_000.0, step = 100.0)
        val stats = SnapshotAnalyzer.analyze(snapshots)!!

        assertEquals(11, stats.totalSnapshots)
        assertEquals(10, stats.coveredDays)
        // (11000 - 10000) / 10
        assertEquals(100.0, stats.avgDailyReturnAmount, 1e-6)
        assertEquals(1.0, stats.avgIntervalDays, 1e-6)
    }

    @Test
    fun `等间隔日快照的日均复合增长是CAGR不是算术平均`() {
        val snapshots = dailySnapshots("2024-03-01", days = 10, startValue = 10_000.0, step = 100.0)
        val stats = SnapshotAnalyzer.analyze(snapshots)!!

        // 手算：(11000/10000)^(1/10) - 1
        val expectedCagr = ((11_000.0 / 10_000.0).pow(1.0 / 10.0) - 1.0) * 100
        assertEquals(expectedCagr, stats.avgDailyReturnPercent!!, 1e-9)
        // 对照：算术平均会把这些天算成约 +0.96%，CAGR 与它接近但绝不等同；
        // 100→110→100 这种往返场景差距最大（见下面的往返用例）
        assertTrue(stats.avgDailyReturnPercent!! > 0.9 && stats.avgDailyReturnPercent!! < 1.0)
    }

    @Test
    fun `往返行情下CAGR为0而算术平均会虚高`() {
        // 100 → 110 → 100：名义日均增幅为 0，但相邻变化率的算术平均是 +0.45%
        val snapshots = listOf(
            snapshot("2024-03-01", 100.0),
            snapshot("2024-03-02", 110.0),
            snapshot("2024-03-03", 100.0)
        )
        val stats = SnapshotAnalyzer.analyze(snapshots)!!
        assertEquals(0.0, stats.avgDailyReturnPercent!!, 1e-9)
    }

    @Test
    fun `缺口场景两份快照相隔10天按10天归一`() {
        // 旧实现会把这份 10 天的变化当成「1 天」：日均增值额 = 1000/1 = 1000
        val snapshots = listOf(
            snapshot("2024-03-01", 10_000.0),
            snapshot("2024-03-11", 11_000.0)
        )
        val stats = SnapshotAnalyzer.analyze(snapshots)!!

        assertEquals(10, stats.coveredDays)
        assertEquals(100.0, stats.avgDailyReturnAmount, 1e-6)
        assertEquals(10.0, stats.avgIntervalDays, 1e-6)
        // 日均复合增长同样按 10 天折算
        val expectedCagr = ((11_000.0 / 10_000.0).pow(1.0 / 10.0) - 1.0) * 100
        assertEquals(expectedCagr, stats.avgDailyReturnPercent!!, 1e-9)
    }

    @Test
    fun `同一天多份快照退化为按期数且不出NaN`() {
        val snapshots = listOf(
            snapshot("2024-03-01", 100.0),
            snapshot("2024-03-01", 110.0),
            snapshot("2024-03-01", 121.0)
        )
        val stats = SnapshotAnalyzer.analyze(snapshots)!!

        assertEquals(0, stats.coveredDays)
        // coveredDays = 0 → 分母退化为快照数 - 1 = 2
        assertEquals(10.5, stats.avgDailyReturnAmount, 1e-6)
        // (121/100)^(1/2) - 1 = 10%
        assertEquals(10.0, stats.avgDailyReturnPercent!!, 1e-6)
        // 全同期 → 平均间隔 0 → 年化无定义，给 0 而不是 NaN/Infinity
        assertEquals(0.0, stats.avgIntervalDays, 1e-9)
        assertEquals(0.0, stats.volatility, 1e-9)
        assertFalse(stats.avgDailyReturnAmount.isNaN())
        assertFalse(stats.avgDailyReturnPercent?.isNaN() ?: false)
        assertFalse(stats.volatility.isNaN())
    }

    @Test
    fun `首值非正时日均复合增长为null但增值额仍按天归一`() {
        val zeroStart = SnapshotAnalyzer.analyze(
            listOf(snapshot("2024-03-01", 0.0), snapshot("2024-03-11", 100.0))
        )!!
        // 首期 ≤0：增长率无数学意义，给 null（界面显示「—」）。
        // 曾经给 0.0——「0.00% 增长」与「每天 +10」并排显示，自相矛盾
        assertNull(zeroStart.avgDailyReturnPercent)
        assertEquals(10.0, zeroStart.avgDailyReturnAmount, 1e-6)

        val negativeStart = SnapshotAnalyzer.analyze(
            listOf(snapshot("2024-03-01", -500.0), snapshot("2024-03-11", 100.0))
        )!!
        assertNull(negativeStart.avgDailyReturnPercent)
        assertEquals(60.0, negativeStart.avgDailyReturnAmount, 1e-6)
    }

    @Test
    fun `年化波动率按平均间隔乘以根号365`() {
        // 每期间隔 10 天，收益率 +10% / -10%
        val snapshots = listOf(
            snapshot("2024-03-01", 100.0),
            snapshot("2024-03-11", 110.0),
            snapshot("2024-03-21", 99.0)
        )
        val stats = SnapshotAnalyzer.analyze(snapshots)!!

        assertEquals(10.0, stats.avgIntervalDays, 1e-6)
        // 样本标准差：sqrt(((0.1-0)^2 + (-0.1-0)^2) / (2-1)) = sqrt(0.02)
        val expected = sqrt(0.02) * sqrt(365.0 / 10.0) * 100
        assertEquals(expected, stats.volatility, 1e-9)
        // 对照旧口径：未年化的期间标准差只有 10%（population stddev of ±10%）
        assertTrue(stats.volatility > 80.0)
    }

    @Test
    fun `最大回撤保持峰谷口径`() {
        val snapshots = listOf(
            snapshot("2024-03-01", 100.0),
            snapshot("2024-03-02", 120.0),
            snapshot("2024-03-03", 60.0),
            snapshot("2024-03-04", 90.0)
        )
        val stats = SnapshotAnalyzer.analyze(snapshots)!!
        // 峰 120 → 谷 60 = 50%
        assertEquals(50.0, stats.maxDrawdown, 1e-6)
        assertEquals(120.0, stats.maxValue, 1e-6)
        assertEquals(60.0, stats.minValue, 1e-6)
    }

    @Test
    fun `少于两份快照返回null`() {
        assertNull(SnapshotAnalyzer.analyze(emptyList()))
        assertNull(SnapshotAnalyzer.analyze(listOf(snapshot("2024-03-01", 100.0))))
    }

    @Test
    fun `乱序输入函数内自排序结果一致`() {
        val ordered = dailySnapshots("2024-03-01", days = 5, startValue = 1_000.0, step = 50.0)
        val shuffled = ordered.reversed()
        assertEquals(SnapshotAnalyzer.analyze(ordered), SnapshotAnalyzer.analyze(shuffled))
    }

    @Test
    fun `含非法日期的快照被跳过且不抛异常`() {
        val snapshots = listOf(
            snapshot("2024-03-01", 100.0),
            snapshot("not-a-date", 999.0),
            snapshot("2024-03-11", 110.0)
        )
        val stats = SnapshotAnalyzer.analyze(snapshots)!!

        assertEquals(2, stats.totalSnapshots)
        assertEquals(10, stats.coveredDays)
        assertEquals(1.0, stats.avgDailyReturnAmount, 1e-6)
        // 脏数据不参与最大/最小值
        assertEquals(110.0, stats.maxValue, 1e-6)
        assertEquals(100.0, stats.minValue, 1e-6)
    }

    @Test
    fun `全部快照日期非法时返回null`() {
        assertNull(
            SnapshotAnalyzer.analyze(
                listOf(snapshot("bad", 1.0), snapshot("worse", 2.0))
            )
        )
    }

    // ===== 首日空快照剔除 =====

    @Test
    fun `首日正资产为0的快照被忽略分析从第二日算起`() {
        val snapshots = listOf(
            snapshot("2024-03-01", 0.0),      // 空快照：无任何资产时 App 自动补的
            snapshot("2024-03-02", 10_000.0),
            snapshot("2024-03-03", 11_000.0)
        )
        val cleaned = SnapshotAnalyzer.dropLeadingEmptySnapshots(snapshots)
        assertEquals(2, cleaned.size)
        assertEquals("2024-03-02", cleaned.first().date)

        // 剔除后统计从 10000 起算：日均增值额 = 1000/1，不再是 11000/2 被空窗稀释
        val stats = SnapshotAnalyzer.analyze(cleaned)!!
        assertEquals(1_000.0, stats.avgDailyReturnAmount, 1e-6)
        assertEquals(1, stats.coveredDays)
    }

    @Test
    fun `开头连续多个空快照全部剔除`() {
        val snapshots = listOf(
            snapshot("2024-03-01", 0.0),
            snapshot("2024-03-02", 0.0),
            snapshot("2024-03-03", 5_000.0),
            snapshot("2024-03-04", 6_000.0)
        )
        val cleaned = SnapshotAnalyzer.dropLeadingEmptySnapshots(snapshots)
        assertEquals(2, cleaned.size)
        assertEquals("2024-03-03", cleaned.first().date)
    }

    @Test
    fun `中途归零是真实数据不剔除`() {
        val snapshots = listOf(
            snapshot("2024-03-01", 10_000.0),
            snapshot("2024-03-02", 0.0),   // 真卖光了：必须保留
            snapshot("2024-03-03", 8_000.0)
        )
        val cleaned = SnapshotAnalyzer.dropLeadingEmptySnapshots(snapshots)
        assertEquals(3, cleaned.size)
        assertEquals(snapshots, cleaned)
    }

    @Test
    fun `首日非零时原样返回`() {
        val snapshots = listOf(
            snapshot("2024-03-01", 100.0),
            snapshot("2024-03-02", 110.0)
        )
        assertEquals(snapshots, SnapshotAnalyzer.dropLeadingEmptySnapshots(snapshots))
    }

    @Test
    fun `剔除后不足2份时原样返回不丢数据`() {
        // 只有「空快照 + 一条」：剔了就剩 1 份，统计整卡消失，不如留着显示「—」
        val snapshots = listOf(
            snapshot("2024-03-01", 0.0),
            snapshot("2024-03-02", 100.0)
        )
        assertEquals(snapshots, SnapshotAnalyzer.dropLeadingEmptySnapshots(snapshots))
    }

    @Test
    fun `全空快照与不足两份的输入原样返回`() {
        val allEmpty = listOf(
            snapshot("2024-03-01", 0.0),
            snapshot("2024-03-02", 0.0)
        )
        assertEquals(allEmpty, SnapshotAnalyzer.dropLeadingEmptySnapshots(allEmpty))
        assertEquals(emptyList<AssetSnapshot>(), SnapshotAnalyzer.dropLeadingEmptySnapshots(emptyList()))
        val single = listOf(snapshot("2024-03-01", 0.0))
        assertEquals(single, SnapshotAnalyzer.dropLeadingEmptySnapshots(single))
    }

    // ===== 月度收益 =====

    @Test
    fun `月度收益连续月monthsSpanned为1且取每月最后一条`() {
        val snapshots = listOf(
            snapshot("2024-01-15", 100.0),
            snapshot("2024-01-31", 105.0),  // 1 月最后一条
            snapshot("2024-02-15", 110.0),
            snapshot("2024-02-29", 120.0)   // 2 月最后一条
        )
        val returns = SnapshotAnalyzer.calculateMonthlyReturns(snapshots)

        assertEquals(1, returns.size)
        val feb = returns[0]
        assertEquals(YearMonth.of(2024, 2), feb.month)
        // 120 - 105：每月最后一条算环比，不是拿月初
        assertEquals(15.0, feb.returnAmount, 1e-6)
        assertEquals(1, feb.monthsSpanned)
    }

    @Test
    fun `月度收益缺月时monthsSpanned为2`() {
        // 3 月无快照：4 月那根 = 4 月底 - 2 月底，两个月的变化被标成「4月」
        val snapshots = listOf(
            snapshot("2024-01-31", 100.0),
            snapshot("2024-02-29", 110.0),
            snapshot("2024-04-30", 121.0)
        )
        val returns = SnapshotAnalyzer.calculateMonthlyReturns(snapshots)

        assertEquals(2, returns.size)
        val feb = returns[0]
        assertEquals(YearMonth.of(2024, 2), feb.month)
        assertEquals(10.0, feb.returnAmount, 1e-6)
        assertEquals(1, feb.monthsSpanned)

        val apr = returns[1]
        assertEquals(YearMonth.of(2024, 4), apr.month)
        assertEquals(11.0, apr.returnAmount, 1e-6)
        assertEquals(10.0, apr.returnRate, 1e-6)  // 11 / 110
        assertEquals(2, apr.monthsSpanned)
    }

    @Test
    fun `月度收益跨年缺口按整月差标注`() {
        val snapshots = listOf(
            snapshot("2023-11-30", 100.0),
            snapshot("2024-02-29", 130.0)
        )
        val returns = SnapshotAnalyzer.calculateMonthlyReturns(snapshots)

        assertEquals(1, returns.size)
        assertEquals(YearMonth.of(2024, 2), returns[0].month)
        // 2023-11 → 2024-02 跨 3 个月
        assertEquals(3, returns[0].monthsSpanned)
        assertEquals(30.0, returns[0].returnAmount, 1e-6)
    }

    @Test
    fun `月度收益跳过非法日期快照`() {
        val snapshots = listOf(
            snapshot("bad-date", 100.0),
            snapshot("2024-01-31", 100.0),
            snapshot("2024-02-29", 110.0)
        )
        val returns = SnapshotAnalyzer.calculateMonthlyReturns(snapshots)

        assertEquals(1, returns.size)
        assertEquals(YearMonth.of(2024, 2), returns[0].month)
        assertEquals(10.0, returns[0].returnAmount, 1e-6)
    }

    @Test
    fun `同一个月内多份快照只产生一根柱子`() {
        val snapshots = listOf(
            snapshot("2024-01-10", 100.0),
            snapshot("2024-01-20", 105.0),
            snapshot("2024-01-31", 108.0)
        )
        assertTrue(SnapshotAnalyzer.calculateMonthlyReturns(snapshots).isEmpty())
    }

    @Test
    fun `统计按日期排序_幽灵快照不靠后`() {
        // 回填补出来的行：date 是 10-15，timestamp 却是 10-17 的。
        // 只按 timestamp 排序会把它当成最新一期，首末取值全错
        val phantom = AssetSnapshot(
            date = "2026-10-15",
            timestamp = "2026-10-17".toTimestamp(),
            totalAssets = 100.0, totalLiabilities = 0.0, netAssets = 100.0
        )
        val snapshots = listOf(
            phantom,
            snapshot("2026-10-16", 110.0),
            snapshot("2026-10-17", 121.0)
        )

        val stats = SnapshotAnalyzer.analyze(snapshots)!!

        // 首期应是 10-15 的 100（幽灵行按它的 date 归位），末期 10-17 的 121，
        // 覆盖 2 天 → 日均增值额 (121-100)/2 = 10.5
        assertEquals(10.5, stats.avgDailyReturnAmount, 1e-6)
        assertEquals(2, stats.coveredDays)
        assertEquals(121.0, stats.maxValue, 1e-6)
    }
}
