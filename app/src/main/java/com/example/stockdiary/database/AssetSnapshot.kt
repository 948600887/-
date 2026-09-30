package com.example.stockdiary.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.stockdiary.utils.costCny
import com.example.stockdiary.utils.profitCny
import com.example.stockdiary.utils.totalValueCny

@Entity(
    tableName = "asset_snapshots",
    indices = [Index(value = ["date"]), Index(value = ["timestamp"])]
)
data class AssetSnapshot(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long = 0L,
    val date: String,
    val totalAssets: Double,
    val totalLiabilities: Double,
    val netAssets: Double,
    val totalCost: Double = 0.0,
    val totalProfit: Double = 0.0,
    val yieldRate: Double = 0.0,
    val stockValue: Double = 0.0,
    val fundValue: Double = 0.0,
    val cryptoValue: Double = 0.0,
    val bankDepositValue: Double = 0.0,
    val stockCost: Double = 0.0,
    val fundCost: Double = 0.0,
    val cryptoCost: Double = 0.0,
    val bankDepositCost: Double = 0.0
) {
    val stockProfit: Double
        get() = stockValue - stockCost
    
    val fundProfit: Double
        get() = fundValue - fundCost
    
    val cryptoProfit: Double
        get() = cryptoValue - cryptoCost
    
    val bankDepositProfit: Double
        get() = bankDepositValue - bankDepositCost
    
    val stockYieldRate: Double
        get() = if (stockCost > 0) (stockValue - stockCost) / stockCost * 100 else 0.0
    
    val fundYieldRate: Double
        get() = if (fundCost > 0) (fundValue - fundCost) / fundCost * 100 else 0.0
    
    val cryptoYieldRate: Double
        get() = if (cryptoCost > 0) (cryptoValue - cryptoCost) / cryptoCost * 100 else 0.0
    
    val bankDepositYieldRate: Double
        get() = if (bankDepositCost > 0) (bankDepositValue - bankDepositCost) / bankDepositCost * 100 else 0.0
    
    companion object {
        fun fromAssets(assets: List<Asset>, date: String, @Suppress("UNUSED_PARAMETER") previousSnapshot: AssetSnapshot? = null): AssetSnapshot {
            val assetsOnly = assets.filter { !it.type.isLiability }
            val liabilities = assets.filter { it.type.isLiability }
            
            val stocks = assetsOnly.filter { it.type == AssetType.STOCK }
            val funds = assetsOnly.filter { it.type == AssetType.FUND }
            val cryptos = assetsOnly.filter { it.type == AssetType.CRYPTO }
            val bankDeposits = assetsOnly.filter { it.type == AssetType.BANK_DEPOSIT }
            
            val stockCost = stocks.sumOf { it.costCny }
            val fundCost = funds.sumOf { it.costCny }
            val cryptoCost = cryptos.sumOf { it.costCny }
            val bankDepositCost = bankDeposits.sumOf { it.costCny }

            val totalCost = stockCost + fundCost + cryptoCost + bankDepositCost

            return AssetSnapshot(
                date = date,
                timestamp = System.currentTimeMillis(),
                totalAssets = assetsOnly.sumOf { it.totalValueCny },
                totalLiabilities = liabilities.sumOf { it.totalValueCny },
                netAssets = assetsOnly.sumOf { it.totalValueCny } - liabilities.sumOf { it.totalValueCny },
                totalCost = totalCost,
                totalProfit = assetsOnly.sumOf { it.profitCny },
                yieldRate = if (totalCost > 0) (assetsOnly.sumOf { it.profitCny } / totalCost * 100) else 0.0,
                stockValue = stocks.sumOf { it.totalValueCny },
                fundValue = funds.sumOf { it.totalValueCny },
                cryptoValue = cryptos.sumOf { it.totalValueCny },
                bankDepositValue = bankDeposits.sumOf { it.totalValueCny },
                stockCost = stockCost,
                fundCost = fundCost,
                cryptoCost = cryptoCost,
                bankDepositCost = bankDepositCost
            )
        }
    }
}

@Entity(
    tableName = "asset_snapshot_details",
    indices = [Index(value = ["snapshotId"]), Index(value = ["assetId"])]
)
data class AssetSnapshotDetail(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val snapshotId: Long,
    val assetId: Long,
    val assetName: String,
    val assetType: AssetType,
    val balance: Double,
    val price: Double,
    val totalValue: Double,
    val cost: Double,
    val profit: Double,
    val yieldRate: Double
)
