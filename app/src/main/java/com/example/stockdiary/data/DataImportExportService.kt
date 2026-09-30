package com.example.stockdiary.data

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.room.withTransaction
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetSnapshot
import com.example.stockdiary.database.AssetSnapshotDetail
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.database.Dividend
import com.example.stockdiary.database.Portfolio
import com.example.stockdiary.database.StockDatabase
import com.example.stockdiary.database.Transaction
import com.example.stockdiary.utils.AppLogger
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import java.io.File
import java.io.FileInputStream
import java.io.FileWriter
import java.lang.reflect.Type

/**
 * 数据导入导出服务
 * 支持全量数据库数据的 JSON 格式导入导出
 * 用于换机迁移或重新安装 App 时恢复数据
 */
class DataImportExportService(private val context: Context) {
    
    companion object {
        private const val TAG = "DataImportExport"
        const val EXPORT_FORMAT_VERSION = 1
        const val FILE_EXTENSION = ".json"
        const val PAGE_SIZE = 500

        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: DataImportExportService? = null
        
        fun getInstance(context: Context): DataImportExportService {
            return instance ?: synchronized(this) {
                instance ?: DataImportExportService(context.applicationContext).also { instance = it }
            }
        }
    }
    
    private val gson: Gson = GsonBuilder()
        .setPrettyPrinting()
        .registerTypeAdapter(AssetType::class.java, AssetTypeAdapter())
        .create()
    
    /**
     * 导出所有数据库数据为 JSON 文件
     * 包含：资产、快照、快照详情、投资组合、分红记录、交易记录
     * 使用分页加载，避免一次性读取大量数据造成内存峰值
     */
    suspend fun exportAllData(): ExportResult {
        return try {
            val db = StockDatabase.getInstance(context)

            val assets = loadAllPaged { offset -> db.assetDao().getAssetsPaged(offset, PAGE_SIZE) }
            val snapshots = loadAllPaged { offset -> db.snapshotDao().getSnapshotsPaged(offset, PAGE_SIZE) }
            val snapshotDetails = loadAllPaged { offset -> db.snapshotDao().getSnapshotDetailsPaged(offset, PAGE_SIZE) }
            val portfolios = loadAllPaged { offset -> db.portfolioDao().getPortfoliosPaged(offset, PAGE_SIZE) }
            val dividends = loadAllPaged { offset -> db.dividendDao().getDividendsPaged(offset, PAGE_SIZE) }
            val transactions = loadAllPaged { offset -> db.transactionDao().getTransactionsPaged(offset, PAGE_SIZE) }

            val exportData = ExportData(
                version = EXPORT_FORMAT_VERSION,
                exportTime = System.currentTimeMillis(),
                appVersion = getAppVersion(),
                assets = assets,
                snapshots = snapshots,
                snapshotDetails = snapshotDetails,
                portfolios = portfolios,
                dividends = dividends,
                transactions = transactions
            )

            val jsonContent = gson.toJson(exportData)
            val fileName = "stockdiary_backup_${System.currentTimeMillis()}$FILE_EXTENSION"
            val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
            val file = File(exportDir, fileName)

            FileWriter(file).use { writer ->
                writer.write(jsonContent)
            }

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            AppLogger.i("数据导出成功: ${assets.size} 资产, ${snapshots.size} 快照", TAG)

            ExportResult(
                success = true,
                uri = uri,
                stats = mapOf(
                    "assets" to assets.size,
                    "snapshots" to snapshots.size,
                    "portfolios" to portfolios.size,
                    "dividends" to dividends.size,
                    "transactions" to transactions.size
                )
            )
        } catch (e: Exception) {
            AppLogger.e("数据导出失败: ${e.message}", e, TAG)
            ExportResult(success = false, errorMessage = e.message)
        }
    }

    /**
     * 分页加载所有数据，避免单次查询返回过多记录
     */
    private suspend fun <T> loadAllPaged(loadPage: suspend (Int) -> List<T>): List<T> {
        val result = mutableListOf<T>()
        var offset = 0
        while (true) {
            val page = loadPage(offset)
            if (page.isEmpty()) break
            result.addAll(page)
            if (page.size < PAGE_SIZE) break
            offset += PAGE_SIZE
        }
        return result
    }
    
