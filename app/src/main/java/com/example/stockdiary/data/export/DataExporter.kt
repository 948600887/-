package com.example.stockdiary.data.export

import com.example.stockdiary.common.DateFormats
import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.stockdiary.common.Result
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetSnapshot
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.database.Dividend
import com.example.stockdiary.database.Transaction
import com.example.stockdiary.database.TransactionType
import com.example.stockdiary.utils.AppLogger
import com.example.stockdiary.utils.CRYPTO_DECIMALS
import com.example.stockdiary.utils.ExchangeRateManager
import com.example.stockdiary.utils.MONEY_DIGITS
import com.example.stockdiary.utils.StockCodeManager
import com.example.stockdiary.utils.pricePrecisionFor
import com.example.stockdiary.utils.quantityPrecisionFor
import com.example.stockdiary.utils.sumTotalValueCny
import com.example.stockdiary.utils.sumDebtValueCny
import com.example.stockdiary.utils.excludingDebts
import com.example.stockdiary.utils.debts
import com.example.stockdiary.utils.toFileTimestamp
import com.example.stockdiary.utils.totalValueCny
import java.io.File
import java.io.OutputStreamWriter
import java.io.Writer
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.util.Locale

/**
 * 导出文件生成器。
 *
 * 分两层：
 * - [CsvContent] 只负责「把数据拼成文件内容」，纯函数、无 Android 依赖，可直接单测；
 * - 本类负责落盘、FileProvider URI、清理旧文件。
 *
 * 所有导出方法返回 [Result]：失败时把原因一路带到界面，而不是只回一个 null
 * 让设置页干巴巴地显示「导出失败」。
 */
class DataExporter(private val context: Context) {

    companion object {
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: DataExporter? = null

        /** 导出/诊断文件最多保留份数：缓存目录只增不删会一直堆着历史文件 */
        internal const val MAX_EXPORT_FILES = 5

        fun getInstance(context: Context): DataExporter {
            return instance ?: synchronized(this) {
                instance ?: DataExporter(context.applicationContext).also { instance = it }
            }
        }
    }

    private val exportDir: File
        get() = File(context.cacheDir, "exports").apply { mkdirs() }

    /**
     * 打开一个**带 UTF-8 BOM** 的写入器，供所有 CSV / 文本导出使用。
     *
     * 不能再用 `FileWriter(file)`：它走平台默认字符集且**不输出 BOM**，
     * Windows 中文版 Excel 打开无 BOM 的 CSV 会按 GBK/ANSI 解码，
     * 表头「名称,类型,代码,账户…」与全部中文资产名/备注直接变乱码 ——
     * 而导出按钮的目的就是给表格软件打开，等于该功能实际不可用。
     * 显式 UTF-8 + BOM 后 Excel 能正确识别编码。
     */
    private fun openWriter(file: File): Writer {
        val writer = OutputStreamWriter(file.outputStream(), StandardCharsets.UTF_8)
        writer.write('\uFEFF'.toString()) // UTF-8 BOM
        return writer
    }

    fun exportAssetsToCSV(assets: List<Asset>): Result<Uri> =
        exportFile("assets", "csv") { it.append(CsvContent.assets(assets)) }

    fun exportSnapshotsToCSV(snapshots: List<AssetSnapshot>): Result<Uri> =
        exportFile("snapshots", "csv") { it.append(CsvContent.snapshots(snapshots)) }

    fun exportDividendsToCSV(dividends: List<Dividend>): Result<Uri> =
        exportFile("dividends", "csv") { it.append(CsvContent.dividends(dividends)) }

    fun exportTransactionsToCSV(assets: List<Asset>, transactions: List<Transaction>): Result<Uri> =
        exportFile("statement", "csv") { it.append(CsvContent.transactions(assets, transactions)) }

