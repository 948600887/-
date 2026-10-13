package com.example.stockdiary.data

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Test

class DividendHtmlParserTest {

    @Test
    fun `新浪表头 公告日期-派息-除权除息日 解析正确`() {
        val html = """
            <table>
              <tr><th>公告日期</th><th>送股(股)</th><th>转增(股)</th>
                  <th>派息(税前)(元)</th><th>进度</th><th>除权除息日</th></tr>
              <tr><td>2023-06-01</td><td>0</td><td>0</td><td>1.00</td><td>实施</td><td>2023-06-08</td></tr>
            </table>
        """.trimIndent()
        val table = Jsoup.parse(html).select("table").first()!!

        val idx = DividendHtmlParser.resolveHeaderIndices(
            table,
            listOf(
                DividendHtmlParser.ColumnSpec("announceDate", listOf("公告日期", "公告日"), 0),
                DividendHtmlParser.ColumnSpec("dividend", listOf("派息"), 3),
                DividendHtmlParser.ColumnSpec("exDate", listOf("除权除息日", "除息日"), 5)
            )
        )

        assertEquals(0, idx["announceDate"])
        assertEquals(3, idx["dividend"])      // 派息(税前)(元) 在第 3 列
        assertEquals(5, idx["exDate"])
    }

    @Test
    fun `天天基金表头 年份-权益登记日-除息日-每10份分红-分红发放日 解析正确`() {
        val html = """
            <table>
              <tr><th>年份</th><th>权益登记日</th><th>除息日</th>
                  <th>每10份分红</th><th>分红发放日</th></tr>
              <tr><td>2023</td><td>2023-06-05</td><td>2023-06-06</td><td>1.20</td><td>2023-06-09</td></tr>
            </table>
        """.trimIndent()
        val table = Jsoup.parse(html).select("table").first()!!

        val idx = DividendHtmlParser.resolveHeaderIndices(
            table,
            listOf(
                DividendHtmlParser.ColumnSpec("exDate", listOf("除息日"), 2),
                DividendHtmlParser.ColumnSpec("regDate", listOf("权益登记日"), 1),
                DividendHtmlParser.ColumnSpec("payDate", listOf("分红发放日", "派息日"), 4),
                DividendHtmlParser.ColumnSpec(
                    "dividend",
                    listOf("每10份分红", "每份分红", "每10份派", "每份派", "分红"),
                    3
                )
            )
        )

        assertEquals(1, idx["regDate"])
        assertEquals(2, idx["exDate"])
        assertEquals(3, idx["dividend"])
        assertEquals(4, idx["payDate"])
    }

    @Test
    fun `分红发放日 必须判给 payDate 而非被泛化分红抢走`() {
        // 仅两列：[分红发放日, 每10份分红]，验证顺序敏感与 claimed 逻辑
        val html = """
            <table>
              <tr><th>分红发放日</th><th>每10份分红</th></tr>
            </table>
        """.trimIndent()
        val table = Jsoup.parse(html).select("table").first()!!

        val idx = DividendHtmlParser.resolveHeaderIndices(
            table,
            listOf(
                DividendHtmlParser.ColumnSpec("exDate", listOf("除息日"), 2),
                DividendHtmlParser.ColumnSpec("regDate", listOf("权益登记日"), 1),
                DividendHtmlParser.ColumnSpec("payDate", listOf("分红发放日", "派息日"), 4),
                DividendHtmlParser.ColumnSpec(
                    "dividend",
                    listOf("每10份分红", "每份分红", "每10份派", "每份派", "分红"),
                    3
                )
            )
        )

        assertEquals(0, idx["payDate"])   // 分红发放日 → payDate
        assertEquals(1, idx["dividend"])  // 每10份分红 → dividend（不能抢走 0 列）
    }

    @Test
    fun `未命中列 回退到 fallbackIndex`() {
        val html = """<table><tr><th>其它</th><th>列</th></tr></table>"""
        val table = Jsoup.parse(html).select("table").first()!!

        val idx = DividendHtmlParser.resolveHeaderIndices(
            table,
            listOf(
                DividendHtmlParser.ColumnSpec("exDate", listOf("除息日"), 2),
                DividendHtmlParser.ColumnSpec("dividend", listOf("派息"), 3)
            )
        )

        assertEquals(2, idx["exDate"])    // 回退
        assertEquals(3, idx["dividend"])  // 回退
    }
}
