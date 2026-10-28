package com.example.stockdiary.data.calculator

import com.example.stockdiary.database.AssetSnapshot
import com.example.stockdiary.utils.toLocalDate
import java.time.Instant
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 快照排序比较器：**日期优先**（date 字段），同日再按创建时刻（timestamp）。
 *
 * 为什么不能只按 timestamp：timestamp 是「这一行什么时候写进库的」，date 才是
 * 「这一天账目」的口径，两者会错位——2026-10-18 之前的 fillMissingSnapshots
 * 逐日回填补出来的快照，date 是被补的那天、timestamp 是补的时刻
 * （用户库里就有这种行：选「1周」放出 10 天数据）。只按 timestamp 排序会把
 * 10/15 的行排到 10/16 之后，图表连线回折、统计首末取错。
 * 界面各处（X 轴刻度、期间增值起止、日历、资产总览）全是 date 口径，
 * 排序必须与它一致。
 *
 * date 解析不了的脏数据不丢弃：退回用创建时刻推导的日期，仍参与排序。
 */
internal val SNAPSHOT_ORDER: Comparator<AssetSnapshot> =
    compareBy({ it.effectiveEpochDay() }, { it.timestamp })

/** 快照的排序用日期：date 解析失败时退回创建时刻推导的日期（脏数据不静默丢弃） */
internal fun AssetSnapshot.effectiveEpochDay(): Long =
    date.toLocalDate()?.toEpochDay()
        ?: Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()

/**
 * 历史页风险卡片统计。
 *
 * @property coveredDays 首末快照相隔天数（同一天多份为 0）；缺口越大，「日均」越依赖它
 * @property avgIntervalDays 相邻快照的平均间隔天数，年化波动率的分母
 */
data class SnapshotStats(
    val maxValue: Double,
    val minValue: Double,
    val totalSnapshots: Int,
    /** 最大回撤（百分比）：峰谷口径，与快照间隔无关，缺口不影响 */
    val maxDrawdown: Double,
    /**
     * 日均复合增长（百分比）：(末/首)^(1/覆盖天数) - 1，不是算术平均。
     * 首期净资产 ≤ 0 时增长率无数学意义，为 null（界面显示「—」）——
     * 显示 0.00% 会被读成「零增长」，与正值日均增值额自相矛盾
     */
    val avgDailyReturnPercent: Double?,
    /** 日均增值额：(末净值 - 首净值) / 覆盖天数 */
    val avgDailyReturnAmount: Double,
    /**
     * 年化波动率（百分比）：每期收益率样本标准差 × sqrt(实际年天数 / 平均间隔天数)。
     * 样本不足（期间收益率 < 2 个，或平均间隔为 0）时无法年化，为 null（界面显示「—」），
     * 而不是给 0%——0.00% 会被读成「零波动」，与实际「算不出」矛盾。
     */
    val volatility: Double?,
    val coveredDays: Int,
    val avgIntervalDays: Double
)

/**
 * 月度收益（按每月最后一条快照算环比）。
 *
 * @property monthsSpanned 这根柱子实际跨界的月数：1 = 就是一个月；
 *   缺了一个月的快照时，4 月那一根 = 4 月底 - 2 月底，跨 2 个月
 */
data class MonthlyReturn(
    val month: YearMonth,
    val returnAmount: Double,
    val returnRate: Double,
    val monthsSpanned: Int
)

/**
 * 「资产分析」历史统计算法。
 *
 * 本项目快照序列**不保证等间隔连续**：`fillMissingSnapshots()` 只补今天一条，
 * 不逐日回填（逐日补出来的净值是直线，没有意义），所以缺快照的日子是常态。
 * 这里所有「日均 / 年化」指标都按**真实天数**归一，绝不做「每份快照 == 一天」的假设。
 *
 * 纯函数、无 Android 依赖，可被 JVM 单测直接调用。
 */
object SnapshotAnalyzer {