    /**
     * 从 JSON 文件导入数据
     * 会先校验备份文件、备份当前数据，然后清空并导入新数据
     */
    suspend fun importAllData(uri: Uri): ImportResult {
        return try {
            val jsonContent = readUriContent(uri)
            val exportData = gson.fromJson(jsonContent, ExportData::class.java)

            val validation = validateExportData(exportData)
            if (!validation.isValid) {
                return ImportResult(success = false, errorMessage = validation.errorMessage)
            }

            val db = StockDatabase.getInstance(context)

            // 导入前先备份当前数据，防止导入失败或备份文件损坏导致数据丢失
            val backupResult = createInternalBackup(db)
            if (!backupResult.success) {
                AppLogger.w("导入前备份当前数据失败: ${backupResult.errorMessage}", TAG)
            }

            val assetCount = exportData.assets?.size ?: 0
            val snapshotCount = exportData.snapshots?.size ?: 0
            val portfolioCount = exportData.portfolios?.size ?: 0
            val dividendCount = exportData.dividends?.size ?: 0
            val transactionCount = exportData.transactions?.size ?: 0

            db.withTransaction {
                db.assetDao().deleteAllAssets()
                db.snapshotDao().deleteAllSnapshotDetails()
                db.snapshotDao().deleteAllSnapshots()
                db.portfolioDao().deleteAll()
                db.dividendDao().deleteAll()
                db.transactionDao().deleteAllTransactions()

                if (!exportData.assets.isNullOrEmpty()) {
                    db.assetDao().insertAssets(exportData.assets)
                }
                if (!exportData.snapshots.isNullOrEmpty()) {
                    db.snapshotDao().insertAllSnapshots(exportData.snapshots)
                }
                if (!exportData.snapshotDetails.isNullOrEmpty()) {
                    db.snapshotDao().insertAllSnapshotDetails(exportData.snapshotDetails)
                }
                if (!exportData.portfolios.isNullOrEmpty()) {
                    db.portfolioDao().insertAllPortfolios(exportData.portfolios)
                }
                if (!exportData.dividends.isNullOrEmpty()) {
                    db.dividendDao().insertAll(exportData.dividends)
                }
                if (!exportData.transactions.isNullOrEmpty()) {
                    db.transactionDao().insertAll(exportData.transactions)
                }
            }

            AppLogger.i("数据导入成功: $assetCount 资产, $snapshotCount 快照", TAG)

            ImportResult(
                success = true,
                importedCount = assetCount + snapshotCount + portfolioCount + dividendCount + transactionCount,
                stats = mapOf(
                    "assets" to assetCount,
                    "snapshots" to snapshotCount,
                    "portfolios" to portfolioCount,
                    "dividends" to dividendCount,
                    "transactions" to transactionCount
                )
            )
        } catch (e: Exception) {
            AppLogger.e("数据导入失败: ${e.message}", e, TAG)
            ImportResult(success = false, errorMessage = e.message)
        }
    }

    /**
     * 校验导入数据：版本、必填字段、枚举值、ID 唯一性、外键引用关系
     */
    private fun validateExportData(data: ExportData): ValidationResult {
        if (data.version != EXPORT_FORMAT_VERSION) {
            return ValidationResult.error("不支持的备份格式版本: ${data.version}")
        }

        data.assets?.forEachIndexed { index, asset ->
            if (asset.name.isBlank()) {
                return ValidationResult.error("资产第 ${index + 1} 条记录名称为空")
            }
            if (asset.balance < 0) {
                return ValidationResult.error("资产 '${asset.name}' 数量不能为负数")
            }
            if (asset.purchasePrice < 0) {
                return ValidationResult.error("资产 '${asset.name}' 成本价不能为负数")
            }
        }

        val assetIds = data.assets?.map { it.id } ?: emptyList()
        if (assetIds.size != assetIds.toSet().size) {
            return ValidationResult.error("资产 ID 存在重复")
        }

        val snapshotIds = data.snapshots?.map { it.id } ?: emptyList()
        if (snapshotIds.size != snapshotIds.toSet().size) {
            return ValidationResult.error("快照 ID 存在重复")
        }

        data.snapshotDetails?.forEachIndexed { index, detail ->
            if (detail.snapshotId <= 0) {
                return ValidationResult.error("快照详情第 ${index + 1} 条记录的 snapshotId 无效")
            }
            if (data.snapshots != null && detail.snapshotId !in snapshotIds) {
                return ValidationResult.error("快照详情第 ${index + 1} 条记录引用了不存在的快照 ${detail.snapshotId}")
            }
            if (detail.assetId < 0) {
                return ValidationResult.error("快照详情第 ${index + 1} 条记录的 assetId 无效")
            }
        }

        data.portfolios?.forEachIndexed { index, portfolio ->
            if (portfolio.symbol.isBlank() || portfolio.name.isBlank()) {
                return ValidationResult.error("投资组合第 ${index + 1} 条记录代码或名称为空")
            }
            if (portfolio.quantity < 0 || portfolio.purchasePrice < 0) {
                return ValidationResult.error("投资组合 '${portfolio.symbol}' 数量或价格不能为负数")
            }
        }

        data.dividends?.forEachIndexed { index, dividend ->
            if (dividend.symbol.isBlank() || dividend.date.isBlank()) {
                return ValidationResult.error("分红记录第 ${index + 1} 条代码或日期为空")
            }
            if (dividend.amount < 0) {
                return ValidationResult.error("分红记录 '${dividend.symbol}' 金额不能为负数")
            }
        }

        data.transactions?.forEachIndexed { index, transaction ->
            if (transaction.symbol.isBlank() || transaction.date.isBlank()) {
                return ValidationResult.error("交易记录第 ${index + 1} 条代码或日期为空")
            }
            if (transaction.quantity < 0 || transaction.price < 0 || transaction.fee < 0) {
                return ValidationResult.error("交易记录 '${transaction.symbol}' 数量、价格或手续费不能为负数")
            }
        }

        return ValidationResult.success()
    }

