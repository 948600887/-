package com.example.stockdiary.data

import org.jsoup.nodes.Element

/**
 * 分红 HTML 表格的通用表头解析辅助。
 *
 * 新浪（DividendService）与天天基金（FundDividendApiService）的表结构、单位都不同，
 * 但「按关键字解析表头列索引」这一步完全同构。抽出此处，避免两份脆弱的表头扫描逻辑
 * 各自演化、改一处漏另一处——这正是之前易出回归的地方。
 *
 * 语义与旧解析器保持一致：先声明的列优先占用单元格，以复原先前 when 分支
 * 「先匹配先占用」的顺序敏感行为（例如「分红发放日」应判给派息日列，
 * 而不能被泛化的「分红」关键字抢走该列）。
 */
object DividendHtmlParser {

    /**
     * 一列的逻辑定义。
     * @param key        逻辑列名，结果 Map 的键。
     * @param keywords   命中即视为该列的关键字（子串匹配）。
     * @param fallbackIndex 未命中时的回退列索引，默认 -1。
     */
    data class ColumnSpec(
        val key: String,
        val keywords: List<String>,
        val fallbackIndex: Int = -1
    )

    /**
     * 在表格中按 [specs] 顺序解析各逻辑列的索引。
     * @return 键为 [ColumnSpec.key]、值为列索引（含回退值）的 Map。
     */
    fun resolveHeaderIndices(table: Element, specs: List<ColumnSpec>): Map<String, Int> {
        val rows = table.select("tr")
        val claimed = mutableSetOf<Int>()
        val result = mutableMapOf<String, Int>()

        for (spec in specs) {
            var resolved = spec.fallbackIndex
            outer@ for (row in rows) {
                val cells = row.select("th, td")
                for ((col, cell) in cells.withIndex()) {
                    if (col in claimed) continue
                    val text = cell.text().trim()
                    if (spec.keywords.any { text.contains(it) }) {
                        resolved = col
                        claimed.add(col)
                        break@outer
                    }
                }
            }
            result[spec.key] = resolved
        }
        return result
    }
}
