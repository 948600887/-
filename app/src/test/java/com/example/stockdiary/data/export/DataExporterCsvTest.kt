package com.example.stockdiary.data.export

import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetSnapshot
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.database.Dividend
import com.example.stockdiary.database.Transaction
import com.example.stockdiary.utils.ExchangeRateManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 导出内容的回归测试。
 *
 * 导出是「改一行就可能列错位/静默丢精度」的典型场景，且用户拿它去对账，
 * 错了不会报错、只会得到一个看起来正常的错文件。这里钉住：
 * 列数一致、转义、加密精度、多账户/已删资产的币种处理、手续费折算。
 */
class DataExporterCsvTest {

    @Before
    fun setUp() {
        ExchangeRateManager.resetForTesting()
    }

    @After
    fun tearDown() {
        ExchangeRateManager.resetForTesting()
    }

    private val stock = Asset(
        id = 1, name = "长江电力", type = AssetType.STOCK, symbol = "600900",
        account = "默认", balance = 1000.0, purchasePrice = 20.0, currentPrice = 30.0
    )
    private val deposit = Asset(
        id = 2, name = "定期存款", type = AssetType.BANK_DEPOSIT, symbol = null,
        account = "银行", balance = 100_000.0, interestRate = 3.0
    )
    private val btc = Asset(
        id = 3, name = "Bitcoin", type = AssetType.CRYPTO, symbol = "BTCUSDT",
        account = "币安", balance = 0.0001234567, purchasePrice = 0.0,
        currentPrice = 60_000.0, currency = "USD"
    )
    private val btcTx = Transaction(
        symbol = "BTCUSDT", name = "Bitcoin", type = "BUY",
        quantity = 0.0001234567, price = 60_000.123456789,
        date = "2026-10-01", fee = 5.0
    )

    /** 最小 CSV 解析：支持双引号包裹与内部 "" 转义，供列数/内容断言使用 */
    private fun parseRow(line: String): List<String> {
        val cells = mutableListOf<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    cell.append('"')
                    i++
                }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> {
                    cells.add(cell.toString())
                    cell.clear()
                }
                else -> cell.append(c)
            }
            i++
        }
        cells.add(cell.toString())
        return cells
    }

    private fun dataSection(text: String): List<String> =
        text.lineSequence().takeWhile { it.isNotBlank() }.filter { it.isNotBlank() }.toList()

    @Test
    fun `资产 CSV 表头与每一数据行列数一致`() {
        val text = CsvContent.assets(listOf(stock, deposit))
        val rows = dataSection(text)
        rows.forEach { line -> assertEquals("列数不一致: $line", 15, parseRow(line).size) }
        assertEquals(15, CsvContent.ASSET_HEADER.size)
    }

    @Test
    fun `逗号与引号被正确转义后仍是一列`() {
        assertEquals("\"a,b\"", escapeCsv("a,b"))
        assertEquals("\"a\"\"b\"", escapeCsv("a\"b"))
        assertEquals("\"a\nb\"", escapeCsv("a\nb"))
        assertEquals("abc", escapeCsv("abc"))

        val weird = Asset(
            id = 9, name = "长,江\"电", type = AssetType.STOCK, symbol = "600900",
            account = "默认", balance = 1.0, purchasePrice = 1.0
        )
        val cells = parseRow(CsvContent.assetRow(weird))
        assertEquals("长,江\"电", cells[0])
        assertEquals(15, cells.size)
    }

    @Test
    fun `存款的单价列留空而不是 0`() {
        val cells = parseRow(CsvContent.assetRow(deposit))
        // 存款没有行情：数量是本金、总价值=本金，单价列留空，避免「数量 10 万、单价 0」的自相矛盾
        assertEquals("", cells[5])
        assertEquals("", cells[6])
        assertEquals(100_000.0, cells[4].toDouble(), 1e-9)
        assertEquals(100_000.0, cells[7].toDouble(), 1e-9)
        assertEquals("3.00", cells[13])
    }

    @Test
    fun `加密货币交易不再被截断成 2 位精度`() {
        val cells = parseRow(CsvContent.transactionRow(btcTx, AssetLookup(listOf(btc))))
        assertEquals(12, cells.size)
        assertEquals("0.0001234567", cells[4])
        assertEquals(60_000.123456789, cells[5].toDouble(), 1e-6)
        assertEquals("USD", cells[8])
        // 人民币 = 数量 × 价格 × 7.25
        assertEquals(0.0001234567 * 60_000.123456789 * 7.25, cells[9].toDouble(), 0.01)
    }

    @Test
    fun `多账户同代码时账户标多账户且不猜币种`() {
        val a = Asset(
            id = 4, name = "长电A", type = AssetType.STOCK, symbol = "600900",
            account = "账户A", balance = 100.0, purchasePrice = 1.0
        )
        val b = Asset(
            id = 5, name = "长电B", type = AssetType.STOCK, symbol = "600900",
            account = "账户B", balance = 100.0, purchasePrice = 1.0, currency = "USD"
        )
        val tx = Transaction(
            symbol = "600900", name = "长电", type = "BUY",
            quantity = 100.0, price = 20.0, date = "2026-10-01"
        )
        val cells = parseRow(CsvContent.transactionRow(tx, AssetLookup(listOf(a, b))))
        assertEquals("多账户", cells[10])
        // 两个账户币种不同 → 无法确定，宁可留空也不按人民币静默折算
        assertEquals("", cells[8])
        assertEquals("", cells[9])
    }

    @Test
    fun `资产已删除时币种留空并计入无法折算`() {
        val text = CsvContent.transactions(emptyList(), listOf(btcTx))
        val firstDataRow = dataSection(text)[1]
        val cells = parseRow(firstDataRow)
        assertEquals("", cells[8])
        assertEquals("", cells[9])
        assertTrue(text.contains("无法折算的交易(资产已删除或币种不明),1"))
    }

    @Test
    fun `手续费按原币折算成人民币`() {
        // 旧实现把 5 美元和 10 元直接相加；这里 5 USD × 7.25 = 36.25
        val text = CsvContent.transactions(listOf(btc), listOf(btcTx))
        assertTrue(text.contains("总手续费(人民币),36.25"))
    }

    @Test
    fun `综合对账单所有行列数一致且包含分红与快照`() {
        val snapshot = AssetSnapshot(
            date = "2026-10-28", totalAssets = 100.0, totalLiabilities = 0.0, netAssets = 100.0
        )
        val dividend = Dividend(
            symbol = "600900", name = "长江电力", amount = 10.0,
            date = "2026-06-01", payDate = "2026-06-15"
        )
        val text = CsvContent.statement(
            listOf(stock, btc), listOf(btcTx), listOf(snapshot), listOf(dividend)
        )
        text.lines().filter { it.isNotBlank() }.forEach { line ->
            assertEquals("列数不一致: $line", 13, parseRow(line).size)
        }
        assertTrue(text.contains("分红"))
        assertTrue(text.contains("快照"))
        assertTrue(text.contains("到账日: 2026-06-15"))
    }

    @Test
    fun `分红导出带合计`() {
        val text = CsvContent.dividends(
            listOf(
                Dividend(symbol = "600900", name = "长江电力", amount = 10.0, date = "2026-06-01"),
                Dividend(symbol = "600900", name = "长江电力", amount = 2.5, date = "2026-07-01")
            )
        )
        assertTrue(text.contains("分红合计(元),12.50"))
        assertTrue(text.contains("记录数,2"))
    }

    @Test
    fun `文本报告包含账户信息`() {
        assertTrue(CsvContent.textReport(listOf(stock)).contains("账户: 默认"))
    }
}
