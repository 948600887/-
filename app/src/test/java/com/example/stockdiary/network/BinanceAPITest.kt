package com.example.stockdiary.network

import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BinanceAPI.isEarnProduct] / [BinanceAPI.earnUnderlyingPair] 与
 * [NonTradableAssetException] 的单元测试。
 *
 * ## 为什么要有这组测试
 *
 * 一轮实测同步里 37 条「获取加密货币价格失败」有 32 条是 `LD*` 持仓：币安
 * Simple Earn / 锁仓产品的代码是 `LD` + 底层资产名（`LDBNB` / `LDLTC` / `LDPOL`…），
 * 账户接口把它和现货一起返回、被当成普通持仓写进库。它不是现货交易对，
 * 币安没有 `LDLTCUSDT` 市场，直接拿它发请求必然失败——最惨的一次挂了 3478 秒，
 * 把 AssetSyncManager 的并发信号量堵死，整轮同步跑了 70 分钟。
 *
 * 这里盯住三件事：
 * 1. 各种书写形态（大小写 / 空格 / 带报价币后缀）都能被认成理财壳；
 * 2. **LDO 不能被误伤**——Lido DAO 是真实现货，有 LDOUSDT 交易对。
 *    把它判成不可交易，比漏判一个 LD 持仓严重得多，所以正反两面都写用例；
 * 3. LD 剥掉前缀后必须得到**底层交易对**（1:1 可赎回），否则这些持仓
 *    purchasePrice = 0 又取不到价，市值恒为 0，总资产少算整整一块。
 */
class BinanceAPITest {

    // ---------- 理财/锁仓产品 → true ----------

    @Test
    fun `LD 前缀锁仓产品识别为理财`() {
        assertTrue(BinanceAPI.isEarnProduct("LDBNB"))
        assertTrue(BinanceAPI.isEarnProduct("LDLTCUSDT"))
        assertTrue(BinanceAPI.isEarnProduct("LDMANTRAUSDT"))
        assertTrue(BinanceAPI.isEarnProduct("LDPAXGUSDT"))
        assertTrue(BinanceAPI.isEarnProduct("LDPOLUSDT"))
    }

    @Test
    fun `小写与前后空格归一后才判断`() {
        assertTrue(BinanceAPI.isEarnProduct("ldpolusdt"))
        assertTrue(BinanceAPI.isEarnProduct(" LDPAXGUSDT "))
        assertTrue(BinanceAPI.isEarnProduct("ldbnb"))
    }

    // ---------- 常规资产与边界 → false ----------

    @Test
    fun `LDO 是现货不是理财`() {
        assertFalse("LDO 是 Lido DAO 的现货，必须排除在外", BinanceAPI.isEarnProduct("LDO"))
        assertFalse("LDOUSDT 是真实现货交易对", BinanceAPI.isEarnProduct("LDOUSDT"))
        assertFalse(BinanceAPI.isEarnProduct("ldousdt"))
    }

    @Test
    fun `常规加密货币不是理财`() {
        assertFalse(BinanceAPI.isEarnProduct("BTC"))
        assertFalse(BinanceAPI.isEarnProduct("ETHUSDT"))
        assertFalse(BinanceAPI.isEarnProduct("BNBUSDT"))
        assertFalse(BinanceAPI.isEarnProduct("USDT"))
    }

    @Test
    fun `空串与短串不是理财`() {
        assertFalse(BinanceAPI.isEarnProduct(""))
        assertFalse(BinanceAPI.isEarnProduct("   "))
        assertFalse(BinanceAPI.isEarnProduct("LD"))
    }

    // ---------- 异常判定 ----------

    @Test
    fun `NonTradableAssetException 判定`() {
        assertFalse(NonTradableAssetException.isNonTradable(null))
        assertTrue(NonTradableAssetException.isNonTradable(NonTradableAssetException("x")))
        assertFalse(NonTradableAssetException.isNonTradable(IllegalStateException("x")))
        // 被包了一层（协程/Result 传递后常见）也要能认出来
        assertTrue(
            NonTradableAssetException.isNonTradable(
                RuntimeException("wrapper", NonTradableAssetException("inner"))
            )
        )
    }

    /**
     * 两条豁免口径不能互相吃掉：理财/LD 不能被当成「停牌」，反之亦然。
     * AssetSyncManager 的记账分支是 if / else if 链，判串了记错账。
     */
    @Test
    fun `与停牌豁免互不误判`() {
        val nonTradable: Throwable = NonTradableAssetException("LDLTCUSDT 是币安理财/锁仓产品，没有现货行情")
        assertFalse(QuoteSuspendedException.isSuspended(nonTradable))
        assertTrue(NonTradableAssetException.isNonTradable(nonTradable))

        val suspended: Throwable = QuoteSuspendedException("600000", "停牌或无有效报价: 600000")
        assertFalse(NonTradableAssetException.isNonTradable(suspended))
        assertTrue(QuoteSuspendedException.isSuspended(suspended))
    }

