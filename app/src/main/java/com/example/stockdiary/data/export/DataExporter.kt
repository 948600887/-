package com.example.stockdiary.data.export

import com.example.stockdiary.common.DateFormats
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetSnapshot
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.database.Dividend
import com.example.stockdiary.database.Transaction
import com.example.stockdiary.database.TransactionType
import com.example.stockdiary.utils.AppLogger
import com.example.stockdiary.utils.ExchangeRateManager
import com.example.stockdiary.utils.format
import com.example.stockdiary.utils.pricePrecisionFor
import com.example.stockdiary.utils.quantityPrecisionFor
import com.example.stockdiary.utils.sumTotalValueCny
import com.example.stockdiary.utils.toFileTimestamp
import com.example.stockdiary.utils.totalValueCny
import java.io.File
import java.io.FileWriter
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException

class DataExporter(private val context: Context) {
    
    companion object {
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: DataExporter? = null
        
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
    private fun openCsvWriter(file: File): java.io.Writer {
        val writer = java.io.OutputStreamWriter(java.io.FileOutputStream(file), StandardCharsets.UTF_8)
        writer.write('\uFEFF'.toString()) // UTF-8 BOM
        return writer
    }

    fun exportAssetsToCSV(assets: List<Asset>): Uri? {
        return try {
            val fileName = "assets_${System.currentTimeMillis().toFileTimestamp()}.csv"
            val file = File(exportDir, fileName)
            
            openCsvWriter(file).use { writer ->
                writer.append("名称,类型,代码,账户,数量,参考单价,当前价,总价值(原币),总价值(人民币),币种,汇率,备注,到期日\n")

                assets.forEach { asset ->
                    writer.append(buildCsvRow(asset))
                    writer.append("\n")
                }

                writer.append("\n")
                writer.append("汇总信息\n")
                val assetsOnly = assets.filter { !it.type.isLiability }
                val liabilities = assets.filter { it.type.isLiability }
                writer.append("总资产(人民币),${assetsOnly.sumTotalValueCny().format(2)}\n")
                writer.append("总负债(人民币),${liabilities.sumTotalValueCny().format(2)}\n")
                writer.append("净资产(人民币),${(assetsOnly.sumTotalValueCny() - liabilities.sumTotalValueCny()).format(2)}\n")
                writer.append("资产数量,${assets.size}\n")
            }
            
            getUriForFile(file)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("导出资产到CSV失败: ${e.message}", e, "DataExporter")
            null
        }
    }
    
    fun exportSnapshotsToCSV(snapshots: List<AssetSnapshot>): Uri? {
        return try {
            val fileName = "snapshots_${System.currentTimeMillis().toFileTimestamp()}.csv"
            val file = File(exportDir, fileName)
            
            openCsvWriter(file).use { writer ->
                writer.append("日期,总资产,总负债,净资产,股票,基金,虚拟货币,银行存款\n")
                
                snapshots.sortedBy { it.date }.forEach { snapshot ->
                    writer.append("${escapeCsv(snapshot.date)},")
                    writer.append("${snapshot.totalAssets.format(2)},")
                    writer.append("${snapshot.totalLiabilities.format(2)},")
                    writer.append("${snapshot.netAssets.format(2)},")
                    writer.append("${snapshot.stockValue.format(2)},")
                    writer.append("${snapshot.fundValue.format(2)},")
                    writer.append("${snapshot.cryptoValue.format(2)},")
                    writer.append("${snapshot.bankDepositValue.format(2)}\n")
                }
            }
            
            getUriForFile(file)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("导出资产到文本失败: ${e.message}", e, "DataExporter")
            null
        }
    }

    /**
     * 导出分红明细（CSV）。
     *
     * 字段口径与界面一致：date 是除息日（决定有没有资格拿），payDate 是到账日
     * （钱真正到账那天，通常晚 1~4 周）；两者都为空时到账日留空。金额按原币（元）导出，
     * 不折算——分红本来就是本币计，强行折算反而失真。
     */
    fun exportDividendsToCSV(dividends: List<Dividend>): Uri? {
        return try {
            val fileName = "dividends_${System.currentTimeMillis().toFileTimestamp()}.csv"
            val file = File(exportDir, fileName)

            openCsvWriter(file).use { writer ->
                writer.append("代码,名称,分红金额(元),除息日,到账日,是否手动添加\n")

                if (dividends.isEmpty()) {
                    writer.append("(暂无分红记录)\n")
                } else {
                    dividends.forEach { dividend ->
                        writer.append("${escapeCsv(dividend.symbol)},")
                        writer.append("${escapeCsv(dividend.name)},")
                        writer.append("${dividend.amount.format(2)},")
                        writer.append("${escapeCsv(dividend.date)},")
                        writer.append("${escapeCsv(dividend.payDate ?: "")},")
                        writer.append("${if (dividend.isManual) "是" else "否"}\n")
                    }
                }
            }

            getUriForFile(file)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("导出分红到CSV失败: ${e.message}", e, "DataExporter")
            null
        }
    }

    /**
     * 导出交易明细对账单（CSV）
     */
    fun exportTransactionsToCSV(assets: List<Asset>, transactions: List<Transaction>): Uri? {
        return try {
            val fileName = "statement_${System.currentTimeMillis().toFileTimestamp()}.csv"
            val file = File(exportDir, fileName)
            val assetMap = assets.associateBy { it.symbol }

            openCsvWriter(file).use { writer ->
                writer.append("日期,代码,名称,交易类型,数量,价格,手续费,成交金额(原币),币种,成交金额(人民币),账户,备注\n")

                transactions.sortedByDescending { it.date }.forEach { tx ->
                    val asset = assetMap[tx.symbol]
                    val typeLabel = TransactionType.fromValue(tx.type)?.name ?: tx.type
                    val amount = tx.quantity * tx.price
                    val amountCny = asset?.let { ExchangeRateManager.toCny(amount, it.currency) } ?: amount
                    val currency = asset?.currency ?: "CNY"

                    writer.append(listOf(
                        escapeCsv(tx.date),
                        escapeCsv(tx.symbol),
                        escapeCsv(tx.name),
                        escapeCsv(typeLabel),
                        tx.quantity.format(2),
                        tx.price.format(3),
                        tx.fee.format(2),
                        amount.format(2),
                        escapeCsv(currency),
                        amountCny.format(2),
                        escapeCsv(asset?.account ?: ""),
                        escapeCsv(asset?.notes ?: "")
                    ).joinToString(","))
                    writer.append("\n")
                }

                writer.append("\n")
                writer.append("汇总信息\n")
                val buyAmount = transactions
                    .filter { TransactionType.fromValue(it.type) == TransactionType.BUY }
                    .sumOf { tx ->
                        val asset = assetMap[tx.symbol]
                        asset?.let { ExchangeRateManager.toCny(tx.quantity * tx.price, it.currency) } ?: (tx.quantity * tx.price)
                    }
                val sellAmount = transactions
                    .filter { TransactionType.fromValue(it.type) == TransactionType.SELL }
                    .sumOf { tx ->
                        val asset = assetMap[tx.symbol]
                        asset?.let { ExchangeRateManager.toCny(tx.quantity * tx.price, it.currency) } ?: (tx.quantity * tx.price)
                    }
                val totalFee = transactions.sumOf { it.fee }
                writer.append("买入总金额(人民币),${buyAmount.format(2)}\n")
                writer.append("卖出总金额(人民币),${sellAmount.format(2)}\n")
                writer.append("总手续费,${totalFee.format(2)}\n")
                writer.append("交易笔数,${transactions.size}\n")
            }

            getUriForFile(file)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("导出交易对账单失败: ${e.message}", e, "DataExporter")
            null
        }
    }

    /**
     * 导出综合对账单（资产 + 交易 + 快照）
     */
    fun exportStatementCSV(
        assets: List<Asset>,
        transactions: List<Transaction>,
        snapshots: List<AssetSnapshot>
    ): Uri? {
        return try {
            val fileName = "full_statement_${System.currentTimeMillis().toFileTimestamp()}.csv"
            val file = File(exportDir, fileName)

            openCsvWriter(file).use { writer ->
                val dateStr = LocalDateTime.now().format(DateFormats.ISO_DATETIME_FORMATTER)
                writer.append("# 综合对账单,生成时间,$dateStr\n\n")

                // 资产部分
                writer.append("## 资产持仓\n")
                writer.append("名称,类型,代码,账户,数量,参考单价,当前价,总价值(原币),总价值(人民币),币种,汇率,备注,到期日\n")
                assets.forEach { asset ->
                    writer.append(buildCsvRow(asset))
                    writer.append("\n")
                }
                writer.append("\n")

                // 交易部分
                writer.append("## 交易明细\n")
                writer.append("日期,代码,名称,交易类型,数量,价格,手续费,成交金额(原币),币种,成交金额(人民币),账户\n")
                val assetMap = assets.associateBy { it.symbol }
                transactions.sortedByDescending { it.date }.forEach { tx ->
                    val asset = assetMap[tx.symbol]
                    val typeLabel = TransactionType.fromValue(tx.type)?.name ?: tx.type
                    val amount = tx.quantity * tx.price
                    val amountCny = asset?.let { ExchangeRateManager.toCny(amount, it.currency) } ?: amount
                    val currency = asset?.currency ?: "CNY"
                    writer.append(listOf(
                        escapeCsv(tx.date),
                        escapeCsv(tx.symbol),
                        escapeCsv(tx.name),
                        escapeCsv(typeLabel),
                        tx.quantity.format(2),
                        tx.price.format(3),
                        tx.fee.format(2),
                        amount.format(2),
                        escapeCsv(currency),
                        amountCny.format(2),
                        escapeCsv(asset?.account ?: "")
                    ).joinToString(","))
                    writer.append("\n")
                }
                writer.append("\n")

                // 快照部分
                writer.append("## 历史快照\n")
                writer.append("日期,总资产,总负债,净资产,股票,基金,虚拟货币,银行存款\n")
                snapshots.sortedBy { it.date }.forEach { snapshot ->
                    writer.append("${escapeCsv(snapshot.date)},${snapshot.totalAssets.format(2)},${snapshot.totalLiabilities.format(2)},${snapshot.netAssets.format(2)},${snapshot.stockValue.format(2)},${snapshot.fundValue.format(2)},${snapshot.cryptoValue.format(2)},${snapshot.bankDepositValue.format(2)}\n")
                }
            }

            getUriForFile(file)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("导出综合对账单失败: ${e.message}", e, "DataExporter")
            null
        }
    }

    fun exportToText(assets: List<Asset>): Uri? {
        return try {
            val fileName = "assets_${System.currentTimeMillis().toFileTimestamp()}.txt"
            val file = File(exportDir, fileName)
            
            openCsvWriter(file).use { writer ->
                val dateStr = LocalDateTime.now().format(DateFormats.CN_DATETIME_FORMATTER)
                writer.append("=== 资产报告 ===\n")
                writer.append("生成时间: $dateStr\n\n")
                
                val assetsOnly = assets.filter { !it.type.isLiability }
                val liabilities = assets.filter { it.type.isLiability }
                val totalAssets = assetsOnly.sumTotalValueCny()
                val totalLiabilities = liabilities.sumTotalValueCny()
                val netAssets = totalAssets - totalLiabilities

                writer.append("【净资产】\n")
                writer.append("  ¥${netAssets.format(2)}\n\n")

                writer.append("【总资产】: ¥${totalAssets.format(2)}\n")
                writer.append("【总负债】: ¥${totalLiabilities.format(2)}\n\n")
                
                writer.append("━━━━━━━━━━━━━━━━━━━━━━━━\n\n")
                
                if (assetsOnly.isNotEmpty()) {
                    writer.append("【资产详情】\n\n")
                    assetsOnly.groupBy { it.type }.forEach { (type, typeAssets) ->
                        writer.append("  ${type.icon} ${type.displayName}\n")
                        typeAssets.forEach { asset ->
                            writer.append("    - ${asset.name}\n")
                            writer.append("      代码: ${asset.symbol ?: "-"}\n")
                            writer.append("      数量: ${asset.balance.format(quantityPrecisionFor(type, asset.symbol))}\n")
                            writer.append("      当前价值: ${asset.currency} ${asset.totalValue.format(2)} (¥${asset.totalValueCny.format(2)})\n")
                            if (asset.currency != "CNY") {
                                writer.append("      汇率: ${ExchangeRateManager.getRate(asset.currency).format(4)}\n")
                            }
                            writer.append("\n")
                        }
                    }
                }
                
                if (liabilities.isNotEmpty()) {
                    writer.append("【负债详情】\n\n")
                    liabilities.groupBy { it.type }.forEach { (type, typeAssets) ->
                        writer.append("  ${type.icon} ${type.displayName}\n")
                        typeAssets.forEach { asset ->
                            writer.append("    - ${asset.name}\n")
                            // 跟资产详情那节保持一致：先给原币金额，再给折算后的人民币
                            writer.append("      金额: ${asset.currency} ${asset.totalValue.format(2)} (¥${asset.totalValueCny.format(2)})\n")
                            asset.notes?.let { writer.append("      备注: $it\n") }
                            writer.append("\n")
                        }
                    }
                }
                
                writer.append("\n━━━━━━━━━━━━━━━━━━━━━━━━\n")
                writer.append("报告生成完毕\n")
            }
            
            getUriForFile(file)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("导出资产到文本失败: ${e.message}", e, "DataExporter")
            null
        }
    }
    
    private fun buildCsvRow(asset: Asset): String {
        return listOf(
            escapeCsv(asset.name),
            escapeCsv(asset.type.displayName),
            escapeCsv(asset.symbol ?: ""),
            escapeCsv(asset.account),
            asset.balance.format(quantityPrecisionFor(asset.type, asset.symbol)),
            asset.purchasePrice.format(pricePrecisionFor(asset.type, asset.symbol)),
            (asset.currentPrice ?: asset.purchasePrice).format(pricePrecisionFor(asset.type, asset.symbol)),
            asset.totalValue.format(2),
            asset.totalValueCny.format(2),
            escapeCsv(asset.currency),
            ExchangeRateManager.getRate(asset.currency).format(4),
            escapeCsv(asset.notes ?: ""),
            escapeCsv(asset.dueDate ?: "")
        ).joinToString(",")
    }

    /**
     * 统一 CSV 字段转义：字段含引号、逗号或换行时用双引号包裹，并将内部引号转义为两个连续引号。
     */
    private fun escapeCsv(field: String): String {
        val needsQuoting = field.any { it == '"' || it == ',' || it == '\n' || it == '\r' }
        return if (needsQuoting) {
            "\"" + field.replace("\"", "\"\"") + "\""
        } else {
            field
        }
    }
    
    private fun getUriForFile(file: File): Uri {
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }
    
    fun shareFile(uri: Uri, title: String = "分享资产报告") {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = when {
                uri.toString().contains(".csv") -> "text/csv"
                uri.toString().contains(".txt") -> "text/plain"
                else -> "application/octet-stream"
            }
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        
        context.startActivity(Intent.createChooser(shareIntent, title))
    }
}
