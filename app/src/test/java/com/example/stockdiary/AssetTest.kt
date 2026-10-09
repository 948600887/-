package com.example.stockdiary

import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetSnapshot
import com.example.stockdiary.database.AssetType
import org.junit.Assert.*
import org.junit.Test

class AssetTest {
    
    @Test
    fun `Asset totalValue for stock calculates correctly`() {
        val asset = Asset(
            id = 1,
            name = "贵州茅台",
            type = AssetType.STOCK,
            symbol = "sh600519",
            account = "招商证券",
            balance = 100.0,
            purchasePrice = 1800.0,
            currentPrice = 2000.0
        )
        
        assertEquals(200000.0, asset.totalValue, 0.01)
    }
    
    @Test
    // 以下两个用例随「去成本」改造一起删除：
    // Asset.profit / profitPercent 已从实体上移除，界面不再展示盈亏
    fun `Bank deposit totalValue equals balance`() {
        val asset = Asset(
            id = 1,
            name = "工商银行",
            type = AssetType.BANK_DEPOSIT,
            account = "工商银行",
            balance = 100000.0
        )
        
        assertEquals(100000.0, asset.totalValue, 0.01)
    }
    
    @Test
    fun `Liability totalValue equals balance`() {
        val asset = Asset(
            id = 1,
            name = "房贷",
            type = AssetType.MORTGAGE,
            account = "建设银行",
            balance = 500000.0
        )
        
        assertEquals(500000.0, asset.totalValue, 0.01)
    }
    
    @Test
    fun `Asset with null currentPrice uses purchasePrice`() {
        val asset = Asset(
            id = 1,
            name = "易方达",
            type = AssetType.FUND,
            symbol = "110001",
            account = "天天基金",
            balance = 10000.0,
            purchasePrice = 3.0,
            currentPrice = null
        )
        
        assertEquals(30000.0, asset.totalValue, 0.01)
    }
}

class AssetSnapshotTest {
    
    @Test
    fun `AssetSnapshot fromAssets calculates correctly`() {
        val assets = listOf(
            Asset(
                id = 1,
                name = "工商银行",
                type = AssetType.BANK_DEPOSIT,
                account = "工商银行",
                balance = 200000.0,
                purchasePrice = 1.0
            ),
            Asset(
                id = 2,
                name = "贵州茅台",
                type = AssetType.STOCK,
                symbol = "sh600519",
                account = "招商证券",
                balance = 100.0,
                purchasePrice = 1800.0,
                currentPrice = 2000.0
            ),
            Asset(
                id = 3,
                name = "房贷",
                type = AssetType.MORTGAGE,
                account = "建设银行",
                balance = 500000.0
            )
        )
        
        val snapshot = AssetSnapshot.fromAssets(assets, "2024-01-15")
        
        assertEquals(400000.0, snapshot.totalAssets, 0.01)
        assertEquals(500000.0, snapshot.totalLiabilities, 0.01)
        assertEquals(-100000.0, snapshot.netAssets, 0.01)
        assertEquals(200000.0, snapshot.bankDepositValue, 0.01)
        assertEquals(200000.0, snapshot.stockValue, 0.01)
    }
    
    @Test
    fun `AssetSnapshot netAssets calculation`() {
        val assets = listOf(
            Asset(
                id = 1,
                name = "银行存款",
                type = AssetType.BANK_DEPOSIT,
                account = "银行",
                balance = 1000000.0,
                purchasePrice = 1.0
            )
        )
        
        val snapshot = AssetSnapshot.fromAssets(assets, "2024-01-15")
        
        assertEquals(1000000.0, snapshot.totalAssets, 0.01)
        assertEquals(0.0, snapshot.totalLiabilities, 0.01)
        assertEquals(1000000.0, snapshot.netAssets, 0.01)
    }
}

class AssetTypeTest {
    
    @Test
    fun `getAssetTypes returns only non-liability types`() {
        val assetTypes = AssetType.getAssetTypes()
        
        assertTrue(assetTypes.contains(AssetType.BANK_DEPOSIT))
        assertTrue(assetTypes.contains(AssetType.STOCK))
        assertTrue(assetTypes.contains(AssetType.FUND))
        assertTrue(assetTypes.contains(AssetType.CRYPTO))
        
        assertFalse(assetTypes.contains(AssetType.LOAN))
        assertFalse(assetTypes.contains(AssetType.CREDIT_CARD))
        assertFalse(assetTypes.contains(AssetType.MORTGAGE))
    }
    
    @Test
    fun `getLiabilityTypes returns only liability types`() {
        val liabilityTypes = AssetType.getLiabilityTypes()
        
        assertTrue(liabilityTypes.contains(AssetType.LOAN))
        assertTrue(liabilityTypes.contains(AssetType.CREDIT_CARD))
        assertTrue(liabilityTypes.contains(AssetType.MORTGAGE))
        assertTrue(liabilityTypes.contains(AssetType.CAR_LOAN))
        assertTrue(liabilityTypes.contains(AssetType.OTHER_LIABILITY))
        
        assertFalse(liabilityTypes.contains(AssetType.BANK_DEPOSIT))
        assertFalse(liabilityTypes.contains(AssetType.STOCK))
    }
    
    @Test
    fun `asset type display names are correct`() {
        assertEquals("银行存款", AssetType.BANK_DEPOSIT.displayName)
        assertEquals("股票", AssetType.STOCK.displayName)
        assertEquals("基金", AssetType.FUND.displayName)
        assertEquals("虚拟货币", AssetType.CRYPTO.displayName)
        assertEquals("贷款", AssetType.LOAN.displayName)
        assertEquals("信用卡", AssetType.CREDIT_CARD.displayName)
    }
}

class FormatTest {
    
    @Test
    fun `zero balance asset`() {
        val asset = Asset(
            id = 1,
            name = "空持仓",
            type = AssetType.STOCK,
            symbol = "sh600000",
            account = "券商",
            balance = 0.0,
            purchasePrice = 100.0,
            currentPrice = 90.0
        )

        assertEquals(0.0, asset.totalValue, 0.01)
    }

    @Test
    fun `crypto asset calculation`() {
        val asset = Asset(
            id = 1,
            name = "比特币",
            type = AssetType.CRYPTO,
            symbol = "BTCUSDT",
            account = "币安",
            balance = 1.5,
            purchasePrice = 300000.0,
            currentPrice = 450000.0
        )

        assertEquals(675000.0, asset.totalValue, 0.01)
    }
}
