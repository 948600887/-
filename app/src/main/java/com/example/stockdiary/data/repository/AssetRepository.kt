package com.example.stockdiary.data.repository

import android.annotation.SuppressLint
import android.content.Context
import com.example.stockdiary.utils.AppLogger
import com.example.stockdiary.utils.StockCodeManager
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
import kotlinx.coroutines.CancellationException

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
        } catch (e: CancellationException) {
            throw e
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
    suspend fun updatePriceBySymbol(symbol: String, price: Double, millionCopiesIncome: Double? = null) {
        // !isFinite 是必须的：NaN 参与的任何比较都返回 false，`price <= 0` 拦不住 NaN
        if (!price.isFinite() || price <= 0.0) {
            AppLogger.w("拒绝写入非正价格: $symbol = $price（可能为解析失败）", "AssetRepository")
            return
        }
        assetDao.updatePriceBySymbol(symbol, price, System.currentTimeMillis(), millionCopiesIncome)
    }

    suspend fun updatePriceById(id: Long, price: Double) {
        if (!price.isFinite() || price <= 0.0) {
            AppLogger.w("拒绝写入非正价格: id=$id price=$price（可能为解析失败）", "AssetRepository")
            return
        }
        assetDao.updatePriceById(id, price, System.currentTimeMillis())
    }

    /**
     * 按代码校正证券名（只「导入后同步」链路调用）。
     *
     * 只改 name、不动其它字段；该不该改由调用方判断（见 [AssetSyncManager.nameCorrectionOrNull]）——
     * 普通行情同步不走这里，避免把用户故意改过的名字冲掉。
     */
    suspend fun updateNameById(id: Long, name: String) {
        assetDao.updateNameById(id, name, System.currentTimeMillis())
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
            db.dividendDao().deleteAll()
            db.transactionDao().deleteAllTransactions()
            // 净值持久化缓存也算「所有数据」：留着它，清完数据后下一次刷新
            // 会直接复用库里的旧净值 —— 用户以为清干净了，数字却原样回来。
            db.fundNavCacheDao().clear()
        }
    }
    
    suspend fun getAssetsByTypes(types: List<AssetType>): List<Asset> {
        return assetDao.getAssetsByTypes(types)
    }

    /**
     * 一次性修复已存的 LD 锁仓脏符号（见 [computeLdRepairs]）。
     *
     * ## 为什么需要它
     *
     * 旧版币安导入把 `LDBCH` 落库成 `LDBCHUSDT`（盲加 USDT），币安无此交易对，
     * 导致之后每次行情同步都 400。导入路径已修（BinanceImportService），但库里
     * **已经**按错符号落库的资产仍会继续 400，必须一次性把它们的 symbol 改成底层
     * 交易对（如 `LDBCHUSDT` → `BCHUSDT`）。
     *
     * ## 幂等 & 零数据丢失
     *
     * - 用 SharedPreferences 守卫，保证每个安装只跑一次；即使守卫失效重复调用，
     *   [computeLdRepairs] 本身是幂等纯函数，已修过的库 updates 为空，不会重复改动。
     * - 只改 symbol 字段；碰撞的条目整体跳过（记 AppLogger.w），绝不覆盖、不丢数据。
     *
     * @return [LdRepairSummary]：实际改了几条、跳了几条、是否因守卫而跳过
     */
    suspend fun repairLdDirtySymbols(): LdRepairSummary {
        val prefs = context.getSharedPreferences(REPAIR_PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_LD_REPAIR_DONE, false)) {
            AppLogger.d("LD 脏符号修复已执行过，跳过", "AssetRepository")
            return LdRepairSummary(updated = 0, skipped = 0, alreadyDone = true)
        }

        // 只检查加密货币资产：LD 脏符号只可能出现在加密持仓里
        val cryptoAssets = assetDao.getAssetsByTypes(listOf(AssetType.CRYPTO))
        val plan = computeLdRepairs(cryptoAssets)

        if (plan.updates.isEmpty()) {
            AppLogger.i("LD 脏符号修复：无需修复（库中无 LD 脏符号）", "AssetRepository")
            prefs.edit().putBoolean(KEY_LD_REPAIR_DONE, true).apply()
            return LdRepairSummary(updated = 0, skipped = plan.skipped.size, alreadyDone = false)
        }

        val byId = cryptoAssets.associateBy { it.id }
        try {
            StockDatabase.getInstance(context).withTransaction {
                for ((id, newSymbol) in plan.updates) {
                    val asset = byId[id] ?: continue
                    assetDao.updateAsset(asset.copy(symbol = newSymbol))
                }
            }
            // 碰撞跳过的条目记警告：不覆盖、不丢数据，留给用户手工处理
            plan.skipped.forEach { id ->
                val sym = byId[id]?.symbol
                AppLogger.w("LD 脏符号修复跳过（目标底层交易对已存在，不覆盖）：$id $sym", "AssetRepository")
            }
            AppLogger.i(
                "LD 脏符号修复完成：改 ${plan.updates.size} 条，跳过 ${plan.skipped.size} 条",
                "AssetRepository"
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("LD 脏符号修复失败: ${e.message}", e, "AssetRepository")
            // 失败也要标记已尝试？不标：下次启动可重试，避免脏数据永久卡在守卫外
            return LdRepairSummary(updated = 0, skipped = plan.skipped.size, alreadyDone = false)
        }

        prefs.edit().putBoolean(KEY_LD_REPAIR_DONE, true).apply()
        return LdRepairSummary(updated = plan.updates.size, skipped = plan.skipped.size, alreadyDone = false)
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
                AppLogger.i("Snapshot created: ${snapshot.date}, netAssets=${snapshot.netAssets}, totalAssets=${snapshot.totalAssets}")
                snapshot.copy(id = snapshotId)
            }
        } catch (e: CancellationException) {
            throw e
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
        // 存储边界强制统一代码形态：全项目约定存库的一律是 normalizeSymbol 的结果
        // （600519 → sh600519、场外 000306 → 000306、fund_ 前缀原样保留）。
        // 该函数幂等，调用方已规范化过也不会有变化；但少了这一道，
        // 任何新调用方漏掉规范化就会写进形态不一致的代码 ——
        // 同一标的被建成两条，或分红按另一种形态去查而永远抓不到（与 fund_ 前缀那次同源）。
        val normalizedSymbol = StockCodeManager.normalizeSymbol(symbol)

        return StockDatabase.getInstance(context).withTransaction {
            // 按 symbol + account 查找：不同账户的同名持仓独立核算，不跨账户合并
            val existingAsset = assetDao.getAssetBySymbolAndAccount(normalizedSymbol, account)

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
                    symbol = normalizedSymbol,
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
            recordTransactionInternal(normalizedSymbol, name, TransactionType.BUY.value, balance, price, fee)
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
        val date = LocalDate.now().toString()
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

        /** SharedPreferences 文件名（一次性修复的守卫用） */
        private const val REPAIR_PREFS = "stock_diary_repair"

        /** 标记「LD 脏符号修复」已执行过的 key，保证每个安装只跑一次 */
        private const val KEY_LD_REPAIR_DONE = "ld_dirty_symbol_repair_done"

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
