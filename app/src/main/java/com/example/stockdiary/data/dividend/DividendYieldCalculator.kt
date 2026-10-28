package com.example.stockdiary.data.dividend

import com.example.stockdiary.common.DateFormats
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException

/**
 * 股息率计算器
 * 用于计算股票的TTM股息率和静态股息率
 */
object DividendYieldCalculator {
    
    /**
     * 计算TTM股息率（最近12个月）
     * @param dividends 分红历史记录列表
     * @param currentPrice 当前股价
     * @return TTM股息率百分比
     */
    fun calculateTTMDividendYield(
        dividends: List<DividendRecord>,
        currentPrice: Double,
        today: LocalDate = LocalDate.now()
    ): Double {
        if (currentPrice <= 0 || dividends.isEmpty()) return 0.0

        // 获取最近12个月的分红记录
        // 含边界：正好 12 个月前那天也算在 TTM 内（isAfter 严格大于会漏掉它）
        val twelveMonthsAgo = today.minusMonths(12)

        val recentDividends = dividends.filter { dividend ->
            try {
                val date = parseDate(dividend.exDividendDate)
                date != null && !date.isBefore(twelveMonthsAgo)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
        }
        
        // 计算每十股分红总和
        val totalDividendPerTenShares = recentDividends.sumOf {
            dividend -> dividend.dividendPerShare.toDoubleOrNull() ?: 0.0
        }
        
        // 股息率 = (每十股分红 ÷ 10 ÷ 股价) × 100%
        return (totalDividendPerTenShares / 10.0 / currentPrice) * 100
    }
    
    /**
     * 计算静态股息率（上一年度）
     * @param lastYearDividend 上一年度每十股分红
     * @param currentPrice 当前股价
     * @return 静态股息率百分比
     */
    fun calculateStaticDividendYield(
        lastYearDividend: Double,
        currentPrice: Double
    ): Double {
        if (currentPrice <= 0 || lastYearDividend <= 0) return 0.0
        return (lastYearDividend / 10.0 / currentPrice) * 100
    }
    
    /**
     * 获取最近12个月（TTM）的年度分红总额（每十股）
     * 正确处理一年内多次分红的情况，将所有 TTM 内的分红累加
     *
     * 窗口是 **[今天-12个月, 今天]** 闭区间：正好落在边界那天要算进去，
     * 用 isAfter 严格大于会把那天漏掉一天。
     */
    fun getTTMDividend(dividends: List<DividendRecord>, today: LocalDate = LocalDate.now()): Double {
        if (dividends.isEmpty()) return 0.0

        val twelveMonthsAgo = today.minusMonths(12)
        return dividends
            .filter { dividend ->
                try {
                    val date = parseDate(dividend.exDividendDate)
                    date != null && !date.isBefore(twelveMonthsAgo) && !date.isAfter(today)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
            }
            .sumOf { it.dividendPerShare.toDoubleOrNull() ?: 0.0 }
    }

    /**
     * 「今年已宣告优先、无则 TTM」的每十股分红，与首页收息进度的口径同源
     * （[com.example.stockdiary.data.dividend.DividendProgressCalculator.naturalYearAnnualIncome]
     * 对股票/基金非货基就是这套逻辑）。
     *
     * 口径：
     * - 除息日落在今年 1/1 ~ 12/31 的记录实额（每十股）累加 → 今年已宣告；
     * - 今年还没有任何宣告（含月初、或今年尚未排定）→ 退回 TTM（[getTTMDividend]），
     *   否则年初「今年已宣告」为 0，会显得这支股票今年一分都不分，反而误导。
     *
     * 资产详情页用这一把尺子，保证单资产卡片上「预计每十股分红」与首页「今年预计」
     * 严格同源、逐资产相等。
     */
    fun getDeclaredOrTTMPerTenShares(
        dividends: List<DividendRecord>,
        today: LocalDate = LocalDate.now()
    ): Double {
        if (dividends.isEmpty()) return 0.0
        val year = today.year
        val yearStart = "$year-01-01"
        val yearEnd = "$year-12-31"
        // 必须配上界：只写 >= yearStart 会把「明年及以后除息」也算进今年
        // （字符串比较下 "2027-03-15" >= "2026-01-01" 为真），跨年后重复计入两个年度。
        val thisYear = dividends.filter { dividend ->
            val exDate = dividend.exDividendDate.take(10)
            exDate.length == 10 && exDate >= yearStart && exDate <= yearEnd
        }
        if (thisYear.isNotEmpty()) {
            return thisYear.sumOf { it.dividendPerShare.toDoubleOrNull() ?: 0.0 }
        }
        return getTTMDividend(dividends, today)
    }

    /**
     * 获取上一年度分红
     * @param dividends 分红历史记录列表
     * @return 上一年度每股分红，如无则取最近一年的分红
     */
    fun getLastYearDividend(dividends: List<DividendRecord>): Double {
        if (dividends.isEmpty()) return 0.0
        
        val currentYear = LocalDate.now().year
        val lastYear = currentYear - 1
        
        // 先尝试获取上一年度的分红
        val lastYearDividend = dividends
            .filter { dividend ->
                try {
                    val date = parseDate(dividend.exDividendDate)
                    val year = getYearFromDate(date)
                    year == lastYear
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
            }
            .sumOf { it.dividendPerShare.toDoubleOrNull() ?: 0.0 }
        
        if (lastYearDividend > 0) {
            return lastYearDividend
        }
        
        // 如果上一年没有分红，使用最近一年的分红数据
        val yearlyDividends = dividends.groupBy { dividend ->
            try {
                val date = parseDate(dividend.exDividendDate)
                getYearFromDate(date)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                0
            }
        }.filterKeys { it > 0 }
        
        if (yearlyDividends.isEmpty()) return 0.0
        
        val mostRecentYear = yearlyDividends.keys.maxOrNull() ?: 0
        return yearlyDividends[mostRecentYear]?.sumOf { it.dividendPerShare.toDoubleOrNull() ?: 0.0 } ?: 0.0
    }
    
    /**
     * 计算平均年化股息率
     * @param dividends 分红历史记录列表
     * @param currentPrice 当前股价
     * @return 平均年化股息率百分比
     */
    fun calculateAvgDividendYield(
        dividends: List<DividendRecord>,
        currentPrice: Double
    ): Double {
        if (currentPrice <= 0 || dividends.isEmpty()) return 0.0
        
        // 按年份分组计算每年分红
        val yearlyDividends = dividends.groupBy { dividend ->
            try {
                getYearFromDate(parseDate(dividend.exDividendDate))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                0
            }
        }
        
        // 过滤掉无效年份（0）的数据
        val validYearlyDividends = yearlyDividends.filterKeys { it > 0 }
        if (validYearlyDividends.isEmpty()) return 0.0
        
        // 计算平均每年分红
        val avgYearlyDividend = validYearlyDividends.values
            .map { yearDividends -> 
                yearDividends.sumOf { dividend ->
                    dividend.dividendPerShare.toDoubleOrNull() ?: 0.0
                }
            }
            .average()
        
        return (avgYearlyDividend / 10.0 / currentPrice) * 100
    }
    
    /**
     * 解析日期字符串
     */
    private fun parseDate(dateStr: String): LocalDate? {
        if (dateStr.isBlank()) return null
        
        val formats = listOf(
            DateFormats.ISO_DATE_FORMATTER,
            DateFormats.SLASH_DATE_FORMATTER,
            DateFormats.CN_DATE_FORMATTER,
            DateFormats.COMPACT_DATE_FORMATTER
        )
        
        for (format in formats) {
            try {
                return LocalDate.parse(dateStr, format)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 尝试下一种格式
            }
        }
        
        return null
    }
    
    /**
     * 从日期获取年份
     */
    private fun getYearFromDate(date: LocalDate?): Int {
        return date?.year ?: 0
    }
}

