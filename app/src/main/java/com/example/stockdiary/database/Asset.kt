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
        // 加密货币允许负余额：币安借币 / 组合保证金欠币在账户接口里 free+locked 就是负值，
        // 负余额按设计落成一条数量为负的 CRYPTO 资产，参与净值计算时自动扣减
        // （见 BinanceImportService 的 dust 过滤注释与 SnapshotAlignment.commit）。
        // 其它类型（股票 / 基金 / 存款 / 贷款 / 负债）数量必须是非负，保持原约束——
        // 这些类型出现负值是数据错误，应当直接拒绝，而不是静默存成脏数据。
        require(balance >= 0 || type == AssetType.CRYPTO) { "数量不能为负数" }
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
