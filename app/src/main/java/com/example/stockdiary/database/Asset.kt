package com.example.stockdiary.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "assets",
    indices = [
        Index(value = ["symbol"]),
        Index(value = ["type"]),
        Index(value = ["account"]),
        Index(value = ["currency"])
    ]
)
data class Asset(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val type: AssetType,
    val symbol: String? = null,
    val account: String,
    val balance: Double = 0.0,
    val purchasePrice: Double = 0.0,
    val currentPrice: Double? = null,
    val currency: String = "CNY",
    val lastSyncTime: Long = 0L,
    val lastUpdateTime: Long = 0L,
    val notes: String? = null,
    val interestRate: Double? = null,
    val dueDate: String? = null
) {
    init {
        require(name.isNotBlank()) { "资产名称不能为空" }
        require(balance >= 0) { "数量不能为负数" }
        // purchasePrice 现在只作「参考单价」：无行情时的市值估算兜底，不再是成本
        require(purchasePrice >= 0) { "参考单价不能为负数" }
    }
    val totalValue: Double
        get() = if (type.isLiability || type == AssetType.BANK_DEPOSIT) {
            balance
        } else {
            balance * (currentPrice ?: purchasePrice)
        }
    
    val profit: Double
        get() = if (type.isLiability) {
            0.0
        } else {
            totalValue - (balance * purchasePrice)
        }
    
    val profitPercent: Double
        get() = if (type.isLiability || purchasePrice <= 0 || balance <= 0) {
            0.0
        } else {
            ((currentPrice ?: purchasePrice) - purchasePrice) / purchasePrice * 100
        }
    
    val change: Double
        get() = if (currentPrice != null && currentPrice > 0 && !type.isLiability) {
            (currentPrice - purchasePrice) * balance
        } else 0.0
    
    val changePercent: Double
        get() = profitPercent
}
