package com.example.stockdiary.data

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
        currentPrice: Double
    ): Double {
        if (currentPrice <= 0 || dividends.isEmpty()) return 0.0
        
        // 获取最近12个月的分红记录
        val twelveMonthsAgo = LocalDate.now().minusMonths(12)
        
        val recentDividends = dividends.filter { dividend ->
            try {
                val date = parseDate(dividend.exDividendDate)
                date != null && date.isAfter(twelveMonthsAgo)
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
     */
    fun getTTMDividend(dividends: List<DividendRecord>): Double {
        if (dividends.isEmpty()) return 0.0
        
        val twelveMonthsAgo = LocalDate.now().minusMonths(12)
        return dividends
            .filter { dividend ->
                try {
                    val date = parseDate(dividend.exDividendDate)
                    date != null && date.isAfter(twelveMonthsAgo)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
            }
            .sumOf { it.dividendPerShare.toDoubleOrNull() ?: 0.0 }
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
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("yyyy年MM月dd日"),
            DateTimeFormatter.ofPattern("yyyyMMdd")
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

