package com.example.stockdiary.ui.screens

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import org.junit.Rule
import org.junit.Test

class DashboardScreenTest {
    
    @get:Rule
    val composeTestRule = createComposeRule()
    
    @Test
    fun dashboard_showsEmptyStateWhenNoAssets() {
        composeTestRule.setContent {
            EmptyAssetCard(onAddAsset = {})
        }
        
        composeTestRule.onNodeWithText("还没有添加任何资产").assertIsDisplayed()
        composeTestRule.onNodeWithText("点击下方按钮添加你的第一笔资产").assertIsDisplayed()
        composeTestRule.onNodeWithText("添加资产").assertIsDisplayed()
    }
    
    @Test
    fun emptyAssetCard_clickTriggersCallback() {
        var clicked = false
        
        composeTestRule.setContent {
            EmptyAssetCard(onAddAsset = { clicked = true })
        }
        
        composeTestRule.onNodeWithText("添加资产").performClick()
        
        assert(clicked)
    }
}

class AssetItemTest {
    
    @get:Rule
    val composeTestRule = createComposeRule()
    
    @Test
    fun assetItem_displaysAssetName() {
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
        
        composeTestRule.setContent {
            AssetItem(asset = asset, onClick = {})
        }
        
        composeTestRule.onNodeWithText("贵州茅台").assertIsDisplayed()
    }
    
    @Test
    fun assetItem_displaysSymbol() {
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
        
        composeTestRule.setContent {
            AssetItem(asset = asset, onClick = {})
        }
        
        composeTestRule.onNodeWithText("sh600519").assertIsDisplayed()
    }
    
    @Test
    fun assetItem_clickTriggersCallback() {
        var clicked = false
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
        
        composeTestRule.setContent {
            AssetItem(asset = asset, onClick = { clicked = true })
        }
        
        composeTestRule.onNodeWithText("贵州茅台").performClick()
        
        assert(clicked)
    }
    
    @Test
    fun assetItem_displaysTotalValue() {
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
        
        composeTestRule.setContent {
            AssetItem(asset = asset, onClick = {})
        }
        
        composeTestRule.onNodeWithText("¥200,000.00").assertIsDisplayed()
    }
}

class LiabilityItemTest {
    
    @get:Rule
    val composeTestRule = createComposeRule()
    
    @Test
    fun liabilityItem_displaysLiabilityName() {
        val asset = Asset(
            id = 1,
            name = "房屋贷款",
            type = AssetType.MORTGAGE,
            account = "建设银行",
            balance = 500000.0
        )
        
        composeTestRule.setContent {
            LiabilityItem(asset = asset, onClick = {})
        }
        
        composeTestRule.onNodeWithText("房屋贷款").assertIsDisplayed()
    }
    
    @Test
    fun liabilityItem_displaysNegativeValue() {
        val asset = Asset(
            id = 1,
            name = "房屋贷款",
            type = AssetType.MORTGAGE,
            account = "建设银行",
            balance = 500000.0
        )
        
        composeTestRule.setContent {
            LiabilityItem(asset = asset, onClick = {})
        }
        
        composeTestRule.onNodeWithText("-¥500,000.00").assertIsDisplayed()
    }
}

class TotalAssetCardTest {
    
    @get:Rule
    val composeTestRule = createComposeRule()
    
    @Test
    fun totalAssetCard_displaysNetAssets() {
        composeTestRule.setContent {
            TotalAssetCard(
                showTotalAssets = true,
                showNetAssets = true,
                showProfit = true,
                totalAssets = 1000000.0,
                totalLiabilities = 200000.0,
                netAssets = 800000.0,
                profit = 50000.0,
                profitPercent = 6.25,
                realAnnualIncome = 40000.0,
                dividendLoading = false
            )
        }
        
        composeTestRule.onNodeWithText("净资产").assertIsDisplayed()
        composeTestRule.onNodeWithText("¥800,000.00").assertIsDisplayed()
    }
    
    @Test
    fun totalAssetCard_displaysTotalAssetsAndLiabilities() {
        composeTestRule.setContent {
            TotalAssetCard(
                showTotalAssets = true,
                showNetAssets = true,
                showProfit = true,
                totalAssets = 1000000.0,
                totalLiabilities = 200000.0,
                netAssets = 800000.0,
                profit = 50000.0,
                profitPercent = 6.25,
                realAnnualIncome = 40000.0,
                dividendLoading = false
            )
        }
        
        composeTestRule.onNodeWithText("总资产").assertIsDisplayed()
        composeTestRule.onNodeWithText("总负债").assertIsDisplayed()
        composeTestRule.onNodeWithText("¥1,000,000.00").assertIsDisplayed()
        composeTestRule.onNodeWithText("¥200,000.00").assertIsDisplayed()
    }
    
