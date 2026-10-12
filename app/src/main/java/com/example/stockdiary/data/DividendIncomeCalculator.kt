package com.example.stockdiary.data

import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.utils.ExchangeRateManager
import com.example.stockdiary.utils.totalValueCny
import java.time.LocalDate
import kotlin.math.pow
import kotlinx.coroutines.CancellationException

/**
 * 股息收益计算器
 * 类似"收息佬"的核心功能：计算持仓股息收入、FIRE覆盖率等
 */
object DividendIncomeCalculator {

    /**
     * 计算单个资产的年化收入（分红+利息）
     */
    /**
     * 计算单个资产的年化收入（分红+利息）。
     *
     * 返回值统一为**人民币口径**：利息随本金币种而来、每股分红随标的币种而来，
     * 组合层要把它们相加再除以总资产，分子分母不同币种的话比值毫无意义。
     */
    fun calculateAnnualIncome(asset: com.example.stockdiary.database.Asset, dividendPerShare: Double = 0.0): Double {
        return if (asset.type == com.example.stockdiary.database.AssetType.BANK_DEPOSIT) {
            // 银行存款：计算利息
            val rate = asset.interestRate ?: 0.0
            asset.totalValueCny * (rate / 100)
        } else {
            // 股票/基金：计算分红（dividendPerShare 为每十股分红）
            if (asset.type != com.example.stockdiary.database.AssetType.STOCK && asset.type != com.example.stockdiary.database.AssetType.FUND) {
                0.0
            } else {
                // 每股分红与标的同币种，折成人民币后再乘份额
                asset.balance * ExchangeRateManager.toCny(dividendPerShare, asset.currency) / 10.0
            }
        }
    }

    /**
     * 计算整个组合的年化总收入（分红+利息）
     */
    fun calculateTotalAnnualIncome(
        assets: List<com.example.stockdiary.database.Asset>,
        dividendsBySymbol: Map<String, Double> = emptyMap()
    ): Double {
        return assets.sumOf { asset ->
            if (asset.type == com.example.stockdiary.database.AssetType.BANK_DEPOSIT) {
                calculateAnnualIncome(asset)
            } else {
                val dividendPerShare = asset.symbol?.let { dividendsBySymbol[it] } ?: 0.0
                calculateAnnualIncome(asset, dividendPerShare)
            }
        }
    }

    /**
     * 计算组合的年化收入率（分红 + 利息）占总资产的比例。
     * 注：App 已去掉成本概念，这里不是「收益率」，是「收入率」。
     */
    fun calculatePortfolioYield(
        assets: List<com.example.stockdiary.database.Asset>,
        dividendsBySymbol: Map<String, Double> = emptyMap()
    ): Double {
        val totalValue = assets.filter { !it.type.isLiability }.sumOf { it.totalValueCny }
        if (totalValue <= 0) return 0.0
        val totalIncome = calculateTotalAnnualIncome(assets, dividendsBySymbol)
        return (totalIncome / totalValue) * 100
    }

    /**
     * 计算单只股票的年度股息收入
     * @param asset 资产
     * @param dividendPerShare 每十股分红
     * @return 年度股息收入
     */
    fun calculateAnnualDividendIncome(asset: Asset, dividendPerShare: Double): Double {
        if (asset.type != AssetType.STOCK && asset.type != AssetType.FUND) return 0.0
        return asset.balance * ExchangeRateManager.toCny(dividendPerShare, asset.currency) / 10.0
    }

    /**
     * 计算股息率（基于当前价）
     * @param dividendPerShare 每十股分红
     * @param currentPrice 当前股价
     * @return 股息率百分比
     */
    fun calculateDividendYieldByPrice(dividendPerShare: Double, currentPrice: Double): Double {
        if (currentPrice <= 0) return 0.0
        return (dividendPerShare / 10.0 / currentPrice) * 100
    }

    /**
     * 计算整个账户的加权平均股息率
     * @param holdings 持仓列表（资产+每十股分红）
     * @return 加权平均股息率
     */
    fun calculatePortfolioDividendYield(
        holdings: List<HoldingDividendInfo>
    ): Double {
        if (holdings.isEmpty()) return 0.0

        val totalValue = holdings.sumOf { it.asset.totalValueCny }
        if (totalValue <= 0) return 0.0

        val totalDividendIncome = holdings.sumOf { 
            it.asset.balance * ExchangeRateManager.toCny(it.annualDividendPerShare, it.asset.currency) / 10.0 
        }

        return (totalDividendIncome / totalValue) * 100
    }

    /**
     * 计算年度总股息收入
     */
    fun calculateTotalAnnualDividend(holdings: List<HoldingDividendInfo>): Double {
        return holdings.sumOf { 
            it.asset.balance * ExchangeRateManager.toCny(it.annualDividendPerShare, it.asset.currency) / 10.0 
        }
    }

    /**
     * 计算月度平均股息收入
     */
    fun calculateMonthlyAverageDividend(holdings: List<HoldingDividendInfo>): Double {
        return calculateTotalAnnualDividend(holdings) / 12
    }

    /**
     * 计算FIRE覆盖率（分红收入覆盖月度生活费的比例）
     * @param monthlyDividend 月度分红收入
     * @param monthlyExpense 月度生活费
     * @return 覆盖率（0-100+）
     */
    fun calculateFireCoverage(monthlyDividend: Double, monthlyExpense: Double): Double {
        if (monthlyExpense <= 0) return 0.0
        return (monthlyDividend / monthlyExpense) * 100
    }

