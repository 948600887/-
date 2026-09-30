package com.example.stockdiary.data

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.database.StockDatabase
import com.example.stockdiary.database.Transaction
import com.example.stockdiary.database.TransactionType
import com.example.stockdiary.utils.AppLogger
import com.example.stockdiary.utils.StockCodeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 券商交割单批量导入服务。
 *
 * 设计要点：
 * - 不复用 AssetRepository.addOrUpdateStockAsset/sellStockAsset：它们内部用 LocalDate.now()
 *   作为交易日期，会丢失交割单真实成交日。本服务直接操作 AssetDao/TransactionDao，
 *   在同一事务内完成「持仓合并/加权成本」+「写入带真实日期的交易记录」，保证原子性与日期准确。
 * - 通用列映射：不写死任何券商格式，用正则关键字自动猜测列映射，用户可在预览页下拉调整。
 * - 先预览再提交：解析后展示交易列表 + 异常行/重复行标记，用户确认后才批量入库。
 * - 回放顺序：commit 前按日期升序排序，保证多次买入的加权成本正确累积。
 */
class BrokerageImportService private constructor(private val context: Context) {

    /** 解析后的 CSV 结构 */
    data class ParsedCsv(
        val headers: List<String>,
        val rows: List<List<String>>
    )

    /** 列映射：每列映射到哪个语义字段，-1 表示不映射 */
    data class ColumnMapping(
        val dateCol: Int = -1,
        val symbolCol: Int = -1,
        val nameCol: Int = -1,
        val typeCol: Int = -1,
        val quantityCol: Int = -1,
        val priceCol: Int = -1,
        val feeCol: Int = -1,
        val accountCol: Int = -1
    )

    /** 解析后的一行交易（含异常与重复标记） */
    data class ParsedTransaction(
        val date: String,
        val symbol: String,
        val name: String,
        val type: String,
        val quantity: Double,
        val price: Double,
        val fee: Double,
        val account: String,
        val rowNumber: Int,
        val errors: List<String>,
        val isDuplicate: Boolean,
        var skip: Boolean = false
    )

    data class ParseResult(
        val csv: ParsedCsv,
        val charset: Charset,
        val mapping: ColumnMapping
    )

    data class RowFailure(val rowNumber: Int, val reason: String)

    data class CommitResult(
        val successCount: Int,
        val skippedDuplicate: Int,
        val skippedByUser: Int,
        val skippedError: Int,
        val failedCount: Int,
        val failures: List<RowFailure>
    )

    companion object {
        /**
         * 截图导入时证券代码缺失的提示文案。解析层、预览页、提交前校验都引用这一处，
         * 避免同一句提示在多处各写一遍后逐渐不一致。
         */
        const val ERR_SYMBOL_REQUIRED = "需手动填写证券代码"

        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: BrokerageImportService? = null

        fun getInstance(context: Context): BrokerageImportService {
            return instance ?: synchronized(this) {
                instance ?: BrokerageImportService(context.applicationContext).also { instance = it }
            }
        }

        /** 可选编码列表（券商 CSV 多为 GBK，亦常见 UTF-8） */
        val SUPPORTED_CHARSETS: List<Pair<String, Charset>> = listOf(
            "GBK" to Charset.forName("GBK"),
            "UTF-8" to Charsets.UTF_8,
            "UTF-16" to Charsets.UTF_16
        )
    }

    private val assetDao get() = StockDatabase.getInstance(context).assetDao()
    private val transactionDao get() = StockDatabase.getInstance(context).transactionDao()

    /**
     * 读取文件字节并自动探测编码（BOM 优先，再试 UTF-8 严格解码，失败回退 GBK）。
     * 若 [charsetOverride] 非空则直接使用该编码。
     */
    private suspend fun readText(uri: Uri, charsetOverride: Charset? = null): Pair<String, Charset> =
        withContext(Dispatchers.IO) {
            val bytes = readBytes(uri)
            val charset = charsetOverride ?: detectCharset(bytes)
            // 去除 BOM
            val content = when {
                bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
                    String(bytes, 3, bytes.size - 3, charset)
                bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                    String(bytes, 2, bytes.size - 2, charset)
                else -> String(bytes, charset)
            }
            content to charset
        }

    private suspend fun readBytes(uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("无法读取文件")
    }