    @Test
    fun totalAssetCard_displaysProfitWithPositiveValue() {
        composeTestRule.setContent {
            TotalAssetCard(
                showTotalAssets = true,
                showNetAssets = true,
                showProfit = true,
                totalAssets = 1000000.0,
                totalLiabilities = 200000.0,
                netAssets = 800000.0,
                profit = 50000.0,
                profitPercent = 6.25,
                realAnnualIncome = 40000.0,
                dividendLoading = false
            )
        }
        
        composeTestRule.onNodeWithText("+¥50,000.00").assertIsDisplayed()
    }
    
    @Test
    fun totalAssetCard_displaysLossWithNegativeValue() {
        composeTestRule.setContent {
            TotalAssetCard(
                showTotalAssets = true,
                showNetAssets = true,
                showProfit = true,
                totalAssets = 1000000.0,
                totalLiabilities = 200000.0,
                netAssets = 800000.0,
                profit = -50000.0,
                profitPercent = -6.25,
                realAnnualIncome = 40000.0,
                dividendLoading = false
            )
        }
        
        composeTestRule.onNodeWithText("-¥50,000.00").assertIsDisplayed()
    }
}

class AssetTypeSectionTest {
    
    @get:Rule
    val composeTestRule = createComposeRule()
    
    @Test
    fun assetTypeSection_displaysTypeName() {
        val assets = listOf(
            Asset(
                id = 1,
                name = "工商银行",
                type = AssetType.BANK_DEPOSIT,
                account = "工商银行",
                balance = 200000.0
            )
        )
        
        composeTestRule.setContent {
            AssetTypeSection(
                type = AssetType.BANK_DEPOSIT,
                assets = assets,
                isExpanded = false,
                onToggleExpand = {}
            )
        }
        
        composeTestRule.onNodeWithText("银行存款").assertIsDisplayed()
    }
    
    @Test
    fun assetTypeSection_displaysAssetCount() {
        val assets = listOf(
            Asset(id = 1, name = "工商银行", type = AssetType.BANK_DEPOSIT, account = "工商银行", balance = 200000.0),
            Asset(id = 2, name = "建设银行", type = AssetType.BANK_DEPOSIT, account = "建设银行", balance = 300000.0)
        )
        
        composeTestRule.setContent {
            AssetTypeSection(
                type = AssetType.BANK_DEPOSIT,
                assets = assets,
                isExpanded = false,
                onToggleExpand = {}
            )
        }
        
        composeTestRule.onNodeWithText("2个账户").assertIsDisplayed()
    }
    
    @Test
    fun assetTypeSection_displaysTotalValue() {
        val assets = listOf(
            Asset(id = 1, name = "工商银行", type = AssetType.BANK_DEPOSIT, account = "工商银行", balance = 200000.0)
        )
        
        composeTestRule.setContent {
            AssetTypeSection(
                type = AssetType.BANK_DEPOSIT,
                assets = assets,
                isExpanded = false,
                onToggleExpand = {}
            )
        }
        
        composeTestRule.onNodeWithText("¥200,000.00").assertIsDisplayed()
    }
    
    @Test
    fun assetTypeSection_clickTogglesExpand() {
        var expanded = false
        val assets = listOf(
            Asset(id = 1, name = "工商银行", type = AssetType.BANK_DEPOSIT, account = "工商银行", balance = 200000.0)
        )
        
        composeTestRule.setContent {
            AssetTypeSection(
                type = AssetType.BANK_DEPOSIT,
                assets = assets,
                isExpanded = expanded,
                onToggleExpand = { expanded = !expanded }
            )
        }
        
        composeTestRule.onNodeWithText("银行存款").performClick()
        
        assert(expanded)
    }
}

class DistributionItemTest {
    
    @get:Rule
    val composeTestRule = createComposeRule()
    
    @Test
    fun distributionItem_displaysLabel() {
        composeTestRule.setContent {
            DistributionItem(
                icon = "💳",
                label = "银行存款",
                value = 500000.0,
                percent = 50.0
            )
        }
        
        composeTestRule.onNodeWithText("银行存款").assertIsDisplayed()
    }
    
    @Test
    fun distributionItem_displaysPercentage() {
        composeTestRule.setContent {
            DistributionItem(
                icon = "💳",
                label = "银行存款",
                value = 500000.0,
                percent = 50.0
            )
        }
        
        composeTestRule.onNodeWithText("50.0%").assertIsDisplayed()
    }
    
    @Test
    fun distributionItem_formatsLargeValuesInWan() {
        composeTestRule.setContent {
            DistributionItem(
                icon = "💳",
                label = "银行存款",
                value = 10000000.0,
                percent = 50.0
            )
        }
        
        composeTestRule.onNodeWithText("1000.0万").assertIsDisplayed()
    }
}