    /**
     * 计算达到FIRE目标所需的投资金额
     * @param monthlyExpense 月度生活费
     * @param targetDividendYield 目标股息率（如4%）
     * @return 所需投资金额
     */
    fun calculateFireTargetAmount(monthlyExpense: Double, targetDividendYield: Double): Double {
        if (targetDividendYield <= 0) return 0.0
        val annualExpense = monthlyExpense * 12
        return annualExpense / (targetDividendYield / 100)
    }

    /**
     * 计算分红再投资收益（复利）
     * @param initialInvestment 初始投资
     * @param annualDividendYield 年化股息率
     * @param years 投资年限
     * @param reinvestRate 再投资比例（0-1）
     * @return 再投资收益预测
     */
    fun calculateReinvestmentProjection(
        initialInvestment: Double,
        annualDividendYield: Double,
        years: Int,
        reinvestRate: Double = 1.0
    ): ReinvestmentProjection {
        if (initialInvestment <= 0 || annualDividendYield <= 0 || years <= 0) {
            return ReinvestmentProjection(0.0, 0.0, 0.0, emptyList())
        }

        val yearlyData = mutableListOf<YearlyReinvestmentData>()
        var currentValue = initialInvestment
        var totalDividendReceived = 0.0

        for (year in 1..years) {
            val annualDividend = currentValue * (annualDividendYield / 100)
            val reinvestedAmount = annualDividend * reinvestRate
            val cashReceived = annualDividend * (1 - reinvestRate)

            totalDividendReceived += annualDividend
            currentValue += reinvestedAmount

            yearlyData.add(YearlyReinvestmentData(
                year = year,
                portfolioValue = currentValue,
                annualDividend = annualDividend,
                reinvestedAmount = reinvestedAmount,
                cashReceived = cashReceived,
                cumulativeDividend = totalDividendReceived
            ))
        }

        return ReinvestmentProjection(
            finalPortfolioValue = currentValue,
            totalDividendReceived = totalDividendReceived,
            totalReturn = currentValue - initialInvestment + totalDividendReceived,
            yearlyData = yearlyData
        )
    }

    /**
     * 预测未来N年的分红收入
     */
    fun projectFutureDividends(
        holdings: List<HoldingDividendInfo>,
        years: Int,
        growthRate: Double = 0.05 // 默认5%年化增长
    ): List<YearlyDividendProjection> {
        val currentAnnualDividend = calculateTotalAnnualDividend(holdings)
        
        return (1..years).map { year ->
            val projectedDividend = currentAnnualDividend * (1 + growthRate).pow(year)
            val cumulativeDividend = if (growthRate == 0.0) {
                currentAnnualDividend * year
            } else {
                currentAnnualDividend *
                    ((1 + growthRate).pow(year) - 1) / growthRate
            }
            YearlyDividendProjection(
                year = year,
                projectedDividend = projectedDividend,
                cumulativeDividend = cumulativeDividend
            )
        }
    }

    /**
     * 获取下一个分红日期（基于历史分红记录）
     */
    /**
     * @param today 参考「今天」。默认取真实当前日期；调用方若已有统一的 today
     *        （如 [DividendProgressCalculator]），务必透传，否则预测结果会依赖真实系统时钟，
     *        既无法单测，也会和调用方自己的口径不一致。
     */
    fun predictNextDividendDate(
        historicalDividends: List<DividendRecord>,
        today: LocalDate = LocalDate.now()
    ): String? {
        if (historicalDividends.isEmpty()) return null

        // 按日期排序
        val sortedDividends = historicalDividends.sortedByDescending { it.exDividendDate }
        val lastDividend = sortedDividends.firstOrNull() ?: return null

        // 简单预测：如果上次分红在6个月内，预测明年同期；否则预测今年同期
        val currentYear = today.year
        
        // 解析上次分红日期
        val lastDate = try {
            val parts = lastDividend.exDividendDate.split("-")
            if (parts.size >= 3) {
                val month = parts[1].toInt()
                val day = parts[2].toInt()
                Pair(month, day)
            } else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

        return lastDate?.let { (month, day) ->
            "$currentYear-${"%02d".format(month)}-${"%02d".format(day)}"
        }
    }
}

/**
 * 持仓股息信息
 */
data class HoldingDividendInfo(
    val asset: Asset,
    val annualDividendPerShare: Double,  // 年度每十股分红
    val dividendYieldByCost: Double,     // 基于成本的股息率
    val dividendYieldByPrice: Double,    // 基于当前价的股息率
    val lastDividendDate: String? = null,
    val nextEstimatedDividendDate: String? = null
)

/**
 * 再投资收益预测
 */
data class ReinvestmentProjection(
    val finalPortfolioValue: Double,      // 最终投资组合价值
    val totalDividendReceived: Double,    // 累计收到分红
    val totalReturn: Double,              // 总收益
    val yearlyData: List<YearlyReinvestmentData>
)

/**
 * 年度再投资数据
 */
data class YearlyReinvestmentData(
    val year: Int,
    val portfolioValue: Double,           // 当年投资组合价值
    val annualDividend: Double,           // 当年分红
    val reinvestedAmount: Double,         // 再投资金额
    val cashReceived: Double,             // 现金分红
    val cumulativeDividend: Double        // 累计分红
)

/**
 * 年度分红预测
 */
data class YearlyDividendProjection(
    val year: Int,
    val projectedDividend: Double,        // 预计分红
    val cumulativeDividend: Double        // 累计分红
)


