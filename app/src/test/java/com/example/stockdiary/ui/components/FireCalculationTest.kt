package com.example.stockdiary.ui.components

import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import org.junit.Assert.assertEquals
import org.junit.Test

class FireCalculationTest {

    @Test
    fun 有负债时分母使用净资产_负债被扣减() {
        // 股票 100 万，贷款 30 万 → 净资产应为 70 万，而非毛资产 130 万
        val assets = listOf(
            Asset(name = "股票A", type = AssetType.STOCK, symbol = "sh600000",
                account = "账户", balance = 100.0, currentPrice = 10000.0),
            Asset(name = "房贷", type = AssetType.LOAN, account = "账户", balance = 300000.0)
        )
        val metrics = computeFireMetrics(assets, realAnnualIncome = 0.0, monthlyExpense = 5000.0)
        assertEquals(700000.0, metrics.netWorth, 1e-6)
    }

    @Test
    fun 无任何负债时净资产等于毛资产() {
        val assets = listOf(
            Asset(name = "股票A", type = AssetType.STOCK, symbol = "sh600000",
                account = "账户", balance = 100.0, currentPrice = 10000.0),
            Asset(name = "基金B", type = AssetType.FUND,
                account = "账户", balance = 50.0, currentPrice = 2000.0)
        )
        val metrics = computeFireMetrics(assets, realAnnualIncome = 0.0, monthlyExpense = 5000.0)
        assertEquals(1100000.0, metrics.netWorth, 1e-6)
    }

    @Test
    fun 负余额加密货币在资产侧自动扣减_不计入负债() {
        // 股票 100 万，借币 -1 个（单价 5 万）→ 净资产 95 万，
        // 负 crypto 通过 balance*price 负值在「非负债」侧扣减，不是走负债侧
        val assets = listOf(
            Asset(name = "股票A", type = AssetType.STOCK, symbol = "sh600000",
                account = "账户", balance = 100.0, currentPrice = 10000.0),
            Asset(name = "USDT借币", type = AssetType.CRYPTO, symbol = "USDT",
                account = "币安", balance = -1.0, currentPrice = 50000.0)
        )
        val metrics = computeFireMetrics(assets, realAnnualIncome = 0.0, monthlyExpense = 5000.0)
        assertEquals(950000.0, metrics.netWorth, 1e-6)
    }

    @Test
    fun 收益率为分子除以净资产() {
        // 净资产 100 万，年分红 5 万 → 年化收益率 5%
        val assets = listOf(
            Asset(name = "股票A", type = AssetType.STOCK, symbol = "sh600000",
                account = "账户", balance = 100.0, currentPrice = 10000.0)
        )
        val metrics = computeFireMetrics(assets, realAnnualIncome = 50000.0, monthlyExpense = 5000.0)
        assertEquals(5.0, metrics.realYield, 1e-6)
        // 月支出 5000、月分红 50000/12 ≈ 4166.67 → 覆盖率 ≈ 83.33%
        assertEquals(83.333, metrics.fireCoverage, 1e-3)
        // FIRE 目标 = 月支出*12 / 年化收益率 = 60000 / 0.05 = 1,200,000
        assertEquals(1200000.0, metrics.fireTarget, 1e-6)
        // 进度 = 净资产 / 目标 = 1,000,000 / 1,200,000 ≈ 83.33%
        assertEquals(83.333, metrics.fireProgress, 1e-3)
    }

    @Test
    fun 有贷款时收益率为分子除以净资产_而非毛资产() {
        // 毛资产 130 万（股票100万+贷款30万被当成正值相加若用旧逻辑），
        // 净资产 70 万。年分红 7 万 → 用净资产收益率应为 10%，不是 ~5.38%
        val assets = listOf(
            Asset(name = "股票A", type = AssetType.STOCK, symbol = "sh600000",
                account = "账户", balance = 100.0, currentPrice = 10000.0),
            Asset(name = "房贷", type = AssetType.LOAN, account = "账户", balance = 300000.0)
        )
        val metrics = computeFireMetrics(assets, realAnnualIncome = 70000.0, monthlyExpense = 5000.0)
        assertEquals(10.0, metrics.realYield, 1e-6)
    }
}
