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
    // ⚠️ 不再在 init 里做 require 校验。原因：Room 每次从游标构造实体都会跑 init，
    // 一旦库里存在历史脏行（例如旧版本导入留下的空白 name、负值 purchasePrice、
    // 非加密类的负 balance），init 会抛 IllegalArgumentException，导致 getAllAssets()
    // 这类读路径整批查询崩溃，主界面直接打不开。
    // 数据合法性改由数据写入侧统一把关（见 AssetRepository.sanitizeForWrite）：
    // 只有「新建/更新资产」入口才会校验并兜底，读任意历史行永远安全。
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
