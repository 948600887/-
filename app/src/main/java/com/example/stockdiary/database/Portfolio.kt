package com.example.stockdiary.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.stockdiary.utils.StockCodeManager

@Entity(tableName = "portfolio")
data class Portfolio(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val symbol: String,
    val name: String,
    val quantity: Double,
    val purchasePrice: Double,
    var currentPrice: Double,
    val account: String = "默认账户"
) {
    // 计算属性
    val totalValue: Double
        get() = quantity * currentPrice
    
    val profit: Double
        get() = totalValue - (quantity * purchasePrice)
    
    val profitPercentage: Double
        get() = if (purchasePrice > 0) (profit / (quantity * purchasePrice)) * 100 else 0.0
    
    // 判断是否为ETF
    val isETF: Boolean
        get() {
            val code = StockCodeManager.extractDigits(symbol)
            if (code.length < 3) return false
            val prefix = code.substring(0, 3)
            return prefix in setOf("510", "511", "512", "513", "515", "516", "517", "518", "588") || 
                   code.startsWith("159")
        }
    
    // 获取价格精度（ETF为3位小数，股票为2位）
    val pricePrecision: Int
        get() = if (isETF) 3 else 2
}