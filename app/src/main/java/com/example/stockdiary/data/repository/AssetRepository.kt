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
import com.example.stockdiary.database.Dividend
import com.example.stockdiary.database.StockDatabase
import com.example.stockdiary.database.SnapshotDao
import com.example.stockdiary.database.Transaction
import com.example.stockdiary.database.TransactionType
import com.example.stockdiary.data.sync.planSnapshotDedup
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import kotlinx.coroutines.CancellationException

/**
 * 一条「待落库的价格」意图。
 *
 * 同步侧拿到价后**不立刻写库**，先把意图放进批量缓冲，凑够一批才整体落盘
 * （见 [AssetRepository.updatePricesInTransaction]）：单独一次 UPDATE 就会让 Room 的
 * 观察查询整表重查重发一次，逐个写即「每只标的 = 一次整页重组」。
 *
 * @param symbol 落价按代码匹配（与单条 [AssetRepository.updatePriceBySymbol] 同一口径）
 * @param price 现价；非正/NaN 在落库前被拦，不会把市值清零
 * @param millionCopiesIncome 场外基金的万份收益，股票/加密为 null
 */
data class PendingPriceWrite(
    val symbol: String,
    val price: Double,
    val millionCopiesIncome: Double? = null
)

class AssetRepository private constructor(
    private val assetDao: AssetDao,
    private val snapshotDao: SnapshotDao,
    private val context: Context
) {
    
    fun getAllAssets(): Flow<List<Asset>> = assetDao.getAllAssets()
    
    fun getAssetsByType(type: AssetType): Flow<List<Asset>> = assetDao.getAssetsByType(type)
    
    suspend fun getAssetById(id: Long): Asset? = assetDao.getAssetById(id)
    
    suspend fun getAssetBySymbol(symbol: String): Asset? = assetDao.getAssetBySymbol(symbol)
    
    suspend fun getAssetsBySymbols(symbols: List<String>): List<Asset> = 
        assetDao.getAssetsBySymbols(symbols)
    
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

    /**
     * 加密货币按 (symbol, account) 去重落库（#21）。
     *
     * 手动添加加密货币时，若同账户已存在同名代码（归一化后），直接**更新**那条，
     * 而不是再插一条 —— 否则同一持仓在列表里出现两行、市值被重复计入。
     * 归一化口径与表单落库一致（去 /- 空白并转大写），否则 BTCUSDT 与 BTC-USDT
     * 被判成两条。
     *
     * 仅用于加密货币；存款/负债等无代码（symbol 为 null）的资产仍走 [insertAsset]。
     */
    suspend fun upsertCryptoAsset(asset: Asset): Long {
        val normalizedSymbol = StockCodeManager.normalizeSymbolForType(AssetType.CRYPTO, asset.symbol ?: "")
        return StockDatabase.getInstance(context).withTransaction {
            val now = System.currentTimeMillis()
            val existing = if (normalizedSymbol.isNotBlank()) {
                assetDao.getAssetBySymbolAndAccount(normalizedSymbol, asset.account)
                    // 兼容升级前的旧数据：当时 CRYPTO 代码未归一（库里可能是 BTC/USDT），
                    // 精确匹配不到，按归一化后的代码在内存里再找一次，否则仍会插出第二条
                    ?: assetDao.getAllAssets().first().firstOrNull { candidate ->
                        candidate.type == AssetType.CRYPTO &&
                            candidate.account == asset.account &&
                            StockCodeManager.normalizeSymbolForType(
                                AssetType.CRYPTO,
                                candidate.symbol ?: ""
                            ) == normalizedSymbol
                    }
            } else {
                null
            }
            if (existing != null) {
                val updated = existing.copy(
                    // 顺手把历史未归一的代码改正，后续同步/去重都按新形态走
                    symbol = normalizedSymbol,
                    name = asset.name,
                    balance = asset.balance,
                    purchasePrice = asset.purchasePrice,
                    currentPrice = asset.currentPrice,
                    currency = asset.currency,
                    dueDate = asset.dueDate,
                    notes = asset.notes,
                    interestRate = asset.interestRate,
                    lastUpdateTime = now
                )
                assetDao.updateAsset(updated)
                existing.id
            } else {
                val newAsset = asset.copy(symbol = normalizedSymbol.takeIf { it.isNotBlank() })
                assetDao.insertAsset(newAsset)
            }
        }
    }
    
    suspend fun insertAssets(assets: List<Asset>) = assetDao.insertAssets(assets)

    /**
     * 把一段写库操作包进 Room 事务：要么全成，要么全不成。
     *
     * 导入落库必须整批原子：[com.example.stockdiary.data.importer.SnapshotAlignment.commit]
     * 先逐条 updateBalance / updatePriceById，最后一次性 insertAssets。中途炸掉
     * （SQLITE_FULL、约束冲突、进程被回收）留下的是**半套数据** —— 已有标的数量改了、
     * 新标的没进来，用户当下看到的总资产是错的。重导一次能收敛，但那一屏不该是错的。
     */
    suspend fun <T> inTransaction(block: suspend () -> T): T =
        StockDatabase.getInstance(context).withTransaction(block)
    
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

    /**
     * **批量**落价：把一批 Update 收进**一个 Room 事务**（[inTransaction]）。
     *
     * ## 为什么必须批
     *
     * [getAllAssets] 是 Room 的 Flow，assets 表每写一行 Room 就通知观察查询整表重查、
     * 重发一次；同步一轮要写上百个标的，「逐个写」= 上百次全表重查 + 上百次整页重组，
     * 这正是「点刷新就卡」的引擎（日历页同类问题已在 CalendarViewModel 实测到
     * 「一次全量刷新白跑 20+ 次」）。放进同一个事务后，Room **只在事务提交时失效一次**，
     * 发射次数降到约 N/批大小。
     *
     * 为什么不干脆把整轮同步的所有写都塞进一个事务：那样一旦进程中途被杀或被
     * 停止（看门狗截断 / 退后台取消），已抓取的价格会全部丢失；分批在中途落盘，
     * 最坏只丢最后不足一批的几个。这里只负责「怎么批」，批多大由调用方决定。
     *
     * @param writes 待落库的落价意图；内部照样逐条做非正价格拦截，与 [updatePriceBySymbol] 同口径
     */
    suspend fun updatePricesInTransaction(writes: List<PendingPriceWrite>) {
        if (writes.isEmpty()) return
        inTransaction {
            writes.forEach { write ->
                // 与单条版本完全相同的护栏：一次拽回来的 0.0 / NaN 不能把市值清零
                if (!write.price.isFinite() || write.price <= 0.0) {
                    AppLogger.w(
                        "拒绝写入非正价格: ${write.symbol} = ${write.price}（可能为解析失败）",
                        "AssetRepository"
                    )
                    return@forEach
                }
                assetDao.updatePriceBySymbol(
                    write.symbol,
                    write.price,
                    System.currentTimeMillis(),
                    write.millionCopiesIncome
                )
            }
        }
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
     * 一次性修复「同一只场外基金在库里两种形态」的历史数据：
     * 把纯数字形态（`004502`）统一成 `fund_` 形态（`fund_004502`）。
     *
     * ## 背景
     *
     * [StockCodeManager.normalizeSymbol] 从前对场外号段的纯数字代码**原样返回**，
     * 而基金E账户导入与基金持仓截图一律拼 `fund_` 前缀。两种形态
     * [StockCodeManager.isOTCFund] 都认，但字符串不相等 —— 快照对齐按 symbol 精确
     * 分组，于是同一只基金被判成两只：落两条资产、持仓翻倍，还分别显示在「场内/
     * 场外」两个分组里。normalizeSymbol 现在统一输出 `fund_` 形态（新数据不再分裂），
     * 这里修的是已经按纯数字落库的历史数据。
     *
     * ## 幂等 & 零数据丢失
     *
     * - SharedPreferences 守卫保证每个安装只跑一次；即使守卫失效重复调用，
     *   [computeOtcSymbolRepairs] 本身是幂等纯函数，已修过的库 updates 为空。
     * - 只改 symbol 字段；改的同时把 dividend / transactions 里的同名记录一起改过去，
     *   否则分红与交易流水会因为 symbol 对不上而整段失联。
     * - 碰撞的条目整体跳过（记 AppLogger.w），绝不覆盖、不丢数据。
     *
     * @return [OtcRepairSummary]：实际改了几条、跳了几条、是否因守卫而跳过
     */
    suspend fun repairOtcSymbolForms(): OtcRepairSummary {
        val prefs = context.getSharedPreferences(REPAIR_PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_OTC_SYMBOL_REPAIR_DONE, false)) {
            AppLogger.d("场外基金 symbol 形态修复已执行过，跳过", "AssetRepository")
            return OtcRepairSummary(updated = 0, skipped = 0, alreadyDone = true)
        }

        // 只查基金与股票：场外基金只可能落在这两类里（加密/存款的 symbol 不会命中场外号段）
        val assets = assetDao.getAssetsByTypes(listOf(AssetType.FUND, AssetType.STOCK))
        val plan = computeOtcSymbolRepairs(assets)

        if (plan.updates.isEmpty()) {
            AppLogger.i("场外基金 symbol 形态修复：无需修复（库中无纯数字场外 symbol）", "AssetRepository")
            prefs.edit().putBoolean(KEY_OTC_SYMBOL_REPAIR_DONE, true).apply()
            return OtcRepairSummary(updated = 0, skipped = plan.skipped.size, alreadyDone = false)
        }

        val byId = assets.associateBy { it.id }
        val db = StockDatabase.getInstance(context)
        try {
            db.withTransaction {
                for ((id, newSymbol) in plan.updates) {
                    val asset = byId[id] ?: continue
                    val oldSymbol = asset.symbol ?: continue
                    assetDao.updateAsset(asset.copy(symbol = newSymbol))
                    // 分红与交易流水按 symbol 关联，必须跟着改名，否则整段失联
                    db.dividendDao().renameSymbol(oldSymbol, newSymbol)
                    db.transactionDao().renameSymbol(oldSymbol, newSymbol)
                }
            }
            // 碰撞跳过的记警告：不覆盖、不丢数据，留给用户手工处理
            plan.skipped.forEach { id ->
                AppLogger.w(
                    "场外基金 symbol 形态修复跳过（规范形态已存在，不覆盖）：${byId[id]?.symbol}",
                    "AssetRepository"
                )
            }
            AppLogger.i(
                "场外基金 symbol 形态修复完成：改 ${plan.updates.size} 条，跳过 ${plan.skipped.size} 条",
                "AssetRepository"
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("场外基金 symbol 形态修复失败: ${e.message}", e, "AssetRepository")
            // 失败不标记已完成：下次启动可重试，避免脏数据永久卡在守卫外
            return OtcRepairSummary(updated = 0, skipped = plan.skipped.size, alreadyDone = false)
        }

        prefs.edit().putBoolean(KEY_OTC_SYMBOL_REPAIR_DONE, true).apply()
        return OtcRepairSummary(updated = plan.updates.size, skipped = plan.skipped.size, alreadyDone = false)
    }

    /**
     * 一次性修复「历史库里形态不规范的老三板（退市股）symbol」：
     * 把 `sh400030` / 裸 `400030` 统一归一成 `bj400xxx`。
     *
     * ## 背景
     *
     * 老三板代码段 400xxx / 420xxx 在同步链路被 [com.example.stockdiary.utils.StockCodeManager.isDelistedForSync]
     * 主动跳过（有意设计），但历史数据里可能存在形态不规范的退市 symbol（带 `sh` 前缀、
     * 或裸纯数字）。这些形态 `isDelistedForSync` 能正确识别并跳过，同步不会炸；但 symbol
     * 形态不统一会造成快照对齐把同一只判成两只、持仓页市场分组错乱、分红/交易流水按
     * symbol 关联对不上等隐患。这里把它们归一成 `bj400xxx`，与 [repairOtcSymbolForms]
     * 同源不同标的（标的是老三板退市股而非场外基金）。
     *
     * ## 幂等 & 零数据丢失
     *
     * - SharedPreferences 守卫保证每个安装只跑一次；即使守卫失效重复调用，
     *   [computeDelistedSymbolRepairs] 本身是幂等纯函数，已修过的库 updates 为空。
     * - 只改 symbol 字段；改的同时把 dividend / transactions 里的同名记录一起改过去，
     *   否则分红与交易流水会因为 symbol 对不上而整段失联。
     * - 碰撞的条目整体跳过（记 AppLogger.w），绝不覆盖、不丢数据。
     *
     * @return [DelistedRepairSummary]：实际改了几条、跳了几条、是否因守卫而跳过
     */
    suspend fun repairDelistedSymbolForms(): DelistedRepairSummary {
        val prefs = context.getSharedPreferences(REPAIR_PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DELISTED_SYMBOL_REPAIR_DONE, false)) {
            AppLogger.d("老三板 symbol 形态修复已执行过，跳过", "AssetRepository")
            return DelistedRepairSummary(updated = 0, skipped = 0, alreadyDone = true)
        }

        // 只查股票与基金：老三板是 STOCK；FUND 也查以防类型错配
        val assets = assetDao.getAssetsByTypes(listOf(AssetType.STOCK, AssetType.FUND))
        val plan = computeDelistedSymbolRepairs(assets)

        if (plan.updates.isEmpty()) {
            AppLogger.i("老三板 symbol 形态修复：无需修复（库中无形态不规范的老三板 symbol）", "AssetRepository")
            prefs.edit().putBoolean(KEY_DELISTED_SYMBOL_REPAIR_DONE, true).apply()
            return DelistedRepairSummary(updated = 0, skipped = plan.skipped.size, alreadyDone = false)
        }

        val byId = assets.associateBy { it.id }
        val db = StockDatabase.getInstance(context)
        try {
            db.withTransaction {
                for ((id, newSymbol) in plan.updates) {
                    val asset = byId[id] ?: continue
                    val oldSymbol = asset.symbol ?: continue
                    assetDao.updateAsset(asset.copy(symbol = newSymbol))
                    // 分红与交易流水按 symbol 关联，必须跟着改名，否则整段失联
                    db.dividendDao().renameSymbol(oldSymbol, newSymbol)
                    db.transactionDao().renameSymbol(oldSymbol, newSymbol)
                }
            }
            // 碰撞跳过的记警告：不覆盖、不丢数据，留给用户手工处理
            plan.skipped.forEach { id ->
                AppLogger.w(
                    "老三板 symbol 形态修复跳过（规范形态已存在，不覆盖）：${byId[id]?.symbol}",
                    "AssetRepository"
                )
            }
            AppLogger.i(
                "老三板 symbol 形态修复完成：改 ${plan.updates.size} 条，跳过 ${plan.skipped.size} 条",
                "AssetRepository"
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("老三板 symbol 形态修复失败: ${e.message}", e, "AssetRepository")
            // 失败不标记已完成：下次启动可重试，避免脏数据永久卡在守卫外
            return DelistedRepairSummary(updated = 0, skipped = plan.skipped.size, alreadyDone = false)
        }

        prefs.edit().putBoolean(KEY_DELISTED_SYMBOL_REPAIR_DONE, true).apply()
        return DelistedRepairSummary(updated = plan.updates.size, skipped = plan.skipped.size, alreadyDone = false)
    }

    /**
     * 创建快照。如果指定日期已存在快照，则先删除**全部**同日旧行再插入新快照，
     * 确保同一天只有一条记录，避免图表上出现多个重叠点。
     *
     * 空组合（一个持仓都没有）返回 null 且不落库：净资产 0 的「空快照」不是真实
     * 数据点，落在序列开头会被 dropLeadingEmptySnapshots 砍掉，落在**中段**
     * （清库后重建数据的场景）会把最大回撤打成 100%、月度收益做出假暴跌。
     */
    suspend fun createSnapshot(date: String): AssetSnapshot? {
        return try {
            StockDatabase.getInstance(context).withTransaction {
                // 先取持仓、确认非空，才删同日旧行。
                //
                // 顺序反过来的后果很隐蔽：从前是先 deleteSnapshotsByDate 再判空，而这里的
                // return@withTransaction null 只是让 lambda 正常返回、**不会回滚事务**，删除照旧提交
                // —— 于是清仓后点「保存快照」，当天原本正常的快照被静默抹掉，用户只看到
                // 「创建快照失败」，历史曲线白白少一个点。
                val assets = assetDao.getAllAssets().first()
                if (assets.none { !it.type.isLiability }) {
                    AppLogger.i("无持仓，跳过创建快照（不删当天已有行）: $date", "AssetRepository")
                    return@withTransaction null
                }
                snapshotDao.deleteSnapshotsByDate(date)
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

    /** 库里有没有任何资产。用于冷启动决定要不要排自动快照（清库后不排，导入资产后自然恢复） */
    suspend fun hasAnyAsset(): Boolean = assetDao.getAssetCount() > 0

    /** 全部分红记录（含手动添加），按除息日倒序。供设置页「导出分红 CSV」一次性落盘 */
    suspend fun getAllDividends(): List<Dividend> =
        StockDatabase.getInstance(context).dividendDao().getAll()

    /**
     * 一次性修复：快照表同日多行去重（保留 timestamp 最大的一条）。
     *
     * 历史成因：2026-10-18 前的 fillMissingSnapshots 逐日回填补快照（date 写被补的
     * 那天、timestamp 写补的时刻），叠加 getSnapshotByDate 无 ORDER BY 只删一条，
     * 用户库里可能存在同 date 多行。createSnapshot 已改为按 date 删全量防止再生产，
     * 存量行在这里清掉。
     *
     * SharedPreferences 守卫保证每个安装只跑一次；失败不标记已完成，下次启动重试
     * （与 repairDelistedSymbolForms 同模式，绝不因修复失败拖垮启动）。
     *
     * @return 删掉的行数；无需修复或已修复过返回 0
     */
    suspend fun repairDuplicateSnapshots(): Int {
        val prefs = context.getSharedPreferences(REPAIR_PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DUP_SNAPSHOT_REPAIR_DONE, false)) return 0
        return try {
            val all = snapshotDao.getAllSnapshotsSync()
            val duplicateIds = planSnapshotDedup(all)
            if (duplicateIds.isEmpty()) {
                prefs.edit().putBoolean(KEY_DUP_SNAPSHOT_REPAIR_DONE, true).apply()
                return 0
            }
            val byId = all.associateBy { it.id }
            var deleted = 0
            StockDatabase.getInstance(context).withTransaction {
                duplicateIds.forEach { id ->
                    byId[id]?.let {
                        snapshotDao.deleteSnapshot(it)
                        deleted++
                    }
                }
            }
            AppLogger.i(
                "快照同日多行去重完成：删除 $deleted 行，涉及 ${duplicateIds.size} 个日期",
                "AssetRepository"
            )
            prefs.edit().putBoolean(KEY_DUP_SNAPSHOT_REPAIR_DONE, true).apply()
            deleted
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("快照同日多行去重失败: ${e.message}", e, "AssetRepository")
            0
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
        fee: Double = 0.0,
        currency: String = "CNY",
        dueDate: String? = null,
        notes: String? = null,
        interestRate: Double? = null,
        currentPrice: Double? = null,
        /**
         * 是否同步写入一笔 BUY 交易记录。默认 true（加仓/补仓路径应记交易）。
         * 手动「添加资产」路径传 false：否则补录一笔历史持仓会被记成「今天按市价买入」，
         * 误导导出对账单与交易流水（#5）。加密货币走 [upsertCryptoAsset] 本身就不记交易。
         */
        recordTransaction: Boolean = true
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
                    // 新建分支：把表单里的币种/到期日/备注/利率/现价一并落库，
                    // 否则外币（如美股 USD）会被静默重置成 CNY、到期日存了看不到。
                    currency = currency,
                    dueDate = dueDate,
                    notes = notes,
                    interestRate = interestRate,
                    currentPrice = currentPrice,
                    lastUpdateTime = now
                )
                val newId = assetDao.insertAsset(newAsset)
                newAsset.copy(id = newId)
            }

            // 在同一事务中写入交易记录，失败时整体回滚。
            // recordTransaction 默认 true（加仓/补仓记交易）；手动「添加」路径传 false，
            // 否则补录历史持仓会被记成「今天按市价买入」，误导对账单（#5）。
            if (recordTransaction) {
                recordTransactionInternal(normalizedSymbol, name, TransactionType.BUY.value, balance, price, fee)
            }
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

        /** 标记「场外基金 symbol 形态归一」已执行过的 key，保证每个安装只跑一次 */
        private const val KEY_OTC_SYMBOL_REPAIR_DONE = "otc_symbol_form_repair_done"

        /** 标记「老三板 symbol 形态归一」已执行过的 key，保证每个安装只跑一次 */
        private const val KEY_DELISTED_SYMBOL_REPAIR_DONE = "delisted_symbol_repair_done"

        /** 标记「快照同日多行去重」已执行过的 key，保证每个安装只跑一次 */
        private const val KEY_DUP_SNAPSHOT_REPAIR_DONE = "dup_snapshot_repair_done"

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
