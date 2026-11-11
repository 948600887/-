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
    fun `分数股的数量与单价可在导出表内反算市值`() {
        val fractional = Asset(
            id = 11, name = "AAPL", type = AssetType.STOCK, symbol = "AAPL",
            account = "美股", balance = 0.4, purchasePrice = 90.0, currentPrice = 100.0,
            currency = "USD"
        )
        val cells = parseRow(CsvContent.assetRow(fractional))
        // 数量按 0 位展示会被截成 0，导出必须保住分数股
        assertEquals("0.4", cells[4])
        assertEquals("100", cells[6])
        assertEquals(40.0, cells[4].toDouble() * cells[6].toDouble(), 1e-9)
        assertEquals(40.0, cells[7].toDouble(), 1e-9)
    }

    @Test
    fun `基金净值与低价股单价不被 2 到 3 位截断`() {
        val fund = Asset(
            id = 12, name = "某基金", type = AssetType.FUND, symbol = "000001",
            account = "默认", balance = 1234.56, purchasePrice = 1.0, currentPrice = 1.234567
        )
        assertEquals("1.234567", parseRow(CsvContent.assetRow(fund))[6])

        val lowPriced = Asset(
            id = 13, name = "低价股", type = AssetType.STOCK, symbol = "000002",
            account = "默认", balance = 1000.0, purchasePrice = 3.0, currentPrice = 3.3333
        )
        assertEquals("3.3333", parseRow(CsvContent.assetRow(lowPriced))[6])
    }

    @Test
    fun `非零数量不会被导出成 0`() {
        val dust = Asset(
            id = 14, name = "碎股", type = AssetType.STOCK, symbol = "AAPL",
            account = "美股", balance = 0.00001, purchasePrice = 1.0, currentPrice = 100.0,
            currency = "USD"
        )
        // 4 位小数会把它舍成 0：宁可多留有效位，也不能出现「数量 0、市值有钱」
        assertEquals("0.00001", parseRow(CsvContent.assetRow(dust))[4])
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
    fun `分红导出区分每10份派息率与实际折算金额`() {
        val text = CsvContent.dividends(
            listOf(
                Dividend(symbol = "600900", name = "长江电力", amount = 10.0, date = "2026-06-01"),
                Dividend(symbol = "600900", name = "长江电力", amount = 2.5, date = "2026-07-01")
            ),
            // 持有 1000 股：10 元/10股 -> 1000 元，2.5 元/10股 -> 250 元
            listOf(stock)
        )
        assertTrue(text.contains("每10份派息(元)"))
        assertTrue(text.contains("每10份派息合计(元),12.5000"))
        assertTrue(text.contains("折算金额合计(人民币),1250.00"))
        assertTrue(text.contains("无法折算的分红记录数,0"))
        assertTrue(text.contains("记录数,2"))
    }

    @Test
    fun `文本报告包含账户信息`() {
        assertTrue(CsvContent.textReport(listOf(stock)).contains("账户: 默认"))
    }

    @Test
    fun `快照导出口径与历史页一致_同日去重且剔除前导空快照`() {
        // 同日两条（旧版回填遗物）+ 开头一条零资产空快照 + 一条正常数据
        val empty = AssetSnapshot(
            date = "2026-10-20", timestamp = 1L,
            totalAssets = 0.0, totalLiabilities = 0.0, netAssets = 0.0
        )
        val duplicateOld = AssetSnapshot(
            date = "2026-10-27", timestamp = 2L,
            totalAssets = 100.0, totalLiabilities = 0.0, netAssets = 100.0
        )
        val duplicateNew = AssetSnapshot(
            date = "2026-10-27", timestamp = 3L,
            totalAssets = 200.0, totalLiabilities = 0.0, netAssets = 200.0
        )
        val latest = AssetSnapshot(
            date = "2026-10-28", timestamp = 4L,
            totalAssets = 300.0, totalLiabilities = 0.0, netAssets = 300.0
        )

        val rows = dataSection(
            CsvContent.snapshots(listOf(empty, duplicateOld, duplicateNew, latest))
        ).drop(1)

        // 与历史页所见一致：10/20 空快照被剔除、10/27 只保留 timestamp 最新的一条
        assertEquals(2, rows.size)
        val first = parseRow(rows[0])
        assertEquals("2026-10-27", first[0])
        assertEquals(200.0, first[3].toDouble(), 1e-9)
        assertEquals("2026-10-28", parseRow(rows[1])[0])
    }

    @Test
    fun `负微小值不被舍成0_与正数对称`() {
        // 数量列走 csvExportNumber(STOCK, 4 位)：负微小值被舍成 "-0" 时不能提前返回 "0"，
        // 必须退回原值；-0.4 正常保留；真正的 0 才是 "0"。
        fun quantityCell(value: Double): String =
            parseRow(CsvContent.assetRow(stock.copy(balance = value)))[4]
        assertEquals("-0.00001", quantityCell(-0.00001))
        assertEquals("-0.4", quantityCell(-0.4))
        assertEquals("0", quantityCell(0.0))
    }

    @Test
    fun `数量乘单价与金额列误差在容差内_近似而非严格守恒`() {
        val tx = Transaction(
            symbol = "600900", name = "长江电力", type = "BUY",
            quantity = 123.456, price = 7.89, date = "2026-10-01", fee = 0.0
        )
        val cells = parseRow(CsvContent.transactionRow(tx, AssetLookup(listOf(stock))))
        val product = cells[4].toDouble() * cells[5].toDouble()
        val amount = cells[7].toDouble()
        // 数量/单价列去尾零按导出精度写、金额列固定 2 位：两者是「近似可反算」，
        // 不是严格守恒；误差上限取金额列半个最小单位 0.005。
        assertTrue(
            "|数量×单价 - 金额| 应 ≤ 0.005，实际 $product vs $amount",
            kotlin.math.abs(product - amount) <= 0.005
        )
    }
}
