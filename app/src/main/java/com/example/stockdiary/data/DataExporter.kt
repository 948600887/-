package com.example.stockdiary.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetSnapshot
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.database.Transaction
import com.example.stockdiary.database.TransactionType
import com.example.stockdiary.utils.AppLogger
import com.example.stockdiary.utils.ExchangeRateManager
import com.example.stockdiary.utils.costCny
import com.example.stockdiary.utils.format
import com.example.stockdiary.utils.profitCny
import com.example.stockdiary.utils.sumCostCny
import com.example.stockdiary.utils.sumProfitCny
import com.example.stockdiary.utils.sumTotalValueCny
import com.example.stockdiary.utils.toFileTimestamp
import com.example.stockdiary.utils.totalValueCny
import java.io.File
import java.io.FileWriter
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
    
    fun exportAssetsToCSV(assets: List<Asset>): Uri? {
        return try {
            val fileName = "assets_${System.currentTimeMillis().toFileTimestamp()}.csv"
            val file = File(exportDir, fileName)
            
            FileWriter(file).use { writer ->
                writer.append("名称,类型,代码,账户,数量,成本价,当前价,总价值(原币),总价值(人民币),成本(人民币),盈亏(人民币),盈亏比例,币种,汇率,备注\n")

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
                writer.append("总成本(人民币),${assetsOnly.sumCostCny().format(2)}\n")
                writer.append("总盈亏(人民币),${assetsOnly.sumProfitCny().format(2)}\n")
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
            
            FileWriter(file).use { writer ->
                writer.append("日期,总资产,总负债,净资产,总成本,总收益,收益率,股票,股票成本,股票收益,基金,基金成本,基金收益,虚拟货币,虚拟货币成本,虚拟货币收益,银行存款,银行存款成本,银行存款收益\n")
                
                snapshots.sortedBy { it.date }.forEach { snapshot ->
                    writer.append("${escapeCsv(snapshot.date)},")
                    writer.append("${snapshot.totalAssets.format(2)},")
                    writer.append("${snapshot.totalLiabilities.format(2)},")
                    writer.append("${snapshot.netAssets.format(2)},")
                    writer.append("${snapshot.totalCost.format(2)},")
                    writer.append("${snapshot.totalProfit.format(2)},")
                    writer.append("${snapshot.yieldRate.format(2)},")
                    writer.append("${snapshot.stockValue.format(2)},")
                    writer.append("${snapshot.stockCost.format(2)},")
                    writer.append("${snapshot.stockProfit.format(2)},")
                    writer.append("${snapshot.fundValue.format(2)},")
                    writer.append("${snapshot.fundCost.format(2)},")
                    writer.append("${snapshot.fundProfit.format(2)},")
                    writer.append("${snapshot.cryptoValue.format(2)},")
                    writer.append("${snapshot.cryptoCost.format(2)},")
                    writer.append("${snapshot.cryptoProfit.format(2)},")
                    writer.append("${snapshot.bankDepositValue.format(2)},")
                    writer.append("${snapshot.bankDepositCost.format(2)},")
                    writer.append("${snapshot.bankDepositProfit.format(2)}\n")
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
     * 导出交易明细对账单（CSV）
     */
    fun exportTransactionsToCSV(assets: List<Asset>, transactions: List<Transaction>): Uri? {
        return try {
            val fileName = "statement_${System.currentTimeMillis().toFileTimestamp()}.csv"
            val file = File(exportDir, fileName)
            val assetMap = assets.associateBy { it.symbol }

            FileWriter(file).use { writer ->
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

            FileWriter(file).use { writer ->
                val dateStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                writer.append("# 综合对账单,生成时间,$dateStr\n\n")

                // 资产部分
                writer.append("## 资产持仓\n")
                writer.append("名称,类型,代码,账户,数量,成本价,当前价,总价值(原币),总价值(人民币),成本(人民币),盈亏(人民币),盈亏比例,币种,汇率,备注\n")
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
                writer.append("日期,总资产,总负债,净资产,总成本,总收益,收益率,股票,基金,虚拟货币,银行存款\n")
                snapshots.sortedBy { it.date }.forEach { snapshot ->
                    writer.append("${escapeCsv(snapshot.date)},${snapshot.totalAssets.format(2)},${snapshot.totalLiabilities.format(2)},${snapshot.netAssets.format(2)},${snapshot.totalCost.format(2)},${snapshot.totalProfit.format(2)},${snapshot.yieldRate.format(2)},${snapshot.stockValue.format(2)},${snapshot.fundValue.format(2)},${snapshot.cryptoValue.format(2)},${snapshot.bankDepositValue.format(2)}\n")
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
            
            FileWriter(file).use { writer ->
                val dateStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm"))
                writer.append("=== 资产报告 ===\n")
                writer.append("生成时间: $dateStr\n\n")
                
                val assetsOnly = assets.filter { !it.type.isLiability }
                val liabilities = assets.filter { it.type.isLiability }
                val totalAssets = assetsOnly.sumTotalValueCny()
                val totalCost = assetsOnly.sumCostCny()
                val totalProfit = assetsOnly.sumProfitCny()
                val totalLiabilities = liabilities.sumTotalValueCny()
                val netAssets = totalAssets - totalLiabilities

                writer.append("【净资产】\n")
                writer.append("  ¥${netAssets.format(2)}\n\n")

                writer.append("【总资产】: ¥${totalAssets.format(2)}\n")
                writer.append("【总成本】: ¥${totalCost.format(2)}\n")
                writer.append("【总盈亏】: ¥${totalProfit.format(2)}\n")
                writer.append("【总负债】: ¥${totalLiabilities.format(2)}\n\n")
                
                writer.append("━━━━━━━━━━━━━━━━━━━━━━━━\n\n")
                
                if (assetsOnly.isNotEmpty()) {
                    writer.append("【资产详情】\n\n")
                    assetsOnly.groupBy { it.type }.forEach { (type, typeAssets) ->
                        writer.append("  ${type.icon} ${type.displayName}\n")
                        typeAssets.forEach { asset ->
                            writer.append("    - ${asset.name}\n")
                            writer.append("      代码: ${asset.symbol ?: "-"}\n")
                            writer.append("      数量: ${asset.balance.format(2)}\n")
                            writer.append("      当前价值: ${asset.currency} ${asset.totalValue.format(2)} (¥${asset.totalValueCny.format(2)})\n")
                            writer.append("      成本: ¥${asset.costCny.format(2)}\n")
                            if (asset.type != AssetType.BANK_DEPOSIT) {
                                writer.append("      盈亏: ¥${asset.profitCny.format(2)} (${asset.profitPercent.format(2)}%)\n")
                            }
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
                            writer.append("      金额: ¥${asset.totalValue.format(2)}\n")
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
            asset.balance.format(2),
            asset.purchasePrice.format(2),
            (asset.currentPrice ?: asset.purchasePrice).format(2),
            asset.totalValue.format(2),
            asset.totalValueCny.format(2),
            asset.costCny.format(2),
            asset.profitCny.format(2),
            asset.profitPercent.format(2),
            escapeCsv(asset.currency),
            ExchangeRateManager.getRate(asset.currency).format(4),
            escapeCsv(asset.notes ?: "")
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
