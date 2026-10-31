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

    /**
     * 把文本里的数字（金额 / 数量）解析成 Double。
     *
     * 统一处理各导入源口径不一致的问题，集中放这里避免三份实现各写各的：
     * - 千分位分隔符（半角 "," 与全角 "，"）与空格
     * - 括号负数：(5.00) → -5.00（海外券商账单常见写法）
     * - 全角 / 异体负号归一：－ — ー 一 −（OCR 与部分导出会把 - 认成这些字形）
     *
     * [com.example.stockdiary.data.importer.GalaxyStatementParser.toNumber]、
     * [com.example.stockdiary.data.importer.BrokerageImportService.parseNumber]、
     * [com.example.stockdiary.data.importer.FundEStatementParser.parseNumber]
     * 都委托到这里，保证同一数字在不同导入源解析口径一致。
     */
    fun parseNumber(raw: String): Double? {
        var s = raw.trim()
            .replace(",", "")
            .replace("，", "")
            .replace(" ", "")
        if (s.isEmpty()) return null
        // 括号负：(5.00) → -5.00
        val parenthesized = s.startsWith("(") && s.endsWith(")")
        if (parenthesized) s = s.substring(1, s.length - 1)
        // 全角 / 异体负号归一
        for (dash in charArrayOf('－', '—', 'ー', '一', '−')) {
            s = s.replace(dash, '-')
        }
        val value = s.toDoubleOrNull() ?: return null
        return if (parenthesized) -value else value
    }
}
