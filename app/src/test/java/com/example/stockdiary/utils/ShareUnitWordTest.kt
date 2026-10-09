package com.example.stockdiary.utils

import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 分红单位文案：股票是「股」，基金 / ETF / 场外基金是「份」。
 *
 * 之前整个 App 一律写「每10股」，对持有 ETF 的人（比如 510210）来说是错的单位。
 */
class ShareUnitWordTest {

    private fun asset(type: AssetType, symbol: String? = null) = Asset(
        name = "测试",
        type = type,
        symbol = symbol,
        account = "默认",
        balance = 1000.0
    )

    @Test
    fun `股票用 股`() {
        assertEquals("股", asset(AssetType.STOCK, "sh600519").shareUnitWord)
    }

    @Test
    fun `ETF 是场内基金用 份`() {
        // 510210 上证指数ETF：份额单位是「份」，不是「股」
        assertEquals("份", asset(AssetType.FUND, "sh510210").shareUnitWord)
    }

    @Test
    fun `场外基金用 份`() {
        assertEquals("份", asset(AssetType.FUND, "fund_110011").shareUnitWord)
    }

    @Test
    fun `类型是基金就按 份 走，不看代码长什么样`() {
        // 用户手动把一只股票记成了「基金」，按用户填的类型来
        assertEquals("份", asset(AssetType.FUND, "sh600519").shareUnitWord)
    }

    @Test
    fun `类型没填对时靠代码兜底`() {
        // 类型是「银行存款」但代码是股票，兜底能认出是股票
        assertEquals("股", asset(AssetType.BANK_DEPOSIT, "sh600519").shareUnitWord)
        // 认不出股票的一律按「份」
        assertEquals("份", asset(AssetType.BANK_DEPOSIT, null).shareUnitWord)
    }
}
