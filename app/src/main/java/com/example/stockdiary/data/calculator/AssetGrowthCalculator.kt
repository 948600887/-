package com.example.stockdiary.data.calculator

import com.example.stockdiary.database.AssetSnapshot
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 「资产增值」口径的计算器。
 *
 * 去掉成本之后，App 不再用 `净资产 - 成本` 表达赚了多少，改为表达
 * **净资产相对历史基准的增加额**。基准取自 asset_snapshots：
 *
 * - 本月新增 = 当前净资产 − 「本月 1 号之前」的最后一条快照净资产
 * - 今年新增 = 当前净资产 − 「今年 1 月 1 号之前」的最后一条快照净资产
 *
 * 没有历史基准时返回 null（界面显示「—」），绝不拿 purchasePrice 反推一个假盈亏。
 *
 * 注意口径：**增值里包含期间新投入的本金**（转入/加仓），因为没有成本数据可剔除。
 */
object AssetGrowthCalculator {

    private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    data class Growth(
        val monthDelta: Double?,
        val yearDelta: Double?,
        /** 是否至少有一个基准可用；false 表示还没有历史快照可对比 */
        val hasBaseline: Boolean
    )

    val EMPTY = Growth(monthDelta = null, yearDelta = null, hasBaseline = false)

    fun fromSnapshots(
        snapshots: List<AssetSnapshot>,
        currentNetAssets: Double,
        today: String = LocalDate.now().format(DATE_FORMAT)
    ): Growth {
        if (snapshots.isEmpty()) return EMPTY

        // 容错：today 必须是合法的 yyyy-MM-dd，否则下方的 take(7)/take(4) 截取会拼出
        // 无意义的基准（如 "abc-01"），导致 monthDelta/yearDelta 静默算错却看不出来。
        // 脏日期时退回"今天"的真实值，保证基准可比较。
        val safeToday = try {
            LocalDate.parse(today, DATE_FORMAT)
            today
        } catch (_: Exception) {
            LocalDate.now().format(DATE_FORMAT)
        }

        // 快照日期是 yyyy-MM-dd，字典序即时间序，直接字符串比较即可
        val monthStart = safeToday.take(7) + "-01"
        val yearStart = safeToday.take(4) + "-01-01"

        val monthBase = snapshots.filter { it.date < monthStart }.maxByOrNull { it.date }
        val yearBase = snapshots.filter { it.date < yearStart }.maxByOrNull { it.date }

        return Growth(
            monthDelta = monthBase?.let { currentNetAssets - it.netAssets },
            yearDelta = yearBase?.let { currentNetAssets - it.netAssets },
            hasBaseline = monthBase != null || yearBase != null
        )
    }
}