    private fun detectCharset(bytes: ByteArray): Charset {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte())
            return Charsets.UTF_8
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte())
            return Charsets.UTF_16LE
        // 尝试 UTF-8 严格解码
        return try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes))
            Charsets.UTF_8
        } catch (e: Exception) {
            Charset.forName("GBK")
        }
    }

    /**
     * 第一步：解析 CSV，自动探测编码与列映射。
     */
    suspend fun parse(uri: Uri, charsetOverride: Charset? = null): ParseResult {
        val (text, charset) = readText(uri, charsetOverride)
        val csv = parseCsvText(text)
        val mapping = guessColumnMapping(csv.headers)
        return ParseResult(csv, charset, mapping)
    }

    /** 重新解析（用户切换编码或列映射后） */
    suspend fun reparse(uri: Uri, charset: Charset): ParseResult = parse(uri, charset)

    /**
     * CSV 文本解析：支持引号包裹字段（含逗号、转义双引号 ""）、引号内换行。
     */
    private fun parseCsvText(content: String): ParsedCsv {
        val rows = mutableListOf<List<String>>()
        val current = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        val n = content.length
        while (i < n) {
            val c = content[i]
            when {
                inQuotes -> {
                    if (c == '"') {
                        if (i + 1 < n && content[i + 1] == '"') {
                            sb.append('"'); i += 2
                        } else {
                            inQuotes = false; i++
                        }
                    } else {
                        sb.append(c); i++
                    }
                }
                else -> {
                    when (c) {
                        '"' -> { inQuotes = true; i++ }
                        ',' -> { current.add(sb.toString()); sb.setLength(0); i++ }
                        '\r' -> {
                            current.add(sb.toString()); sb.setLength(0)
                            rows.add(current.toList()); current.clear()
                            i++
                            if (i < n && content[i] == '\n') i++
                        }
                        '\n' -> {
                            current.add(sb.toString()); sb.setLength(0)
                            rows.add(current.toList()); current.clear()
                            i++
                        }
                        else -> { sb.append(c); i++ }
                    }
                }
            }
        }
        // 末尾残留
        if (sb.isNotEmpty() || current.isNotEmpty()) {
            current.add(sb.toString())
            rows.add(current.toList())
        }
        if (rows.isEmpty()) return ParsedCsv(emptyList(), emptyList())
        val headers = rows.first().map { it.trim() }
        val dataRows = rows.drop(1).filter { row -> row.any { it.isNotBlank() } }
        return ParsedCsv(headers, dataRows)
    }

    /**
     * 第二步：基于列名关键字自动猜测列映射（中英文兼容）。
     */
    fun guessColumnMapping(headers: List<String>): ColumnMapping {
        fun findIndex(pattern: String): Int {
            val regex = Regex(pattern, RegexOption.IGNORE_CASE)
            // 精确匹配优先，再退而包含匹配
            headers.indexOfFirst { regex.matches(it.trim()) }.let { if (it >= 0) return it }
            return headers.indexOfFirst { regex.containsMatchIn(it.trim()) }
        }

        return ColumnMapping(
            dateCol = findIndex("^(成交日期|委托日期|日期|成交时间|时间|date|trade.?date)$|日期|date"),
            symbolCol = findIndex("^(证券代码|股票代码|代码|symbol|code|stock.?code)$|证券代码|股票代码|代码|symbol"),
            nameCol = findIndex("^(证券名称|股票名称|名称|product.?name|name)$|证券名称|股票名称|名称"),
            typeCol = findIndex("^(买卖|买卖方向|方向|操作|交易类别|类型|side|direction|operation)$|买卖|方向|操作|交易类别"),
            quantityCol = findIndex("^(成交数量|委托数量|股数|持仓数量|数量|volume|qty|shares)$|成交数量|委托数量|股数|数量|volume|qty"),
            priceCol = findIndex("^(成交均价|成交价格|委托价|价格|均价|单价|price)$|成交均价|成交价格|价格|均价|单价|price"),
            feeCol = findIndex("手续费|佣金|费用|fee|commission|charge"),
            accountCol = findIndex("账户|账号|股东账号|资金账号|account")
        )
    }

    /**
     * 证券代码归一化。
     *
     * 全项目存库的代码形态是 [StockCodeManager.normalizeSymbol] 的结果
     * （AddAssetDialog / AssetDetailViewModel 都走它），例如 "515080" → "sh515080"。
     * 导入时如果直接拿原始 6 位数字去查库，会查不到已有持仓，进而重复建仓、成本算错。
     */
    private fun normalizedSymbol(raw: String): String =
        if (raw.isBlank()) "" else StockCodeManager.normalizeSymbol(raw)

    /**
     * 单行重复检查。供截图导入的预览页在用户补填代码后调用（不需要拉全表）。
     */
    suspend fun isDuplicate(tx: ParsedTransaction): Boolean = withContext(Dispatchers.IO) {
        if (tx.date.isBlank() || tx.type.isBlank() || tx.quantity <= 0 || tx.price <= 0) {
            return@withContext false
        }
        val symbol = normalizedSymbol(tx.symbol)
        if (symbol.isBlank()) return@withContext false
        transactionDao.findDuplicate(symbol, tx.type, tx.quantity, tx.price, tx.date) != null
    }

    /**
     * 第三步：按映射把 CSV 行转换成 ParsedTransaction 列表，并标记异常与重复。
     */
    suspend fun mapRows(parsed: ParseResult): List<ParsedTransaction> = withContext(Dispatchers.IO) {
        val csv = parsed.csv
        val mapping = parsed.mapping

        // 一次性拉取已有交易记录构造去重键集合（避免逐行查库）
        val existingKeys: Set<String> = transactionDao.getAll().map {
            "${normalizedSymbol(it.symbol)}|${it.type}|${it.quantity}|${it.price}|${it.date}"
        }.toSet()

        csv.rows.mapIndexed { idx, row ->
            val rowNo = idx + 2 // 第1行是表头，数据从第2行开始
            val errors = mutableListOf<String>()

            fun get(col: Int): String = if (col in row.indices) row[col].trim() else ""

            val rawDate = get(mapping.dateCol)
            val date = parseDate(rawDate)
            if (date == null) errors.add("日期无法解析: '$rawDate'")

            val symbol = get(mapping.symbolCol)
            if (symbol.isEmpty()) errors.add("证券代码为空")
            val name = get(mapping.nameCol)

            val rawType = get(mapping.typeCol)
            val type = parseType(rawType)
            if (type == null) errors.add("买卖方向未识别: '$rawType'")

            val rawQty = get(mapping.quantityCol)
            val quantity = rawQty.toDoubleOrNull()
            if (quantity == null) errors.add("数量无法解析: '$rawQty'")
            else if (quantity <= 0) errors.add("数量非正: $quantity")

            val rawPrice = get(mapping.priceCol)
            val price = rawPrice.toDoubleOrNull()
            if (price == null) errors.add("价格无法解析: '$rawPrice'")
            else if (price <= 0) errors.add("价格非正: $price")

            val fee = get(mapping.feeCol).toDoubleOrNull() ?: 0.0
            val account = get(mapping.accountCol).ifEmpty { "默认账户" }

            val isDup = if (date != null && type != null && quantity != null && price != null) {
                "${normalizedSymbol(symbol)}|${type}|${quantity}|${price}|${date}" in existingKeys
            } else false

            ParsedTransaction(
                date = date ?: "",
                symbol = symbol,
                name = name,
                type = type ?: "",
                quantity = quantity ?: 0.0,
                price = price ?: 0.0,
                fee = fee,
                account = account,
                rowNumber = rowNo,
                errors = errors,
                isDuplicate = isDup,
                skip = false
            )
        }
    }

    /**
     * 第四步：回放提交。按日期升序，买入合并加权成本、卖出减仓，同事务写交易记录（带真实日期）。
     * 跳过用户取消勾选的行与库内重复行，失败行收集成报告。
     */
    suspend fun commit(transactions: List<ParsedTransaction>): CommitResult = withContext(Dispatchers.IO) {
        val db = StockDatabase.getInstance(context)
        var success = 0
        var skippedDup = 0
        var failed = 0
        val failures = mutableListOf<RowFailure>()

        // 仅处理无异常且未取消勾选的行；按日期升序回放保证加权成本正确累积。
        // 入库前统一归一化证券代码，否则会和 AddAssetDialog 存下的 "shXXXXXX" 对不上，
        // 导致同一只股票被重复建仓。
        val valid = transactions
            .filter { it.errors.isEmpty() && !it.skip }
            .map { it.copy(symbol = normalizedSymbol(it.symbol)) }
            .sortedBy { it.date }

        for (t in valid) {
            try {
                var localSkipped = false
                db.withTransaction {
                    // 事务内二次确认去重（防止预览后库内新增同记录）
                    if (transactionDao.findDuplicate(t.symbol, t.type, t.quantity, t.price, t.date) != null) {
                        localSkipped = true
                        return@withTransaction
                    }
                    val now = System.currentTimeMillis()
                    if (t.type == TransactionType.BUY.value) {
                        applyBuy(t, now)
                    } else {
                        applySell(t, now)
                    }
                    transactionDao.insert(
                        Transaction(
                            symbol = t.symbol,
                            name = t.name,
                            type = t.type,
                            quantity = t.quantity,
                            price = t.price,
                            date = t.date,
                            fee = t.fee
                        )
                    )
                    success++
                }
                if (localSkipped) skippedDup++
            } catch (e: Exception) {
                AppLogger.e("交割单导入第 ${t.rowNumber} 行失败: ${e.message}", e, "BrokerageImport")
                failures.add(RowFailure(t.rowNumber, e.message ?: "未知错误"))
                failed++
            }
        }
        // 用户跳过：勾选了「不导入」的行（含异常行被勾选跳过）
        val skippedUser = transactions.count { it.skip }
        // 异常跳过：有异常但用户未勾选跳过，被系统自动跳过
        val skippedError = transactions.count { it.errors.isNotEmpty() && !it.skip }
        CommitResult(success, skippedDup, skippedUser, skippedError, failed, failures)
    }

    /** 买入：已有持仓则合并加权成本，否则新建资产 */
    private suspend fun applyBuy(t: ParsedTransaction, now: Long) {
        val existing = assetDao.getAssetBySymbol(t.symbol)
        if (existing != null) {
            val newBalance = existing.balance + t.quantity
            val newCost = if (newBalance > 0) {
                ((existing.balance * existing.purchasePrice) + (t.quantity * t.price)) / newBalance
            } else t.price
            assetDao.updateBalanceAndPrice(existing.id, newBalance, newCost, now)
        } else {
            assetDao.insertAsset(
                Asset(
                    name = t.name.ifEmpty { t.symbol },
                    type = AssetType.STOCK,
                    symbol = t.symbol,
                    account = t.account,
                    balance = t.quantity,
                    purchasePrice = t.price,
                    lastUpdateTime = now
                )
            )
        }
    }

    /**
     * 卖出：有持仓则减仓/清仓，无持仓则跳过持仓操作（仅写交易记录，回放时数据可能不全）。
     * 这里不抛异常——卖出无持仓视为交易记录补录，持仓由后续买入记录重建。
     */
    private suspend fun applySell(t: ParsedTransaction, now: Long) {
        val existing = assetDao.getAssetBySymbol(t.symbol)
        if (existing == null) {
            // 无持仓但仍记录交易；标记为提示而非硬失败
            return
        }
        if (t.quantity >= existing.balance) {
            assetDao.deleteAsset(existing)
        } else {
            assetDao.updateBalance(existing.id, existing.balance - t.quantity, now)
        }
    }

    /** 买卖方向识别：买/买入/B/BUY → BUY；卖/卖出/S/SELL → SELL */
    private fun parseType(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        // 先判卖再判买，避免「卖出」「买入」互含误判
        if (s.contains("卖") || s.equals("S", true) || s.equals("SELL", true) || s.contains("SELL", true)) return TransactionType.SELL.value
        if (s.contains("买") || s.equals("B", true) || s.equals("BUY", true) || s.contains("BUY", true)) return TransactionType.BUY.value
        return null
    }

    /** 日期归一化为 yyyy-MM-dd，支持多种格式 */
    private fun parseDate(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        // 带时间
        val dateTimeFormats = listOf(
            "yyyy-MM-dd HH:mm:ss", "yyyy/MM/dd HH:mm:ss",
            "yyyy.MM.dd HH:mm:ss", "yyyy-MM-dd HH:mm"
        )
        for (p in dateTimeFormats) {
            runCatching { LocalDateTime.parse(s, DateTimeFormatter.ofPattern(p)) }
                .getOrNull()?.let { return it.toLocalDate().toString() }
        }
        // 纯日期
        val dateFormats = listOf(
            "yyyy-MM-dd", "yyyy/MM/dd", "yyyy.MM.dd",
            "yyyyMMdd", "dd/MM/yyyy", "dd-MM-yyyy"
        )
        for (p in dateFormats) {
            runCatching { LocalDate.parse(s, DateTimeFormatter.ofPattern(p)) }
                .getOrNull()?.let { return it.toString() }
        }
        // 中文：2024年1月5日
        Regex("(\\d{4})年(\\d{1,2})月(\\d{1,2})日").find(s)?.let { m ->
            val y = m.groupValues[1].toInt()
            val mo = m.groupValues[2].toInt()
            val d = m.groupValues[3].toInt()
            return runCatching { LocalDate.of(y, mo, d).toString() }.getOrNull()
        }
        // 提取前 8/10 位数字尝试 yyyyMMdd
        Regex("\\d{8}").find(s)?.let { m ->
            runCatching { LocalDate.parse(m.value, DateTimeFormatter.ofPattern("yyyyMMdd")) }
                .getOrNull()?.let { return it.toString() }
        }
        return null
    }
}
