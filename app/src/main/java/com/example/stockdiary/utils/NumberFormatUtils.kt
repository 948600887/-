package com.example.stockdiary.utils

/**
 * 各导入/快照解析器共用的数值格式化工具，集中放置避免多处重复定义同款函数。
 */
object NumberFormatUtils {

    /**
     * 整数值不带小数尾（如 100.0 → "100"），非整数保留原样。
     * 用于把 Double 数量写回「原始数量」文本字段，避免落 "100.0" 这种多余尾。
     */
    fun formatPlain(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
}