    /**
     * 导出综合对账单（资产 + 交易 + 快照 + 分红）。
     *
     * 旧实现把四段拼在一个文件里、每段列数不同，Excel 打开是一张错位表，
     * 程序化解析（pandas 等）会直接报列数不齐。现在统一成**一张长表**：
     * 首列「区块」区分 资产 / 交易 / 快照 / 分红 / 汇总，列定义固定 13 列，
     * 不适用的单元格留空。这样既能当人类可读的对账单，也能被表格软件筛选、透视。
     */
    fun exportStatementCSV(
        assets: List<Asset>,
        transactions: List<Transaction>,
        snapshots: List<AssetSnapshot>,
        dividends: List<Dividend>
    ): Result<Uri> =
        exportFile("full_statement", "csv") {
            it.append(CsvContent.statement(assets, transactions, snapshots, dividends))
        }

    fun exportToText(assets: List<Asset>): Result<Uri> =
        exportFile("asset_report", "txt") { it.append(CsvContent.textReport(assets)) }

    /**
     * 落盘并返回可分享的 content URI。
     *
     * 导出前先清理旧文件：缓存目录里的历史导出没人删，攒久了白占空间；
     * 保留最近 [MAX_EXPORT_FILES] 份，兼顾「刚分享出去的那份还在」。
     */
    private fun exportFile(prefix: String, extension: String, build: (Writer) -> Unit): Result<Uri> {
        return try {
            pruneOldExports()
            val file = File(exportDir, "${prefix}_${System.currentTimeMillis().toFileTimestamp()}.$extension")
            openWriter(file).use(build)
            Result.success(getUriForFile(file))
        } catch (e: Exception) {
            AppLogger.e("导出失败($prefix): ${e.message}", e, "DataExporter")
            Result.failure(Exception("导出失败：${e.message ?: "未知错误"}"))
        }
    }

    /**
     * 清空所有已导出的文件（「清除所有数据」调用）。
     *
     * 这些 CSV 里是完整的持仓/交易明细，只清数据库和偏好、留着文件，
     * 等于用户以为删干净了、账目还躺在 cacheDir 里。
     */
    fun clearExportedFiles() {
        pruneOldExports(keep = 0)
    }

    private fun pruneOldExports(keep: Int = MAX_EXPORT_FILES) {
        try {
            val files = exportDir.listFiles()?.filter { it.isFile } ?: return
            if (files.size <= keep) return
            files.sortedByDescending { it.lastModified() }.drop(keep).forEach { it.delete() }
        } catch (e: Exception) {
            AppLogger.w("清理旧导出文件失败: ${e.message}", "DataExporter")
        }
    }

    private fun getUriForFile(file: File): Uri {
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }
}

/** 统一 CSV 字段转义：含引号、逗号或换行时用双引号包裹，内部引号转义为两个连续引号。 */
internal fun escapeCsv(field: String): String {
    val needsQuoting = field.any { it == '"' || it == ',' || it == '\n' || it == '\r' }
    return if (needsQuoting) {
        "\"" + field.replace("\"", "\"\"") + "\""
    } else {
        field
    }
}

/** 拼一行 CSV：每个单元格都过一遍 [escapeCsv]，避免调用方漏转义。 */
internal fun csvRow(vararg cells: String): String = cells.joinToString(",") { escapeCsv(it) }

/**
 * 导出用的数字格式：**固定用 `.` 作小数点**。
 *
 * 界面上的 `format()` 小数分隔符跟随系统 FORMAT locale；在逗号作小数点的语言环境
 * （德语、法语等）下会输出 `1234,56`，写进 CSV 直接多切一列。导出是机器可读格式，
 * 必须与界面解耦。
 */
internal fun csvNumber(value: Double, digits: Int): String =
    String.format(Locale.US, "%.${digits}f", value)

/**
 * 把交易行关联回资产，供币种折算/数量精度/账户列使用。
 *
 * 旧实现 `assets.associateBy { it.symbol }` 有两个静默错误：
 * 1. 同一代码在多个账户（多账户是 App 的核心能力）时后者覆盖前者，账户与人民币金额张冠李戴；
 * 2. 资产已删除时查不到，币种回退 "CNY"，美元交易被 1:1 当人民币汇总。
 *
 * 现在显式区分「唯一命中 / 多账户同名 / 找不到」，找不到或多账户无法确定币种时，
 * 人民币列留空而不是猜。
 */
