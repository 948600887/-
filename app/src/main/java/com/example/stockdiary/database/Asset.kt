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
    val dueDate: String? = null,
    val millionCopiesIncome: Double? = null
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
    
    // 以下四个派生属性（profit / profitPercent / change / changePercent）
    // 随「去成本」改造一起删除：界面不再展示盈亏与当日涨跌，全项目零引用。
    // purchasePrice 保留 —— 它现在是**无行情时的市值兜底价**，不是成本。
}
