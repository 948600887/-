package com.example.stockdiary.ui.dialogs

import com.example.stockdiary.database.AssetType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * buildAssetFromInput 是从 AddAssetDialog 里抽出来的纯函数：
 * 把表单字符串 + 查到的行情价 变成 Asset。对话框不该有业务逻辑，
 * 全部收敛到这里，因而可以在 JVM 上直接测，不需要 Robolectric。
 */
class BuildAssetFromInputTest {

    private fun build(
        type: AssetType,
        name: String = "测试资产",
        symbol: String = "",
        account: String = "招商证券",
        balance: String = "100",
        purchasePrice: String = "",
        interestRate: String = "",
        notes: String = "",
        fundPrice: Double? = null,
        stockPrice: Double? = null
    ) = buildAssetFromInput(
        selectedType = type,
        name = name,
        symbol = symbol,
        account = account,
        balance = balance,
        purchasePrice = purchasePrice,
        interestRate = interestRate,
        notes = notes,
        fundPrice = fundPrice,
        stockPrice = stockPrice
    )

    @Test
    fun 股票代码会被规范化并带市场前缀() {
        val asset = build(AssetType.STOCK, symbol = "600519")
        assertEquals("sh600519", asset.symbol)
    }

    @Test
    fun 基金代码会被规范化() {
        val asset = build(AssetType.FUND, symbol = "515080")
        assertEquals("sh515080", asset.symbol)
    }

    @Test
    fun 非股票基金类型不规范化代码() {
        // 虚拟货币的代码是 BTC 之类，走 StockCodeManager 会变成空串/错值，因此跳过
        val asset = build(AssetType.CRYPTO, symbol = "BTC")
        assertEquals("BTC", asset.symbol)
    }

    @Test
    fun 空白代码落成null而不是空串() {
        val asset = build(AssetType.STOCK, symbol = "   ")
        assertNull(asset.symbol)
    }

    @Test
    fun 银行存款单价恒为1且保留利率() {
        val asset = build(
            AssetType.BANK_DEPOSIT,
            name = "三年定存",
            balance = "50000",
            interestRate = "2.75"
        )
        assertEquals(1.0, asset.purchasePrice, 0.0)
        assertEquals(1.0, asset.currentPrice!!, 0.0)
        assertEquals(2.75, asset.interestRate!!, 0.0)
        // 存款市值直接等于本金，不能被单价放大
        assertEquals(50000.0, asset.totalValue, 0.0)
    }

    @Test
    fun 非存款类型的利率恒为null() {
        val asset = build(AssetType.STOCK, interestRate = "3.5")
        assertNull(asset.interestRate)
    }

    @Test
    fun 有基金行情时优先用基金价() {
        val asset = build(AssetType.FUND, fundPrice = 1.23, stockPrice = 9.99, purchasePrice = "5.0")
        assertEquals(1.23, asset.currentPrice!!, 0.0)
    }

    @Test
    fun 无基金行情时退回股票行情() {
        val asset = build(AssetType.STOCK, fundPrice = null, stockPrice = 9.99, purchasePrice = "5.0")
        assertEquals(9.99, asset.currentPrice!!, 0.0)
    }

    @Test
    fun 无行情时当前价回落到手填的参考单价() {
        // purchasePrice 的定位就是「无行情时的市值估算兜底」，所以这里不该是 null
        val asset = build(AssetType.STOCK, purchasePrice = "5.0")
        assertEquals(5.0, asset.currentPrice!!, 0.0)
        assertEquals(5.0, asset.purchasePrice, 0.0)
        assertEquals(500.0, asset.totalValue, 0.0)
    }

    @Test
    fun 既无行情也没手填单价时当前价为空_市值按0计() {
        val asset = build(AssetType.STOCK, purchasePrice = "")
        assertNull(asset.currentPrice)
        assertEquals(0.0, asset.purchasePrice, 0.0)
        assertEquals(0.0, asset.totalValue, 0.0)
    }

    @Test
    fun 非法数量与单价回落为0() {
        val asset = build(AssetType.STOCK, balance = "abc", purchasePrice = "不是数字")
        assertEquals(0.0, asset.balance, 0.0)
        assertEquals(0.0, asset.purchasePrice, 0.0)
    }

    @Test
    fun 空白备注落成null() {
        assertEquals("有内容", build(AssetType.STOCK, notes = "有内容").notes)
        assertNull(build(AssetType.STOCK, notes = "   ").notes)
    }

    @Test(expected = IllegalArgumentException::class)
    fun 空名称直接拒绝() {
        build(AssetType.STOCK, name = "  ")
    }

    @Test(expected = IllegalArgumentException::class)
    fun 负数量直接拒绝() {
        build(AssetType.STOCK, balance = "-1")
    }

    @Test(expected = IllegalArgumentException::class)
    fun 负参考单价直接拒绝() {
        build(AssetType.STOCK, purchasePrice = "-3")
    }
}