    /**
     * 统计一段时间内的快照。
     *
     * 入参假定已按时间升序，但调用方不能全信（过滤/去重会重排），函数内自己
     * `sortedWith(SNAPSHOT_ORDER)` 一道。日期非法的快照直接跳过（兼容历史脏数据），
     * 跳过之后不足 2 份返回 null。
     */
    fun analyze(snapshots: List<AssetSnapshot>): SnapshotStats? {
        // 解析日期与快照绑定在一起，避免同一个字符串解析两遍
        val valid = snapshots
            .sortedWith(SNAPSHOT_ORDER)
            .mapNotNull { snapshot -> snapshot.date.toLocalDate()?.let { date -> snapshot to date } }
        if (valid.size < 2) return null

        val values = valid.map { it.first.netAssets }
        val maxValue = values.max()
        val minValue = values.min()

        var maxDrawdown = 0.0
        var peak = values.first()
        for (value in values) {
            if (value > peak) peak = value
            val drawdown = if (peak > 0) ((peak - value) / peak) * 100 else 0.0
            if (drawdown > maxDrawdown) maxDrawdown = drawdown
        }

        val firstDate = valid.first().second
        val lastDate = valid.last().second
        val coveredDays = ChronoUnit.DAYS.between(firstDate, lastDate).toInt()
        // 同一天多份快照时 coveredDays = 0，退化为按期数归一，保证分母 >= 1
        val dayDenominator = if (coveredDays > 0) coveredDays.toDouble() else (values.size - 1).toDouble()

        val firstValue = values.first()
        val lastValue = values.last()
        val avgDailyReturnAmount = (lastValue - firstValue) / dayDenominator
        val avgDailyReturnPercent = when {
            // 起点非正：增长率无数学意义，给 null（界面显示「—」）而不是 0——
            // 0.00% 会被读成「零增长」，与正值日均增值额自相矛盾
            firstValue <= 0.0 -> null
            // 中途亏到 0 或以下：负基数开不了分数次幂，按全损处理
            lastValue <= 0.0 -> -100.0
            else -> ((lastValue / firstValue).pow(1.0 / dayDenominator) - 1.0) * 100
        }

        // 年化波动率：每期收益率 r_i = v_i / v_{i-1} - 1（上一期非正的期间不产收益率），
        // 取样本标准差（n-1），再按平均间隔换算到年：× sqrt(365 / avgIntervalDays)
        val intervalDays = mutableListOf<Double>()
        val periodReturns = mutableListOf<Double>()
        for (i in 1 until valid.size) {
            val prev = valid[i - 1]
            val curr = valid[i]
            intervalDays += ChronoUnit.DAYS.between(prev.second, curr.second).toDouble()
            if (prev.first.netAssets > 0) {
                periodReturns += curr.first.netAssets / prev.first.netAssets - 1.0
            }
        }
        val avgIntervalDays = if (intervalDays.isNotEmpty()) intervalDays.average() else 0.0
        val volatility = if (periodReturns.size >= 2 && avgIntervalDays > 0) {
            val mean = periodReturns.average()
            val variance = periodReturns.sumOf { (it - mean) * (it - mean) } / (periodReturns.size - 1)
            // 年化因子按「实际年天数」而非固定 365：跨闰年边界时端点年份取平均，
            // 单年区间直接取该年天数（闰年 366），避免 -365 天式的一天偏差
            val daysInYear = (Year.of(firstDate.year).length() + Year.of(lastDate.year).length()) / 2.0
            sqrt(variance) * sqrt(daysInYear / avgIntervalDays) * 100
        } else null

        return SnapshotStats(
            maxValue = maxValue,
            minValue = minValue,
            totalSnapshots = valid.size,
            maxDrawdown = maxDrawdown,
            avgDailyReturnPercent = avgDailyReturnPercent,
            avgDailyReturnAmount = avgDailyReturnAmount,
            volatility = volatility,
            coveredDays = coveredDays,
            avgIntervalDays = avgIntervalDays
        )
    }

    /**
     * 丢掉开头「正资产为 0」的快照。
     *
     * 首日正资产（totalAssets）为 0 的快照不是真实数据点：它是用户在没有任何资产时
     * 打开 App、fillMissingSnapshots 自动补的空快照。留着它：日均增值额被空窗稀释、
     * 增幅/复合增长无定义、图表从 0 起跳造出一根假陡坡。整段分析从「有资产的第二天」起算。
     *
     * 只丢**开头连续**的：中途归零是真实数据（真卖光了），不能丢。
     * 丢完之后不足 2 份时原样返回——宁可显示带「—」的统计，也不要让整页卡片消失。
     */
    fun dropLeadingEmptySnapshots(snapshots: List<AssetSnapshot>): List<AssetSnapshot> {
        if (snapshots.size < 2) return snapshots
        var start = 0
        while (start < snapshots.size && snapshots[start].totalAssets == 0.0) {
            start++
        }
        val remaining = snapshots.drop(start)
        return if (remaining.size >= 2) remaining else snapshots
    }

    /**
     * 按「每月最后一条快照」算环比收益。
     *
     * 缺月时（如 3 月无快照）4 月那一根跨了 2 个月，用 [MonthlyReturn.monthsSpanned]
     * 标注出来，避免把两个月的变化当成一个月的收益。日期非法的快照跳过。
     */
    fun calculateMonthlyReturns(snapshots: List<AssetSnapshot>): List<MonthlyReturn> {
        if (snapshots.size < 2) return emptyList()
        val monthMap = linkedMapOf<YearMonth, AssetSnapshot>()
        for (snapshot in snapshots.sortedWith(SNAPSHOT_ORDER)) {
            val date = snapshot.date.toLocalDate() ?: continue
            // 同月后者覆盖前者：每月保留最后一条
            monthMap[YearMonth.from(date)] = snapshot
        }
        if (monthMap.size < 2) return emptyList()
        val entries = monthMap.entries.toList()
        val result = mutableListOf<MonthlyReturn>()
        for (i in 1 until entries.size) {
            val prev = entries[i - 1].value
            val curr = entries[i].value
            val returnAmount = curr.netAssets - prev.netAssets
            val returnRate = if (prev.netAssets > 0) (returnAmount / prev.netAssets) * 100 else 0.0
            // 手算年月差：连续月 = 1，缺一个月 = 2。不用 ChronoUnit.MONTHS.between 的
            // 原因是设计里的 "+1" 口径会让连续月算出 2，与「1 表示就是一个月」矛盾，
            // 手算没有歧义，也不用赌 YearMonth 上 MONTHS 单位的行为。
            val monthsSpanned = (entries[i].key.year - entries[i - 1].key.year) * 12 +
                (entries[i].key.monthValue - entries[i - 1].key.monthValue)
            result.add(MonthlyReturn(entries[i].key, returnAmount, returnRate, monthsSpanned))
        }
        return result
    }
}