    /**
     * 上面新增的判断不能打扰既有映射：cryptoTradingPairFor 对 LD 前缀仍按
     * 「裸资产补 USDT」处理（LD 的取价已改走 earnUnderlyingPair 的底层交易对，
     * 不再走这条），但对 LDO 等现货必须保持原样。
     */
    @Test
    fun `交易对映射未被改动影响`() {
        assertEquals("BTCUSDT", BinanceAPI.cryptoTradingPairFor("BTC"))
        assertEquals("ETHBTC", BinanceAPI.cryptoTradingPairFor("ETHBTC"))
        assertEquals("LDOUSDT", BinanceAPI.cryptoTradingPairFor("LDO"))
        assertEquals("LDOUSDT", BinanceAPI.cryptoTradingPairFor("LDOUSDT"))
        assertEquals(null, BinanceAPI.cryptoTradingPairFor("USDT"))
        assertEquals(null, BinanceAPI.cryptoTradingPairFor(""))
        // LD 前缀仍会被补成 (并不存在的) 交易对，所以 LD 的查价必须走 earnUnderlyingPair
        assertEquals("LDLTCUSDT", BinanceAPI.cryptoTradingPairFor("LDLTC"))
    }

    // ---------- 底层交易对（LD 1:1 计价） ----------

    /**
     * LD 与底层资产 1:1 可赎回，估值剥掉 LD 用底层交易对。
     *
     * 这条是「LD 持仓总资产不再为 0」的前提：导入时 purchasePrice = 0，
     * 取价键必须是底层交易对（LDBNBUSDT → BNBUSDT），否则 currentPrice 永远为空。
     */
    @Test
    fun `LD 剥掉前缀得到底层交易对`() {
        assertEquals("BNBUSDT", BinanceAPI.earnUnderlyingPair("LDBNBUSDT"))
        assertEquals("BNBUSDT", BinanceAPI.earnUnderlyingPair("LDBNB"))
        assertEquals("POLUSDT", BinanceAPI.earnUnderlyingPair("LDPOLUSDT"))
        assertEquals("LTCUSDT", BinanceAPI.earnUnderlyingPair("LDLTC"))
        // 大小写与空格同样归一（与 isEarnProduct 的输入域一致）
        assertEquals("BNBUSDT", BinanceAPI.earnUnderlyingPair("ldbnb"))
        assertEquals("POLUSDT", BinanceAPI.earnUnderlyingPair(" ldpolusdt "))
    }

    /**
     * 剥掉 LD 也构不成交易对 → null，调用方按「无行情」处理（NonTradableAssetException）。
     * 每一类 null 的理由都不同，逐个钉住：
     * - LDO 是真实现贷（Lido DAO），有 LDOUSDT 现货，不能被当理财壳；
     * - LDUSDT 剥完是稳定币，币安上没有 USDTUSDT 自我交易对；
     * - 非 LD 符号 / 空串 / 光杆 LD 本就不是理财产品。
     */
    @Test
    fun `构不成底层交易对时返回 null`() {
        assertEquals(null, BinanceAPI.earnUnderlyingPair("LDOUSDT"))
        assertEquals(null, BinanceAPI.earnUnderlyingPair("LDO"))
        assertEquals(null, BinanceAPI.earnUnderlyingPair("LDUSDT"))
        assertEquals(null, BinanceAPI.earnUnderlyingPair("BTCUSDT"))
        assertEquals(null, BinanceAPI.earnUnderlyingPair(""))
        assertEquals(null, BinanceAPI.earnUnderlyingPair("LD"))
    }

    /**
     * 端到端语义：LD 持仓按底层价计入后，totalValue 不再恒为 0。
     *
     * 钉住这次优化的核心诉求——币安导入的 LD 持仓 purchasePrice = 0，
     * 只要 currentPrice 有价（底层币市价），总资产/净资产/快照的加密分块
     * 就把这部分真实资产算进去。
     */
    @Test
    fun `LD 持仓有价后总资产不再为 0`() {
        val asset = Asset(
            name = "LDBNB",
            type = AssetType.CRYPTO,
            symbol = "LDBNBUSDT",
            account = "币安",
            balance = 2.0,
            // 导入时的真实形态：参考单价 0，现价由行情同步按底层 BNBUSDT 填
            purchasePrice = 0.0,
            currentPrice = 600.0
        )
        assertEquals(1200.0, asset.totalValue, 1e-9)
    }
}