internal class AssetLookup(assets: List<Asset>) {

    private val byKey = HashMap<String, MutableList<Asset>>()

    init {
        assets.forEach { asset ->
            val symbol = asset.symbol ?: return@forEach
            index(symbol, asset)
            // 兼容历史未归一的加密代码（BTC/USDT vs BTCUSDT）
            index(cryptoKey(symbol), asset)
        }
    }

    private fun index(key: String, asset: Asset) {
        if (key.isBlank()) return
        val list = byKey.getOrPut(key) { mutableListOf() }
        if (list.none { it === asset }) list.add(asset)
    }

    fun candidates(tx: Transaction): List<Asset> {
        val raw = tx.symbol.trim()
        return byKey[raw]
            ?: byKey[StockCodeManager.normalizeSymbol(raw)]
            ?: byKey[cryptoKey(raw)]
            ?: emptyList()
    }

    fun resolve(tx: Transaction): ResolvedTxAsset {
        val candidates = candidates(tx)
        if (candidates.isEmpty()) return ResolvedTxAsset(asset = null, account = "", currency = null)
        if (candidates.size == 1) {
            val only = candidates.first()
            return ResolvedTxAsset(only, only.account, only.currency)
        }
        // 多账户持有同一代码：能按名称唯一确定的才认，否则账户标「多账户」
        candidates.filter { it.name == tx.name }.singleOrNull()?.let {
            return ResolvedTxAsset(it, it.account, it.currency)
        }
        val currencies = candidates.map { it.currency.uppercase() }.distinct()
        return ResolvedTxAsset(
            // asset 只用于取数量/价格精度，同代码的多条类型基本一致，取第一条即可
            asset = candidates.first(),
            account = "多账户",
            currency = if (currencies.size == 1) currencies.first() else null
        )
    }

    private fun cryptoKey(symbol: String): String =
        symbol.uppercase().filter { it.isLetterOrDigit() }
}

/** 交易行解析结果；[currency] 为 null 表示无法确定币种（资产已删 / 多账户不同币种）。 */
internal data class ResolvedTxAsset(val asset: Asset?, val account: String, val currency: String?)

/**
 * 各导出文件的**内容构建**。纯函数，不碰文件系统，单测直接调。
 */
internal object CsvContent {

    val ASSET_HEADER = listOf(
        "名称", "类型", "代码", "账户", "数量", "参考单价", "当前价", "总价值(原币)",
        "总价值(人民币)", "币种", "汇率", "备注", "到期日", "参考利率(%)", "万份收益"
    )

    private val SNAPSHOT_HEADER = listOf(
        "日期", "总资产", "总负债", "净资产", "股票", "基金", "虚拟货币", "银行存款"
    )

    private val DIVIDEND_HEADER = listOf("代码", "名称", "分红金额(元)", "除息日", "到账日", "是否手动添加")

    private val TRANSACTION_HEADER = listOf(
        "日期", "代码", "名称", "交易类型", "数量", "价格", "手续费", "成交金额(原币)",
        "币种", "成交金额(人民币)", "账户", "资产备注"
    )

    /**
     * 综合对账单的固定列定义。所有区块（资产/交易/快照/分红/汇总）共用这一套列，
     * 不适用的单元格留空 —— 这样整份文件是**一张合法 CSV 表**。
     */
    private val STATEMENT_HEADER = listOf(
        "区块", "日期", "名称", "代码", "类别", "账户", "数量", "单价",
        "金额(原币)", "币种", "汇率", "金额(人民币)", "备注"
    )

    private val SNAPSHOT_METRICS: List<Pair<String, (AssetSnapshot) -> Double>> = listOf(
        "总资产" to AssetSnapshot::totalAssets,
        "总负债" to AssetSnapshot::totalLiabilities,
        "净资产" to AssetSnapshot::netAssets,
        "股票" to AssetSnapshot::stockValue,
        "基金" to AssetSnapshot::fundValue,
        "虚拟货币" to AssetSnapshot::cryptoValue,
        "银行存款" to AssetSnapshot::bankDepositValue
    )

