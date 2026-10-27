package com.example.stockdiary.data

import com.example.stockdiary.data.dividend.DividendIncomeCalculator
import com.example.stockdiary.data.dividend.DividendProgressCalculator
import com.example.stockdiary.data.dividend.daysInCurrentYear
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import java.time.LocalDate
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

    private fun moneyFund(symbol: String, balance: Double, millionCopiesIncome: Double) = Asset(
        name = symbol,
        type = AssetType.FUND,
        symbol = symbol,
        account = "默认",
        balance = balance,
        millionCopiesIncome = millionCopiesIncome
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
    fun `货币基金年化收入按当前自然年天数动态计算`() {
        // 10000 份 × 每万份收益 1.0 ÷ 10000 = 1 元/天 → 年化 = 1 × 当前自然年天数（闰年 366 / 平年 365）
        // 注意：必须按 daysInCurrentYear() 动态断言，不能硬编码 365，否则闰年会假失败。
        assertEquals(
            daysInCurrentYear().toDouble(),
            DividendIncomeCalculator.calculateAnnualIncome(moneyFund("519518", 10_000.0, 1.0)),
            1e-9
        )
    }

    @Test
    fun `货币基金年化按自然年天数且与收息进度计算器同源`() {
        // 验证修复点：货基年化改用「当前自然年天数」（闰年 366 / 平年 365），
        // 且必须与 DividendProgressCalculator.naturalYearAnnualIncome 对同只货基给出同一数字，
        // 否则「年化收入」与收息进度的「全年预计」会同屏自相矛盾。
        val asset = moneyFund("519518", 10_000.0, 1.0) // 份额 × 万份收益 ÷ 10000 = 1 元/天
        val expected = 10_000.0 * 1.0 / 10_000.0 * daysInCurrentYear()

        val calc = DividendIncomeCalculator.calculateAnnualIncome(asset)
        assertEquals(
            "货币基金年化必须按当前自然年天数（闰年 366 / 平年 365）",
            expected, calc, 1e-9
        )

        val progress = DividendProgressCalculator.naturalYearAnnualIncome(
            listOf(asset), emptyMap(), LocalDate.now()
        )
        assertEquals(
            "货基年化口径必须与收息进度计算器一致",
            expected, progress, 1e-9
        )
    }

    @Test
    fun `货币基金没有分红记录也照样计入年化收入`() {
        // 关键：货币基金根本不派「每十股分红」，dividendPerShare 传 0 也要算出钱来。
        // 改之前这里返回 0，会让「年化收入」与收息进度的「全年预计」对同一只货基给出两个数。
        // 年化 = 20000 × 1.0 ÷ 10000 × 当前自然年天数（闰年 366 / 平年 365），动态断言避免跨年 flaky。
        assertEquals(
            20_000.0 * 1.0 / 10_000.0 * daysInCurrentYear(),
            DividendIncomeCalculator.calculateAnnualIncome(moneyFund("004972", 20_000.0, 1.0), 0.0),
            1e-9
        )
    }

    @Test
    fun `普通基金没有万份收益时仍按每十股分红算`() {
        // 回归护栏：普通基金不该被货基分支抢走
        assertEquals(500.0, DividendIncomeCalculator.calculateAnnualIncome(fund("510300", 1000.0), 5.0), 0.001)
    }

    @Test
    fun `组合年化总收入包含货币基金`() {
        val assets = listOf(
            moneyFund("519518", 10_000.0, 1.0),  // 年化 = 当前自然年天数（闰年 366 / 平年 365）
            stock("600519", 1000.0)              // 分红 1000
        )
        val bySymbol = mapOf("600519" to 10.0)
        assertEquals(
            daysInCurrentYear() + 1000.0,
            DividendIncomeCalculator.calculateTotalAnnualIncome(assets, bySymbol),
            1e-9
        )
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
