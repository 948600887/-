package com.example.stockdiary.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "stock_basic")
data class StockBasic(
    @PrimaryKey
    val code: String, // 股票代码，如"sh600000"
    val name: String, // 股票名称
    val market: String, // 市场，如"sh"、"sz"、"bj"
    val listDate: String? = null, // 上市日期
    val isETF: Boolean = false // 是否为ETF
) {
    // 计算属性：纯代码（不包含市场前缀）
    val pureCode: String
        get() = if (code.length > 2 && 
            (code.startsWith("sh") || code.startsWith("sz") || code.startsWith("bj"))) {
            code.substring(2)
        } else {
            code
        }
    
    // 计算属性：完整代码
    val fullCode: String
        get() = "$pureCode.${market.uppercase()}"
    
    // 计算属性：新浪API代码
    val sinaApiCode: String
        get() = code
    
    // 计算属性：显示代码
    val displayCode: String
        get() = "$pureCode.${market.uppercase()}"
}