package com.example.stockdiary.data.backup

import androidx.room.withTransaction
import com.example.stockdiary.database.AssetDao
import com.example.stockdiary.database.DividendDao
import com.example.stockdiary.database.FundNavCacheDao
import com.example.stockdiary.database.SnapshotDao
import com.example.stockdiary.database.StockBasicDao
import com.example.stockdiary.database.StockDatabase
import com.example.stockdiary.database.TransactionDao

/**
 * 全量备份的 Room 侧执行器：只负责「读全表」与「在单事务内整表替换」。
 *
 * ## 为什么导出 JSON 而不是复制 .db 文件
 *
 * 本库是多处持有 `StockDatabase.getInstance(context)` 与 DAO 引用的长生命周期单例，
 * 运行时替换数据库文件要先 close 单例、处理 WAL / SHM，再重建 Room 实例，否则会出现
 * 「磁盘是新的、内存里还是旧 DAO / 旧连接」甚至崩溃。走现有 DAO 逐表读写则完全不碰
 * 数据库文件、不改 schema（不撞 A2/A7 锁 v18），且 JSON 可读、可校验、能写 JVM 往返单测。
 *
 * ## 事务边界
 *
 * [restore] 内**先清 7 张表、再逐表写回**，整体包在 [withTransaction] 里：任何一步抛错
 * 由 Room 整体回滚，绝不会留下「清了一半」的库。JSON 解析与格式校验由调用方
 * （[com.example.stockdiary.ui.viewmodels.SettingsViewModel]）在事务外完成，本类收到的
 * 已是合法的 [BackupPayload]；这里再对 formatVersion 做一次纵深防御校验，同样在事务外，
 * 校验失败时一个 DAO 都不会被调用。
 *
 * 恢复成功后需要复位进程内内存态（分红缓存 / 派生行情缓存 / 汇率 / 同步失败集合），
 * 该动作由 ViewModel 用已注入的依赖在事务成功后执行——本类只持有 [StockDatabase]，
 * 刻意不持有 Context 与一堆 UI 单例，以保持纯数据层、便于 JVM 单测只验证事务契约。
 * 恢复完成后不自动触发行情同步（沿用「导入快照 commit 不自动同步」的既有取舍）。
 */
class BackupManager(private val db: StockDatabase) {

    /** 从 7 个 DAO 各读一次全表，组装成可读 JSON 的载荷。 */
    suspend fun buildPayload(): BackupPayload {
        val assetDao: AssetDao = db.assetDao()
        val snapshotDao: SnapshotDao = db.snapshotDao()
        val dividendDao: DividendDao = db.dividendDao()
        val transactionDao: TransactionDao = db.transactionDao()
        val stockBasicDao: StockBasicDao = db.stockBasicDao()
        val fundNavCacheDao: FundNavCacheDao = db.fundNavCacheDao()

        return BackupPayload(
            formatVersion = BACKUP_FORMAT_VERSION,
            dbVersion = currentDbVersion(),
            exportedAt = System.currentTimeMillis(),
            assets = assetDao.getAllAssetsSync(),
            snapshots = snapshotDao.getAllSnapshotsSync(),
            snapshotDetails = snapshotDao.getAllSnapshotDetailsSync(),
            dividends = dividendDao.getAllSync(),
            transactions = transactionDao.getAllTransactionsSync(),
            stockBasics = stockBasicDao.getAll(),
            fundNavCaches = fundNavCacheDao.getAll()
        )
    }

    /**
     * 用 [payload] 整表替换当前账目。
     *
     * @throws UnsupportedBackupVersionException 载荷格式版本高于当前支持版本（事务外，不写库）
     * @throws InvalidBackupException 载荷格式版本非法（事务外，不写库）
     *
     * 事务内任何 DAO 异常都原样向外传递（不吞），由 Room 回滚全部清表与写入。
     */
    suspend fun restore(payload: BackupPayload) {
        // ---- 事务外校验：任何失败都不进入事务、不碰任何 DAO ----
        if (payload.formatVersion > BACKUP_FORMAT_VERSION) {
            throw UnsupportedBackupVersionException(payload.formatVersion, BACKUP_FORMAT_VERSION)
        }
        if (payload.formatVersion < 1) {
            throw InvalidBackupException("备份格式版本非法：${payload.formatVersion}")
        }

        val assetDao: AssetDao = db.assetDao()
        val snapshotDao: SnapshotDao = db.snapshotDao()
        val dividendDao: DividendDao = db.dividendDao()
        val transactionDao: TransactionDao = db.transactionDao()
        val stockBasicDao: StockBasicDao = db.stockBasicDao()
        val fundNavCacheDao: FundNavCacheDao = db.fundNavCacheDao()

        // ---- 单事务：先清 7 张表，再逐表写回 ----
        db.withTransaction {
            assetDao.deleteAllAssets()
            snapshotDao.deleteAllSnapshots()
            snapshotDao.deleteAllSnapshotDetails()
            dividendDao.deleteAll()
            transactionDao.deleteAllTransactions()
            stockBasicDao.clearAll()
            fundNavCacheDao.clear()

            assetDao.insertAssets(payload.assets)
            snapshotDao.insertAllSnapshots(payload.snapshots)
            snapshotDao.insertAllSnapshotDetails(payload.snapshotDetails)
            dividendDao.insertAll(payload.dividends)
            transactionDao.insertAll(payload.transactions)
            stockBasicDao.insertAll(payload.stockBasics)
            fundNavCacheDao.upsertAll(payload.fundNavCaches)
        }
    }

    /**
     * 备份时记录当时的 DB 版本，仅作排查元信息，恢复时不参与任何迁移决策。
     * 读不到（例如 JVM 单测的 relaxed mock）回退 0，绝不让元信息读取阻断备份。
     */
    private fun currentDbVersion(): Int = try {
        db.openHelper.readableDatabase.version
    } catch (e: Exception) {
        0
    }
}
