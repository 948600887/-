package com.example.stockdiary.network

import com.example.stockdiary.data.importer.baseAssetToSymbol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 币安签名与 symbol 映射的回归。
 *
 * 重点是 [BinanceAccountAPI.sign]：它是整条账户链路里唯一「错一位全盘皆错」
 * 又不依赖网络的部分，所以用币安官方文档的已知向量直接验 HMAC 实现，
 * 不必连网、不必伪造 OkHttp。
 */
class BinanceAccountAPITest {

    /**
     * 币安官方文档的 HMAC-SHA256 示例向量（公开的演示密钥，非真实机密）。
     *
     * 用它是为了对齐币安的真实签名口径：编码（UTF-8）、算法（HmacSHA256）、
     * 输出（小写 hex）。任何一处对不上，签名就会被币安拒。
     */
    private val docSecret = "NhqPtmdSJYdKjVHjA7PZj4Mge3R5YNiP1e3UZjInClVN65XAbvqqM6A7H5fATj0j"
    private val docQuery =
        "symbol=LTCBTC&side=BUY&type=LIMIT&timeInForce=GTC&quantity=1&price=0.1&recvWindow=5000&timestamp=1499827319559"
    private val docSignature =
        "c8db56825ae71d6d79447849e617115f4a920fa2acdcab2b053c4b2838bd6b71"

    @Test
    fun 签名匹配币安官方文档向量() {
        assertEquals(docSignature, BinanceAccountAPI.sign(docSecret, docQuery))
    }

    @Test
    fun 同一输入总是同一输出() {
        val a = BinanceAccountAPI.sign(docSecret, docQuery)
        val b = BinanceAccountAPI.sign(docSecret, docQuery)
        assertEquals(a, b)
    }

    @Test
    fun 输出是64位小写十六进制() {
        val sig = BinanceAccountAPI.sign(docSecret, docQuery)
        // 256 bit / 4 = 64 个 hex 字符
        assertEquals(64, sig.length)
        assertTrue("签名只能含 0-9a-f", sig.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun 换secret得到不同签名() {
        val other = BinanceAccountAPI.sign("另一个完全不同的secret", docQuery)
        assertNotEquals(BinanceAccountAPI.sign(docSecret, docQuery), other)
    }

    @Test
    fun query差一位签名就变() {
        val base = BinanceAccountAPI.sign(docSecret, "timestamp=1499827319559")
        val tweaked = BinanceAccountAPI.sign(docSecret, "timestamp=1499827319558")
        assertNotEquals(base, tweaked)
    }

    // ---------- symbol 映射：基础资产 → 交易对 ----------

    @Test
    fun 基础资产补成USDT交易对() {
        // 币安账户接口给裸资产名，落库必须是 App 取价用的交易对形态
        assertEquals("BTCUSDT", baseAssetToSymbol("BTC"))
        assertEquals("ETHUSDT", baseAssetToSymbol("ETH"))
        assertEquals("LTCUSDT", baseAssetToSymbol("LTC"))
        assertEquals("SOLUSDT", baseAssetToSymbol("SOL"))
    }

    @Test
    fun 大小写和空格都被归一() {
        assertEquals("BTCUSDT", baseAssetToSymbol("btc"))
        assertEquals("ETHUSDT", baseAssetToSymbol("  eth "))
    }

    @Test
    fun 已是USDT计价的稳定币原样返回() {
        // USDT 自身没有 USDTUSDT 交易对，原样保留（价格同步会留空，但余额准确）
        assertEquals("USDT", baseAssetToSymbol("USDT"))
    }
}
