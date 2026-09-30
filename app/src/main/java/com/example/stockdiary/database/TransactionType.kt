package com.example.stockdiary.database

/**
 * 交易类型常量，统一使用英文枚举，避免 SQL/代码中英文混用导致聚合结果错误。
 */
enum class TransactionType(val value: String) {
    BUY("BUY"),
    SELL("SELL");

    companion object {
        fun fromValue(value: String?): TransactionType? {
            return entries.find { it.value.equals(value, ignoreCase = true) }
        }
    }
}
