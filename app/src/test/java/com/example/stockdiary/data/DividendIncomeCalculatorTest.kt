package com.example.stockdiary.data

import com.example.stockdiary.data.dividend.DividendIncomeCalculator
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [DividendIncomeCalculator] 是纯函数（仅依赖 java.time / 数学），无 Android / Context 依赖，
 * 直接 JVM 单测。覆盖「去成本后」的年化收入口径：
 *  - 银行存款按利率算利息（走 [Asset.totalValue]，而非市值）
 *  - 股票/基金按「每十股分红 × 持仓 ÷ 10」算分红
 *  - 其它类型（含虚拟货币、负债）不计入分红
 *  - 组合收入率以「剔除负债后的总资产」为分母
 */
class DividendIncomeCalculatorTest {

    private fun stock(symbol: String, balance: Double, currentPrice: Double? = null) = Asset(
        name = symbol,
        type = AssetType.STOCK,
        symbol = symbol,
        account = "默认",
        balance = balance,
        currentPrice = currentPrice
    )

    private fun fund(symbol: String, balance: Double) = Asset(
        name = symbol,
        type = AssetType.FUND,
        symbol = symbol,
        account = "默认",
        balance = balance
    )

    private fun deposit(balance: Double, rate: Double?) = Asset(
        name = "存款",
        type = AssetType.BANK_DEPOSIT,
        symbol = null,
        account = "银行",
        balance = balance,
        interestRate = rate
    )

    private fun crypto(balance: Double) = Asset(
        name = "BTC",
        type = AssetType.CRYPTO,
        symbol = null,
        account = "链上",
        balance = balance
    )

    // ===== 单资产年化收入 =====

    @Test
    fun `银行存款按利率与本金算利息`() {
        // totalValue(存款) = balance = 100000；利息 = 100000 * 3% = 3000
        assertEquals(3000.0, DividendIncomeCalculator.calculateAnnualIncome(deposit(100_000.0, 3.0)), 0.001)
    }

    @Test
    fun `银行存款利率为空或为零则无利息`() {
        assertEquals(0.0, DividendIncomeCalculator.calculateAnnualIncome(deposit(100_000.0, null)), 0.001)
        assertEquals(0.0, DividendIncomeCalculator.calculateAnnualIncome(deposit(100_000.0, 0.0)), 0.001)
    }

    @Test
    fun `股票按每十股分红乘持仓算分红`() {
        // 1000 股 × 每十股 10 元 ÷ 10 = 1000
        assertEquals(1000.0, DividendIncomeCalculator.calculateAnnualIncome(stock("600519", 1000.0), 10.0), 0.001)
    }

    @Test
    fun `基金与股票同口径`() {
        assertEquals(500.0, DividendIncomeCalculator.calculateAnnualIncome(fund("510300", 1000.0), 5.0), 0.001)
    }

    @Test
    fun `虚拟货币不计入分红`() {
        assertEquals(0.0, DividendIncomeCalculator.calculateAnnualIncome(crypto(1.0), 100.0), 0.001)
    }

    // ===== 组合年化总收入 =====

    @Test
    fun `组合总收入汇总存款利息与持仓分红`() {
        val assets = listOf(
            deposit(100_000.0, 3.0),     // 利息 3000
            stock("600519", 1000.0)      // 分红依赖 dividendsBySymbol
        )
        val bySymbol = mapOf("600519" to 10.0) // 1000 股 × 10 ÷ 10 = 1000
        assertEquals(4000.0, DividendIncomeCalculator.calculateTotalAnnualIncome(assets, bySymbol), 0.001)
    }

    @Test
    fun `组合总收入：无对应分红记录的股票不计分红`() {
        val assets = listOf(stock("600519", 1000.0))
        // 不提供 600519 的每十股分红 → 0
        assertEquals(0.0, DividendIncomeCalculator.calculateTotalAnnualIncome(assets, emptyMap()), 0.001)
    }

    @Test
    fun `组合总收入：负债不计入任何收入`() {
        val liability = Asset(
            name = "房贷", type = AssetType.MORTGAGE, symbol = null,
            account = "银行", balance = 1_000_000.0
        )
        assertEquals(0.0, DividendIncomeCalculator.calculateTotalAnnualIncome(listOf(liability), emptyMap()), 0.001)
    }

    // ===== 收入率（分母剔除负债） =====

    @Test
    fun `收入率以剔除负债后的总资产为分母`() {
        // 股票：市值 = 1000 × 10 = 10000，分红 1000 → 收入率 10%
        val assets = listOf(stock("600519", 1000.0, currentPrice = 10.0))
        val bySymbol = mapOf("600519" to 10.0)
        assertEquals(10.0, DividendIncomeCalculator.calculatePortfolioYield(assets, bySymbol), 0.001)
    }

    @Test
    fun `收入率：总资产为零或非正返回 0`() {
        assertEquals(0.0, DividendIncomeCalculator.calculatePortfolioYield(emptyList(), emptyMap()), 0.001)
        // 全是负债 → 有效总资产为 0
        val liability = Asset(name = "房贷", type = AssetType.MORTGAGE, symbol = null, account = "银行", balance = 100.0)
        assertEquals(0.0, DividendIncomeCalculator.calculatePortfolioYield(listOf(liability), emptyMap()), 0.001)
    }

    @Test
    fun `收入率：负债不进入分母`() {
        // 股票市值 10000、分红 1000；再加一笔 90000 负债 → 分母仍是 10000，收入率仍为 10%
        val stockAsset = stock("600519", 1000.0, currentPrice = 10.0)
        val liability = Asset(name = "房贷", type = AssetType.MORTGAGE, symbol = null, account = "银行", balance = 90_000.0)
        val bySymbol = mapOf("600519" to 10.0)
        assertEquals(10.0, DividendIncomeCalculator.calculatePortfolioYield(listOf(stockAsset, liability), bySymbol), 0.001)
    }
}
