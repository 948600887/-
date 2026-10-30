package com.example.stockdiary.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.stockdiary.utils.costCny
import com.example.stockdiary.utils.sumDebtValueCny
import com.example.stockdiary.utils.excludingDebts
import com.example.stockdiary.utils.debts
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
    // 以下三个字段是**历史遗留列**：App 去掉成本概念后界面已不再展示，
    // 但它们是 Room 的列（历史快照数据里已有值），删列需要数据库迁移，故保留写入。
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
    companion object {
        fun fromAssets(assets: List<Asset>, date: String, @Suppress("UNUSED_PARAMETER") previousSnapshot: AssetSnapshot? = null): AssetSnapshot {
            val assetsOnly = assets.excludingDebts()
            val liabilities = assets.debts()
            
            val stocks = assetsOnly.filter { it.type == AssetType.STOCK }
            val funds = assetsOnly.filter { it.type == AssetType.FUND }
            val cryptos = assetsOnly.filter { it.type == AssetType.CRYPTO }
            val bankDeposits = assetsOnly.filter { it.type == AssetType.BANK_DEPOSIT }
            
            val stockCost = stocks.sumOf { it.costCny }
            val fundCost = funds.sumOf { it.costCny }
            val cryptoCost = cryptos.sumOf { it.costCny }
            val bankDepositCost = bankDeposits.sumOf { it.costCny }

            val totalCost = stockCost + fundCost + cryptoCost + bankDepositCost

            // 同一份求和只算一次：原来 totalAssets/netAssets 各写一遍 sumOf，profit 也各写一遍
            val assetValue = assetsOnly.sumOf { it.totalValueCny }
            val liabilityValue = liabilities.sumDebtValueCny()
            val totalProfit = assetsOnly.sumOf { it.profitCny }

            return AssetSnapshot(
                date = date,
                timestamp = System.currentTimeMillis(),
                totalAssets = assetValue,
                totalLiabilities = liabilityValue,
                netAssets = assetValue - liabilityValue,
                totalCost = totalCost,
                totalProfit = totalProfit,
                yieldRate = if (totalCost > 0) (totalProfit / totalCost * 100) else 0.0,
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