    /**
     * 将当前数据库内容导出到应用私有目录作为恢复备份
     */
    private suspend fun createInternalBackup(db: StockDatabase): ExportResult {
        return try {
            val assets = db.assetDao().getAllAssetsSync()
            val snapshots = db.snapshotDao().getAllSnapshotsSync()
            val snapshotDetails = db.snapshotDao().getAllSnapshotDetailsSync()
            val portfolios = db.portfolioDao().getAllPortfoliosSync()
            val dividends = db.dividendDao().getAllSync()
            val transactions = db.transactionDao().getAllTransactionsSync()

            val backupData = ExportData(
                version = EXPORT_FORMAT_VERSION,
                exportTime = System.currentTimeMillis(),
                appVersion = getAppVersion(),
                assets = assets,
                snapshots = snapshots,
                snapshotDetails = snapshotDetails,
                portfolios = portfolios,
                dividends = dividends,
                transactions = transactions
            )

            val jsonContent = gson.toJson(backupData)
            val file = File(context.filesDir, "stockdiary_auto_backup_before_import.json")
            FileWriter(file).use { it.write(jsonContent) }

            ExportResult(success = true)
        } catch (e: Exception) {
            AppLogger.e("创建导入前备份失败: ${e.message}", e, TAG)
            ExportResult(success = false, errorMessage = e.message)
        }
    }
    
    private fun readUriContent(uri: Uri): String {
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            return inputStream.bufferedReader().use { it.readText() }
        }
        throw IllegalStateException("无法读取文件")
    }
    
    private fun getAppVersion(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }
    
    data class ExportResult(
        val success: Boolean,
        val uri: Uri? = null,
        val errorMessage: String? = null,
        val stats: Map<String, Int> = emptyMap()
    )
    
    data class ImportResult(
        val success: Boolean,
        val importedCount: Int = 0,
        val errorMessage: String? = null,
        val stats: Map<String, Int> = emptyMap()
    )
}

/**
 * 导入数据校验结果
 */
private data class ValidationResult(
    val isValid: Boolean,
    val errorMessage: String? = null
) {
    companion object {
        fun success() = ValidationResult(true)
        fun error(message: String) = ValidationResult(false, message)
    }
}

/**
 * 导出数据结构
 */
data class ExportData(
    val version: Int,
    val exportTime: Long,
    val appVersion: String,
    val assets: List<Asset>?,
    val snapshots: List<AssetSnapshot>?,
    val snapshotDetails: List<AssetSnapshotDetail>?,
    val portfolios: List<Portfolio>?,
    val dividends: List<Dividend>?,
    val transactions: List<Transaction>?
)

/**
 * AssetType 的 Gson 序列化/反序列化适配器
 */
class AssetTypeAdapter : JsonSerializer<AssetType>, JsonDeserializer<AssetType> {
    override fun serialize(src: AssetType, typeOfSrc: Type, context: JsonSerializationContext): JsonElement {
        return JsonPrimitive(src.name)
    }
    
    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): AssetType {
        val raw = json.asString?.trim() ?: ""
        return try {
            AssetType.valueOf(raw)
        } catch (e: IllegalArgumentException) {
            AppLogger.w("导入数据包含未知资产类型: $raw，回退为 STOCK", "AssetTypeAdapter")
            AssetType.STOCK
        }
    }
}
