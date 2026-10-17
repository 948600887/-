package com.example.stockdiary.network

import com.example.stockdiary.data.importer.priceKeyFor
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 币安符号映射测试。
 *
 * 重点盯 [BinanceAPI.cryptoTradingPairFor]：它修的是"手动添加的加密货币永远取不到价"
 * 这个 bug——早期实现直接拿库里 symbol 拼 URL，导入的资产存 `BTCUSDT` 能取到价，
 * 手动添加的存 `BTC` 就拼出 `?symbol=BTC` 被币安 400 拒绝。
 *
 * 另一条要盯的是「自身就是报价币」与「以报价币结尾」的**判定顺序**：
 * 顺序反了 BTC 会被误判成"已是交易对"，正是上述 bug 的同源变体。
 */
class BinanceSymbolMappingTest {

    // ---------- 裸基础资产 → 补 USDT（手动添加的加密货币走这条） ----------

    @Test
    fun `裸基础资产补 USDT`() {
        assertEquals("BTCUSDT", BinanceAPI.cryptoTradingPairFor("BTC"))
        assertEquals("ETHUSDT", BinanceAPI.cryptoTradingPairFor("ETH"))
        assertEquals("ADAUSDT", BinanceAPI.cryptoTradingPairFor("ADA"))
        assertEquals("SOLUSDT", BinanceAPI.cryptoTradingPairFor("SOL"))
        assertEquals("DOGEUSDT", BinanceAPI.cryptoTradingPairFor("DOGE"))
    }

    @Test
    fun `大小写与空格归一`() {
        assertEquals("BTCUSDT", BinanceAPI.cryptoTradingPairFor("btc"))
        assertEquals("ETHUSDT", BinanceAPI.cryptoTradingPairFor("  eth "))
        assertEquals("ADAUSDT", BinanceAPI.cryptoTradingPairFor("aDa"))
    }

    /**
     * 核心回归用例：BTC/ETH/BNB 自身就是报价币，必须先按"裸基础资产"处理。
     * 若实现先判 endsWith，BTC 会被当成已是交易对而原样返回，再次触发老 bug。
     */
    @Test
    fun `自身就是报价币时补 USDT 而不是当成已是交易对`() {
        assertEquals("BTCUSDT", BinanceAPI.cryptoTradingPairFor("BTC"))
        assertEquals("ETHUSDT", BinanceAPI.cryptoTradingPairFor("ETH"))
        assertEquals("BNBUSDT", BinanceAPI.cryptoTradingPairFor("BNB"))
        // USDT 是稳定币，走另一条分支（返回 null），这里只确保它没被当成交易对
        assertEquals(null, BinanceAPI.cryptoTradingPairFor("USDT"))
    }

    // ---------- 已是交易对 → 原样返回（币安导入的资产走这条） ----------

    @Test
    fun `已是交易对形态原样返回`() {
        assertEquals("BTCUSDT", BinanceAPI.cryptoTradingPairFor("BTCUSDT"))
        assertEquals("ADAUSDT", BinanceAPI.cryptoTradingPairFor("ADAUSDT"))
        assertEquals("ETHBTC", BinanceAPI.cryptoTradingPairFor("ETHBTC"))
        assertEquals("1INCHUSDT", BinanceAPI.cryptoTradingPairFor("1INCHUSDT"))
    }

    /**
     * ETHBTC 这类"基础资产恰好是报价币名"的组合最容易误判：
     * 它长度大于 "BTC"，必须以 BTC 结尾判为交易对，而不是把 ETH 当基础资产补成 ETHUSDT。
     */
    @Test
    fun `基础资产名与报价币同名时按交易对处理`() {
        assertEquals("ETHBTC", BinanceAPI.cryptoTradingPairFor("ETHBTC"))
        assertEquals("BNBBTC", BinanceAPI.cryptoTradingPairFor("BNBBTC"))
    }

    // ---------- 稳定币 → null（按 1 美元计，省一次注定为空的请求） ----------

    @Test
    fun `稳定币返回 null`() {
        assertEquals(null, BinanceAPI.cryptoTradingPairFor("USDT"))
        assertEquals(null, BinanceAPI.cryptoTradingPairFor("USDC"))
        assertEquals(null, BinanceAPI.cryptoTradingPairFor("usdc"))
        assertEquals(null, BinanceAPI.cryptoTradingPairFor("FDUSD"))
        assertEquals(null, BinanceAPI.cryptoTradingPairFor("DAI"))
    }

    @Test
    fun `空串返回 null`() {
        assertEquals(null, BinanceAPI.cryptoTradingPairFor(""))
        assertEquals(null, BinanceAPI.cryptoTradingPairFor("   "))
    }

    /**
     * 委托安全性证明：导入链路的 priceKeyFor 现在委托给 cryptoTradingPairFor。
     * 两者的输入域不同（priceKeyFor 只吃账户接口给的基础资产名），但在那个域上
     * 行为必须逐字一致——否则导入时的取价键会变，估值与过滤跟着错。
     */
    @Test
    fun `与导入链路 priceKeyFor 在基础资产输入域上一致`() {
        val baseAssets = listOf("BTC", "ETH", "SOL", "ADA", "DOGE", "btc", "  eth ", "LTC")
        for (asset in baseAssets) {
            assertEquals(
                "priceKeyFor($asset) 与 cryptoTradingPairFor($asset) 不一致",
                priceKeyFor(asset),
                BinanceAPI.cryptoTradingPairFor(asset)
            )
        }
        val stablecoins = listOf("USDT", "USDC", "FDUSD", "DAI", "BUSD")
        for (coin in stablecoins) {
            assertEquals(
                "稳定币 $coin 两侧都应返回 null",
                priceKeyFor(coin),
                BinanceAPI.cryptoTradingPairFor(coin)
            )
        }
    }
}