    // ---------------------------------------------------------------- 资产

    fun assets(assets: List<Asset>): String {
        val sb = StringBuilder()
        sb.appendLine(csvRow(*ASSET_HEADER.toTypedArray()))
        assets.forEach { sb.appendLine(assetRow(it)) }

        val assetsOnly = assets.excludingDebts()
        val liabilities = assets.debts()
        val totalAssets = assetsOnly.sumTotalValueCny()
        val totalLiabilities = liabilities.sumDebtValueCny()

        sb.appendLine()
        sb.appendLine("汇总信息")
        sb.appendLine("总资产(人民币),${csvNumber(totalAssets, MONEY_DIGITS)}")
        sb.appendLine("总负债(人民币),${csvNumber(totalLiabilities, MONEY_DIGITS)}")
        sb.appendLine("净资产(人民币),${csvNumber(totalAssets - totalLiabilities, MONEY_DIGITS)}")
        sb.appendLine("资产数量,${assets.size}")
        return sb.toString()
    }

    internal fun assetRow(asset: Asset): String {
        val quoted = isQuoted(asset.type)
        return csvRow(
            asset.name,
            asset.type.displayName,
            asset.symbol ?: "",
            asset.account,
            csvNumber(asset.balance, quantityPrecisionFor(asset.type, asset.symbol)),
            // 存款/负债没有行情：单价列留空，避免出现「数量 10 万、单价 0、总价值 10 万」的自相矛盾行
            if (quoted) csvNumber(asset.purchasePrice, pricePrecisionFor(asset.type, asset.symbol)) else "",
            if (quoted) csvNumber(
                asset.currentPrice ?: asset.purchasePrice,
                pricePrecisionFor(asset.type, asset.symbol)
            ) else "",
            csvNumber(asset.totalValue, MONEY_DIGITS),
            csvNumber(asset.totalValueCny, MONEY_DIGITS),
            asset.currency,
            csvNumber(ExchangeRateManager.getRate(asset.currency), 4),
            asset.notes ?: "",
            asset.dueDate ?: "",
            asset.interestRate?.let { csvNumber(it, 2) } ?: "",
            asset.millionCopiesIncome?.let { csvNumber(it, 4) } ?: ""
        )
    }

    // ---------------------------------------------------------------- 快照

