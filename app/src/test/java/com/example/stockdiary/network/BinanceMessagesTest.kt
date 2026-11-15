package com.example.stockdiary.network

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [BinanceMessages] 文案与格式化口径回归。
 *
 * 这组函数从 [BinanceAccountAPI] 外提（原来埋在 1600 行 object 里），行为要求**逐字符不变**：
 * 它们同时出现在诊断日志与用户可见的失败原因里，任何一位小数、任何一个分隔符的变化
 * 都会让既有的排查口令（"USDT 到底在不在返回里"）失效。
 */
class BinanceMessagesTest {

    @Test
    fun `理财标签_定期与活期`() {
        assertEquals("定期", BinanceMessages.earnKindLabel(true))
        assertEquals("活期", BinanceMessages.earnKindLabel(false))
    }

    @Test
    fun `金额_最多八位小数且去尾零`() {
        assertEquals("144.99343452", BinanceMessages.formatAmount(144.99343452))
        assertEquals("144.99", BinanceMessages.formatAmount(144.99))
        assertEquals("100", BinanceMessages.formatAmount(100.0))
        assertEquals("0", BinanceMessages.formatAmount(0.0))
        // 日志要能 grep，小数点必须是 '.'，不能随系统语言变成逗号
        assertEquals("1234.5", BinanceMessages.formatAmount(1234.5))
    }

    @Test
    fun `USDT_存在时带金额_不存在时说否`() {
        val withUsdt = listOf(
            BinanceEarnPosition("BTC", 0.5, false),
            BinanceEarnPosition("usdt", 144.99343452, false)
        )
        assertEquals("是(144.99343452)", BinanceMessages.describeUsdt(withUsdt))
        assertEquals("否", BinanceMessages.describeUsdt(listOf(BinanceEarnPosition("BTC", 0.5, true))))
        assertEquals("否", BinanceMessages.describeUsdt(emptyList()))
    }

    @Test
    fun `USDT_大小写不敏感_取第一个命中`() {
        val positions = listOf(
            BinanceEarnPosition("UsDt", 1.0, false),
            BinanceEarnPosition("USDT", 2.0, true)
        )
        assertEquals("是(1)", BinanceMessages.describeUsdt(positions))
    }

    @Test
    fun `页首资产_空列表说无_最多列三个`() {
        assertEquals("无", BinanceMessages.describeHeadAssets(emptyList()))

        val positions = listOf(
            BinanceEarnPosition("USDT", 144.99, false),
            BinanceEarnPosition("BUSD", 12.3, false),
            BinanceEarnPosition("BTC", 0.5, true),
            BinanceEarnPosition("ETH", 2.0, false)
        )
        assertEquals("USDT:144.99、BUSD:12.3、BTC:0.5", BinanceMessages.describeHeadAssets(positions))
    }
}
