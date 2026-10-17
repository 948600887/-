package com.example.stockdiary.data

import com.example.stockdiary.data.dividend.DividendIncomeCalculator
import com.example.stockdiary.data.dividend.HoldingDividendInfo
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.utils.totalValueCny
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 外币资产参与组合计算时的口径回归。
 *
 * 原先这批地方的分子算的是合计值、分母却直接 sum 原币 totalValue，
 * 多币种一掺就把美元和日元当同单位相加。现在一律先折成人民币。
 */
class MultiCurrencyAmountTest {

    private fun asset(
        name: String = "测试",
        type: AssetType = AssetType.STOCK,
        symbol: String? = "600519",
        balance: Double = 100.0,
        price: Double = 10.0,
        currency: String = "CNY",
        interestRate: Double? = null
    ) = Asset(
        id = 0,
        name = name,
        type = type,
        symbol = symbol,
        account = "测试账户",
        balance = balance,
        purchasePrice = price,
        currentPrice = price,
        currency = currency,
        interestRate = interestRate
    )

    // ---------- 单资产年化收入 ----------

    @Test
    fun 人民币资产的总市值不受汇率影响() {
        val a = asset(balance = 100.0, price = 10.0, currency = "CNY")
        assertEquals(1000.0, a.totalValueCny, 0.001)
    }

    @Test
    fun 港币资产的市值按汇率折算() {
        // 100 份 × 10 HKD = 1000 HKD，按内置汇率 0.93 得 930 CNY
        val a = asset(balance = 100.0, price = 10.0, currency = "HKD")
        assertEquals(930.0, a.totalValueCny, 0.001)
    }

    @Test
    fun 美元存款的利息折成人民币() {
        // 10000 USD 本金，年利率 5% → 500 USD → 按 7.25 得 3625 CNY
        val deposit = asset(
            type = AssetType.BANK_DEPOSIT,
            symbol = null,
            balance = 10000.0,
            price = 0.0,
            currency = "USD",
            interestRate = 5.0
        )
        assertEquals(3625.0, DividendIncomeCalculator.calculateAnnualIncome(deposit), 0.001)
    }

    @Test
    fun 港币股票的分红折成人民币() {
        // 1000 股，每 10 股派 10 HKD → 1000 HKD → 按 0.93 得 930 CNY
        val hkStock = asset(symbol = "00700", balance = 1000.0, currency = "HKD")
        assertEquals(930.0, DividendIncomeCalculator.calculateAnnualIncome(hkStock, 10.0), 0.001)
    }

    // ---------- 组合口径 ----------

    @Test
    fun 混合币种时_组合收益率的分母折算成人民币口径() {
        // 人民币资产 10000 CNY，港币资产 10000 HKD（折 9300 CNY）
        // 分母必须是 19300，直接相加会算成 20000
        val cny = asset(name = "A股", balance = 1000.0, price = 10.0, currency = "CNY")
        val hkd = asset(name = "港股", symbol = "00700", balance = 1000.0, price = 10.0, currency = "HKD")
        val assets = listOf(cny, hkd)

        val yield = DividendIncomeCalculator.calculatePortfolioYield(
            assets = assets,
            // 每只每 10 股派 10（各自币种）
            dividendsBySymbol = mapOf("600519" to 10.0, "00700" to 10.0)
        )

        // 收入：1000/10*10 = 1000 CNY(A股) + 1000/10*10*0.93 = 930 CNY(港股) = 1930
        // 总资产：10000 + 9300 = 19300
        // 收益率 = 1930 / 19300 = 10%
        assertEquals(10.0, yield, 0.001)
    }

    @Test
    fun 同一资产用不同币种记账_算出的收益率应当一致() {
        // 这是口径是否统一的判据：把同一笔钱记成 CNY 还是 USD，
        // 收益率这个比值必须相同，否则就是分子分母币种不一致。
        val rate = 7.25
        val inCny = asset(balance = 1000.0, price = 10.0, currency = "CNY")
        val inUsd = asset(
            balance = 1000.0,
            price = 10.0 / rate, // 换成等值美元单价
            currency = "USD"
        )
        val args = mapOf("600519" to 4.0)

        val yieldCny = DividendIncomeCalculator.calculatePortfolioYield(listOf(inCny), args)
        // 美元口径：每股分红也要跟着折成美元，否则比值会放大 7.25 倍
        val yieldUsd = DividendIncomeCalculator.calculatePortfolioYield(
            listOf(inUsd),
            mapOf("600519" to 4.0 / rate)
        )

        assertEquals(yieldCny, yieldUsd, 0.0001)
    }

    @Test
    fun 加权平均股息率的分母折算成人民币口径() {
        val holding = HoldingDividendInfo(
            asset = asset(balance = 1000.0, price = 10.0, currency = "HKD"),
            annualDividendPerShare = 10.0,
            dividendYieldByCost = 0.0,
            dividendYieldByPrice = 0.0
        )
        // 分红 1000 HKD = 930 CNY；市值 10000 HKD = 9300 CNY → 10%
        assertEquals(10.0, DividendIncomeCalculator.calculatePortfolioDividendYield(listOf(holding)), 0.001)
    }

    @Test
    fun 年度总分红也折成人民币() {
        val holding = HoldingDividendInfo(
            asset = asset(balance = 1000.0, price = 10.0, currency = "HKD"),
            annualDividendPerShare = 10.0,
            dividendYieldByCost = 0.0,
            dividendYieldByPrice = 0.0
        )
        assertEquals(930.0, DividendIncomeCalculator.calculateTotalAnnualDividend(listOf(holding)), 0.001)
        assertEquals(77.5, DividendIncomeCalculator.calculateMonthlyAverageDividend(listOf(holding)), 0.001)
    }

    // ---------- 已知货币之外的兜底 ----------

    @Test
    fun 未知币种按一倍处理而不是崩掉() {
        val weird = asset(balance = 100.0, price = 10.0, currency = "XYZ")
        assertEquals(1000.0, weird.totalValueCny, 0.001)
    }
}