    fun snapshots(snapshots: List<AssetSnapshot>): String {
        val sb = StringBuilder()
        sb.appendLine(csvRow(*SNAPSHOT_HEADER.toTypedArray()))
        snapshots.sortedBy { it.date }.forEach { s ->
            sb.appendLine(
                csvRow(
                    s.date,
                    csvNumber(s.totalAssets, MONEY_DIGITS),
                    csvNumber(s.totalLiabilities, MONEY_DIGITS),
                    csvNumber(s.netAssets, MONEY_DIGITS),
                    csvNumber(s.stockValue, MONEY_DIGITS),
                    csvNumber(s.fundValue, MONEY_DIGITS),
                    csvNumber(s.cryptoValue, MONEY_DIGITS),
                    csvNumber(s.bankDepositValue, MONEY_DIGITS)
                )
            )
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------- 分红

    fun dividends(dividends: List<Dividend>): String {
        val sb = StringBuilder()
        sb.appendLine(csvRow(*DIVIDEND_HEADER.toTypedArray()))
        if (dividends.isEmpty()) {
            sb.appendLine("(暂无分红记录)")
            return sb.toString()
        }
        dividends.forEach { d ->
            sb.appendLine(
                csvRow(
                    d.symbol,
                    d.name,
                    csvNumber(d.amount, MONEY_DIGITS),
                    d.date,
                    d.payDate ?: "",
                    if (d.isManual) "是" else "否"
                )
            )
        }
        sb.appendLine()
        sb.appendLine("汇总信息")
        sb.appendLine("分红合计(元),${csvNumber(dividends.sumOf { it.amount }, MONEY_DIGITS)}")
        sb.appendLine("记录数,${dividends.size}")
        return sb.toString()
    }

    // ---------------------------------------------------------------- 交易

    fun transactions(assets: List<Asset>, transactions: List<Transaction>): String {
        val lookup = AssetLookup(assets)
        val sb = StringBuilder()
        sb.appendLine(csvRow(*TRANSACTION_HEADER.toTypedArray()))
        transactions.sortedByDescending { it.date }.forEach { tx ->
            sb.appendLine(transactionRow(tx, lookup))
        }

        val totals = TxTotals.of(transactions, lookup)
        sb.appendLine()
        sb.appendLine("汇总信息")
        sb.appendLine("买入总金额(人民币),${csvNumber(totals.buy, MONEY_DIGITS)}")
        sb.appendLine("卖出总金额(人民币),${csvNumber(totals.sell, MONEY_DIGITS)}")
        sb.appendLine("总手续费(人民币),${csvNumber(totals.fee, MONEY_DIGITS)}")
        sb.appendLine("交易笔数,${transactions.size}")
        if (totals.unconvertible > 0) {
            // 明说而不是静默当人民币：资产被删/多账户不同币种时无法折算
            sb.appendLine("无法折算的交易(资产已删除或币种不明),${totals.unconvertible}")
        }
        return sb.toString()
    }

    internal fun transactionRow(tx: Transaction, lookup: AssetLookup): String {
        val resolved = lookup.resolve(tx)
        val amount = tx.quantity * tx.price
        val amountCny = resolved.currency?.let { ExchangeRateManager.toCny(amount, it) }
        return csvRow(
            tx.date,
            tx.symbol,
            tx.name,
            TransactionType.fromValue(tx.type)?.name ?: tx.type,
            // 精度跟资产类型走：旧实现硬编码 2/3 位，加密货币 0.00012345 会被导成 0.00
            csvNumber(tx.quantity, quantityDigitsOf(resolved.asset)),
            csvNumber(tx.price, priceDigitsOf(resolved.asset)),
            csvNumber(tx.fee, MONEY_DIGITS),
            csvNumber(amount, amountDigitsOf(resolved.asset)),
            resolved.currency ?: "",
            amountCny?.let { csvNumber(it, MONEY_DIGITS) } ?: "",
            resolved.account,
            resolved.asset?.notes ?: ""
        )
    }

    private class TxTotals(var buy: Double = 0.0, var sell: Double = 0.0, var fee: Double = 0.0, var unconvertible: Int = 0) {
        companion object {
            fun of(transactions: List<Transaction>, lookup: AssetLookup): TxTotals {
                val totals = TxTotals()
                transactions.forEach { tx ->
                    val currency = lookup.resolve(tx).currency
                    if (currency == null) {
                        totals.unconvertible++
                        return@forEach
                    }
                    val amountCny = ExchangeRateManager.toCny(tx.quantity * tx.price, currency)
                    when (TransactionType.fromValue(tx.type)) {
                        TransactionType.BUY -> totals.buy += amountCny
                        TransactionType.SELL -> totals.sell += amountCny
                        null -> Unit
                    }
                    // 手续费同样是原币：旧实现直接 sum，把 5 美元和 10 元加在一起
                    totals.fee += ExchangeRateManager.toCny(tx.fee, currency)
                }
                return totals
            }
        }
    }

    // ---------------------------------------------------------------- 综合对账单

    fun statement(
        assets: List<Asset>,
        transactions: List<Transaction>,
        snapshots: List<AssetSnapshot>,
        dividends: List<Dividend>,
        generatedAt: LocalDateTime = LocalDateTime.now()
    ): String {
        val lookup = AssetLookup(assets)
        val sb = StringBuilder()
        sb.appendLine(csvRow(*STATEMENT_HEADER.toTypedArray()))
        sb.appendLine(
            csvRow(
                "对账单",
                generatedAt.format(DateFormats.ISO_DATETIME_FORMATTER),
                "综合对账单",
                "", "", "", "", "", "", "", "", "", ""
            )
        )

        assets.forEach { asset ->
            val quoted = isQuoted(asset.type)
            sb.appendLine(
                csvRow(
                    "资产",
                    "",
                    asset.name,
                    asset.symbol ?: "",
                    asset.type.displayName,
                    asset.account,
                    csvNumber(asset.balance, quantityPrecisionFor(asset.type, asset.symbol)),
                    if (quoted) csvNumber(
                        asset.currentPrice ?: asset.purchasePrice,
                        pricePrecisionFor(asset.type, asset.symbol)
                    ) else "",
                    csvNumber(asset.totalValue, MONEY_DIGITS),
                    asset.currency,
                    csvNumber(ExchangeRateManager.getRate(asset.currency), 4),
                    csvNumber(asset.totalValueCny, MONEY_DIGITS),
                    asset.notes ?: ""
                )
            )
        }

        transactions.sortedByDescending { it.date }.forEach { tx ->
            val resolved = lookup.resolve(tx)
            val amount = tx.quantity * tx.price
            val amountCny = resolved.currency?.let { ExchangeRateManager.toCny(amount, it) }
            sb.appendLine(
                csvRow(
                    "交易",
                    tx.date,
                    tx.name,
                    tx.symbol,
                    TransactionType.fromValue(tx.type)?.name ?: tx.type,
                    resolved.account,
                    csvNumber(tx.quantity, quantityDigitsOf(resolved.asset)),
                    csvNumber(tx.price, priceDigitsOf(resolved.asset)),
                    csvNumber(amount, amountDigitsOf(resolved.asset)),
                    resolved.currency ?: "",
                    resolved.currency?.let { csvNumber(ExchangeRateManager.getRate(it), 4) } ?: "",
                    amountCny?.let { csvNumber(it, MONEY_DIGITS) } ?: "",
                    resolved.asset?.notes ?: ""
                )
            )
        }

        snapshots.sortedBy { it.date }.forEach { snapshot ->
            // 每个快照拆成 7 行（一行一个指标），保持与其余区块同一套列
            SNAPSHOT_METRICS.forEach { (label, metric) ->
                sb.appendLine(
                    csvRow(
                        "快照",
                        snapshot.date,
                        label,
                        "", "", "", "", "", "", "", "",
                        csvNumber(metric(snapshot), MONEY_DIGITS),
                        ""
                    )
                )
            }
        }

        dividends.forEach { d ->
            sb.appendLine(
                csvRow(
                    "分红",
                    d.date,
                    d.name,
                    d.symbol,
                    if (d.isManual) "手动" else "自动",
                    "", "", "",
                    csvNumber(d.amount, MONEY_DIGITS),
                    "CNY",
                    "1",
                    csvNumber(d.amount, MONEY_DIGITS),
                    "到账日: ${d.payDate ?: "—"}"
                )
            )
        }

        val assetsOnly = assets.excludingDebts()
        val liabilities = assets.debts()
        val totalAssets = assetsOnly.sumTotalValueCny()
        val totalLiabilities = liabilities.sumDebtValueCny()
        sb.appendLine(summaryRow("总资产(人民币)", totalAssets))
        sb.appendLine(summaryRow("总负债(人民币)", totalLiabilities))
        sb.appendLine(summaryRow("净资产(人民币)", totalAssets - totalLiabilities))
        sb.appendLine(summaryRow("资产数量", assets.size.toDouble()))
        sb.appendLine(summaryRow("交易笔数", transactions.size.toDouble()))
        sb.appendLine(summaryRow("快照天数", snapshots.map { it.date }.distinct().size.toDouble()))
        sb.appendLine(summaryRow("分红合计(元)", dividends.sumOf { it.amount }))
        return sb.toString()
    }

    private fun summaryRow(label: String, value: Double): String =
        csvRow("汇总", "", label, "", "", "", "", "", "", "", "", csvNumber(value, MONEY_DIGITS), "")

    // ---------------------------------------------------------------- 文本报告

    fun textReport(assets: List<Asset>, generatedAt: LocalDateTime = LocalDateTime.now()): String {
        val sb = StringBuilder()
        sb.appendLine("=== 资产报告 ===")
        sb.appendLine("生成时间: ${generatedAt.format(DateFormats.CN_DATETIME_FORMATTER)}")
        sb.appendLine()

        val assetsOnly = assets.excludingDebts()
        val liabilities = assets.debts()
        val totalAssets = assetsOnly.sumTotalValueCny()
        val totalLiabilities = liabilities.sumDebtValueCny()

        sb.appendLine("【净资产】")
        sb.appendLine("  ¥${csvNumber(totalAssets - totalLiabilities, MONEY_DIGITS)}")
        sb.appendLine()
        sb.appendLine("【总资产】: ¥${csvNumber(totalAssets, MONEY_DIGITS)}")
        sb.appendLine("【总负债】: ¥${csvNumber(totalLiabilities, MONEY_DIGITS)}")
        sb.appendLine()
        sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━")
        sb.appendLine()

        if (assetsOnly.isNotEmpty()) {
            sb.appendLine("【资产详情】")
            sb.appendLine()
            assetsOnly.groupBy { it.type }.forEach { (type, typeAssets) ->
                sb.appendLine("  ${type.icon} ${type.displayName}")
                typeAssets.forEach { asset ->
                    sb.appendLine("    - ${asset.name}")
                    // 账户必须出现：多账户用户看不出某笔资产在哪个账户，这份报告就没法对账
                    sb.appendLine("      代码: ${asset.symbol ?: "-"}  账户: ${asset.account}")
                    sb.appendLine(
                        "      数量: ${csvNumber(asset.balance, quantityPrecisionFor(type, asset.symbol))}"
                    )
                    sb.appendLine(
                        "      当前价值: ${asset.currency} ${csvNumber(asset.totalValue, MONEY_DIGITS)}" +
                            " (¥${csvNumber(asset.totalValueCny, MONEY_DIGITS)})"
                    )
                    if (asset.currency != "CNY") {
                        sb.appendLine("      汇率: ${csvNumber(ExchangeRateManager.getRate(asset.currency), 4)}")
                    }
                    sb.appendLine()
                }
            }
        }

        if (liabilities.isNotEmpty()) {
            sb.appendLine("【负债详情】")
            sb.appendLine()
            liabilities.groupBy { it.type }.forEach { (type, typeAssets) ->
                sb.appendLine("  ${type.icon} ${type.displayName}")
                typeAssets.forEach { asset ->
                    sb.appendLine("    - ${asset.name}")
                    sb.appendLine("      账户: ${asset.account}")
                    sb.appendLine(
                        "      金额: ${asset.currency} ${csvNumber(asset.totalValue, MONEY_DIGITS)}" +
                            " (¥${csvNumber(asset.totalValueCny, MONEY_DIGITS)})"
                    )
                    asset.notes?.let { sb.appendLine("      备注: $it") }
                    sb.appendLine()
                }
            }
        }

        sb.appendLine()
        sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━")
        sb.appendLine("报告生成完毕")
        return sb.toString()
    }

    // ---------------------------------------------------------------- 公共

    /** 有行情概念的资产才展示单价：存款/负债没有价格，只有金额 */
    private fun isQuoted(type: AssetType): Boolean = !type.isLiability && type != AssetType.BANK_DEPOSIT

    private fun quantityDigitsOf(asset: Asset?): Int =
        asset?.let { quantityPrecisionFor(it.type, it.symbol) } ?: UNKNOWN_ASSET_DIGITS

    private fun priceDigitsOf(asset: Asset?): Int =
        asset?.let { pricePrecisionFor(it.type, it.symbol) } ?: UNKNOWN_ASSET_DIGITS

    private fun amountDigitsOf(asset: Asset?): Int = when {
        asset == null -> UNKNOWN_ASSET_DIGITS
        asset.type == AssetType.CRYPTO -> CRYPTO_DECIMALS
        else -> MONEY_DIGITS
    }

    /** 资产找不到时的精度兜底：给足 8 位，宁可多留也不要截断 */
    private const val UNKNOWN_ASSET_DIGITS = 8
}
