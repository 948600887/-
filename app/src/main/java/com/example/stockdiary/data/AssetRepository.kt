package com.example.stockdiary.data

import android.annotation.SuppressLint
import android.content.Context
import com.example.stockdiary.utils.AppLogger
import androidx.room.withTransaction
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetDao
import com.example.stockdiary.database.AssetSnapshot
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.database.StockDatabase
import com.example.stockdiary.database.SnapshotDao
import com.example.stockdiary.database.Transaction
import com.example.stockdiary.database.TransactionType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class AssetRepository private constructor(
    private val assetDao: AssetDao,
    private val snapshotDao: SnapshotDao,
    private val context: Context
) {
    
    fun getAllAssets(): Flow<List<Asset>> = assetDao.getAllAssets()
    
    fun getAssetsByType(type: AssetType): Flow<List<Asset>> = assetDao.getAssetsByType(type)
    
    fun getAssetsByAccount(account: String): Flow<List<Asset>> = assetDao.getAssetsByAccount(account)
    
    suspend fun getAssetById(id: Long): Asset? = assetDao.getAssetById(id)
    
    suspend fun getAssetBySymbol(symbol: String): Asset? = assetDao.getAssetBySymbol(symbol)
    
    suspend fun getAssetsBySymbols(symbols: List<String>): List<Asset> = 
        assetDao.getAssetsBySymbols(symbols)
    
    fun getTotalValueByCurrency(currency: String): Flow<Double?> = 
        assetDao.getTotalValueByCurrency(currency)
    
    suspend fun getAccountsByType(type: AssetType): List<String> = 
        assetDao.getAccountsByType(type)
    
    suspend fun getAllAccounts(): List<String> = assetDao.getAllAccounts()
    
    suspend fun insertAsset(asset: Asset): Long {
        return try {
            assetDao.insertAsset(asset)
        } catch (e: Exception) {
            AppLogger.e("Failed to insert asset: ${e.message}", e)
            throw e
        }
    }
    
    suspend fun insertAssets(assets: List<Asset>) = assetDao.insertAssets(assets)
    
    suspend fun updateAsset(asset: Asset) = assetDao.updateAsset(asset)
    
    /**
     * 更新现价。拒绝写入非正价格：行情/净值解析失败时常返回 0.0，
     * 直接落库会把持仓市值清零并污染总资产曲线，此处统一拦截。
     */
    suspend fun updatePriceBySymbol(symbol: String, price: Double) {
        if (price <= 0.0) {
            AppLogger.w("拒绝写入非正价格: $symbol = $price（可能为解析失败）", "AssetRepository")
            return
        }
        assetDao.updatePriceBySymbol(symbol, price, System.currentTimeMillis())
    }

    suspend fun updatePriceById(id: Long, price: Double) {
        if (price <= 0.0) {
            AppLogger.w("拒绝写入非正价格: id=$id price=$price（可能为解析失败）", "AssetRepository")
            return
        }
        assetDao.updatePriceById(id, price, System.currentTimeMillis())
    }
    
    suspend fun updateBalance(id: Long, balance: Double) = 
        assetDao.updateBalance(id, balance, System.currentTimeMillis())
    
    suspend fun deleteAsset(asset: Asset) = assetDao.deleteAsset(asset)
    
    suspend fun deleteAssetById(id: Long) = assetDao.deleteAssetById(id)
    
    suspend fun deleteAllAssets() = assetDao.deleteAllAssets()

    suspend fun deleteAssetsByIds(ids: Set<Long>) = assetDao.deleteAssetsByIds(ids)

    suspend fun updateAccountForAssets(ids: Set<Long>, account: String) =
        assetDao.updateAccountForAssets(ids, account, System.currentTimeMillis())

    /**
     * 清空所有数据（资产、快照、快照详情、投资组合、分红、交易记录）。
     * 使用事务保证原子性，避免中间状态导致数据不一致。
     */
    suspend fun deleteAllData() {
        val db = StockDatabase.getInstance(context)
        db.withTransaction {
            assetDao.deleteAllAssets()
            snapshotDao.deleteAllSnapshotDetails()
            snapshotDao.deleteAllSnapshots()
            db.portfolioDao().deleteAll()
            db.dividendDao().deleteAll()
            db.transactionDao().deleteAllTransactions()
        }
    }
    
    suspend fun getAssetsByTypes(types: List<AssetType>): List<Asset> {
        return assetDao.getAssetsByTypes(types)
    }
    
    /**
     * 创建快照。如果指定日期已存在快照，则先删除旧快照再插入新快照，
     * 确保同一天只有一条记录，避免图表上出现多个重叠点。
     */
    suspend fun createSnapshot(date: String): AssetSnapshot? {
        return try {
            StockDatabase.getInstance(context).withTransaction {
                snapshotDao.getSnapshotByDate(date)?.let { snapshotDao.deleteSnapshot(it) }
                val assets = assetDao.getAllAssets().first()
                val previousSnapshot = snapshotDao.getLatestSnapshot()
                val snapshot = AssetSnapshot.fromAssets(assets, date, previousSnapshot)
                val snapshotId = snapshotDao.insertSnapshot(snapshot)
                AppLogger.i("Snapshot created: ${snapshot.date}, netAssets=${snapshot.netAssets}, yield=${snapshot.yieldRate}%")
                snapshot.copy(id = snapshotId)
            }
        } catch (e: Exception) {
            AppLogger.e("Failed to create snapshot: ${e.message}", e)
            null
        }
    }

    /**
     * 替换指定日期的快照（语义与 createSnapshot 相同，保留别名供价格同步调用）
     */
    suspend fun replaceSnapshotForDate(date: String): AssetSnapshot? {
        return createSnapshot(date)
    }

    suspend fun getLatestSnapshot(): AssetSnapshot? = snapshotDao.getLatestSnapshot()

    suspend fun getSnapshotByDate(date: String): AssetSnapshot? = snapshotDao.getSnapshotByDate(date)

    fun getAllSnapshots(): Flow<List<AssetSnapshot>> = snapshotDao.getAllSnapshots()
    
    fun getSnapshotsSince(startTime: Long): Flow<List<AssetSnapshot>> = 
        snapshotDao.getSnapshotsSince(startTime)
    
    fun getRecentSnapshots(limit: Int): Flow<List<AssetSnapshot>> = 
        snapshotDao.getRecentSnapshots(limit)
    
    suspend fun deleteSnapshot(snapshot: AssetSnapshot) = snapshotDao.deleteSnapshot(snapshot)
    
    /**
     * 添加或合并股票/基金资产（替代旧 Portfolio 逻辑）
     * 如果已存在同 symbol 且同 account 的资产，则合并数量并重新计算加权成本价；
     * 不同账户的持仓独立核算，不跨账户合并。
     * 资产变更与交易记录写入在同一事务中执行，确保数据一致。
     */
    suspend fun addOrUpdateStockAsset(
        symbol: String,
        name: String,
        balance: Double,
        price: Double,
        account: String,
        assetType: AssetType = AssetType.STOCK,
        fee: Double = 0.0
    ): Asset {
        val now = System.currentTimeMillis()

        return StockDatabase.getInstance(context).withTransaction {
            // 按 symbol + account 查找：不同账户的同名持仓独立核算，不跨账户合并
            val existingAsset = assetDao.getAssetBySymbolAndAccount(symbol, account)

            val resultAsset = if (existingAsset != null) {
                val newBalance = existingAsset.balance + balance
                // 成本含买入手续费（与券商 App「摊薄成本」口径一致），全项目统一此口径
                val newPurchasePrice = if (newBalance > 0) {
                    ((existingAsset.balance * existingAsset.purchasePrice) + (balance * price) + fee) / newBalance
                } else {
                    price
                }
                assetDao.updateBalanceAndPrice(existingAsset.id, newBalance, newPurchasePrice, now)
                existingAsset.copy(
                    balance = newBalance,
                    purchasePrice = newPurchasePrice,
                    lastUpdateTime = now
                )
            } else {
                val newAsset = Asset(
                    name = name,
                    type = assetType,
                    symbol = symbol,
                    account = account,
                    balance = balance,
                    // 新建成仓时同样把手续费摊入成本
                    purchasePrice = if (balance > 0) price + fee / balance else price,
                    lastUpdateTime = now
                )
                val newId = assetDao.insertAsset(newAsset)
                newAsset.copy(id = newId)
            }

            // 在同一事务中写入交易记录，失败时整体回滚
            recordTransactionInternal(symbol, name, TransactionType.BUY.value, balance, price, fee)
            resultAsset
        }
    }

    /**
     * 卖出股票/基金资产
     * 如果数量 >= 持有量，则删除资产；否则减少数量。
     * 资产变更与交易记录写入在同一事务中执行，确保数据一致。
     */
    suspend fun sellStockAsset(asset: Asset, quantity: Double, fee: Double = 0.0): Asset? {
        val now = System.currentTimeMillis()

        return StockDatabase.getInstance(context).withTransaction {
            if (quantity >= asset.balance) {
                assetDao.deleteAsset(asset)
                recordTransactionInternal(
                    asset.symbol ?: "",
                    asset.name,
                    TransactionType.SELL.value,
                    asset.balance,
                    asset.currentPrice ?: asset.purchasePrice,
                    fee
                )
                null
            } else {
                val newBalance = asset.balance - quantity
                assetDao.updateBalance(asset.id, newBalance, now)
                recordTransactionInternal(
                    asset.symbol ?: "",
                    asset.name,
                    TransactionType.SELL.value,
                    quantity,
                    asset.currentPrice ?: asset.purchasePrice,
                    fee
                )
                asset.copy(balance = newBalance, lastUpdateTime = now)
            }
        }
    }

    /**
     * 更新资产的基本信息（数量和成本价）
     */
    suspend fun updateAssetPosition(asset: Asset, newBalance: Double, newPurchasePrice: Double) {
        val now = System.currentTimeMillis()
        assetDao.updateBalanceAndPrice(asset.id, newBalance, newPurchasePrice, now)
    }

    /**
     * 公开方法：在独立事务中记录交易。
     * 注意：当与资产变更一起调用时，应优先使用 [recordTransactionInternal] 以避免事务嵌套。
     */
    suspend fun recordTransaction(
        symbol: String,
        name: String,
        type: String,
        quantity: Double,
        price: Double,
        fee: Double = 0.0
    ) {
        StockDatabase.getInstance(context).withTransaction {
            recordTransactionInternal(symbol, name, type, quantity, price, fee)
        }
    }

    private suspend fun recordTransactionInternal(
        symbol: String,
        name: String,
        type: String,
        quantity: Double,
        price: Double,
        fee: Double = 0.0
    ) {
        val db = StockDatabase.getInstance(context)
        val date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        // 避免同一天同价位同数量的重复交易记录
        val existing = db.transactionDao().findDuplicate(symbol, type, quantity, price, date)
        if (existing != null) {
            AppLogger.d("跳过重复交易记录: $symbol $type $quantity@$price $date", "AssetRepository")
            return
        }
        val transaction = Transaction(
            symbol = symbol,
            name = name,
            type = type,
            quantity = quantity,
            price = price,
            date = date,
            fee = fee
        )
        db.transactionDao().insertAll(listOf(transaction))
    }
    
    companion object {
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: AssetRepository? = null
        
        fun getInstance(context: Context): AssetRepository {
            return instance ?: synchronized(this) {
                instance ?: AssetRepository(
                    assetDao = StockDatabase.getInstance(context).assetDao(),
                    snapshotDao = StockDatabase.getInstance(context).snapshotDao(),
                    context = context.applicationContext
                ).also { instance = it }
            }
        }
    }
}
