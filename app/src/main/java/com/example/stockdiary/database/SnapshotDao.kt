package com.example.stockdiary.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SnapshotDao {
    @Query("SELECT * FROM asset_snapshots ORDER BY timestamp DESC")
    fun getAllSnapshots(): Flow<List<AssetSnapshot>>
    
    @Query("SELECT * FROM asset_snapshots WHERE timestamp >= :startTime ORDER BY timestamp ASC")
    fun getSnapshotsSince(startTime: Long): Flow<List<AssetSnapshot>>

    /**
     * 历史页按所选周期查询：date 是 ISO 字符串（yyyy-MM-dd），可直接做字典序范围比较。
     * 上界传「今天」，未来日期的脏数据不进统计。
     */
    @Query("SELECT * FROM asset_snapshots WHERE date >= :startDate AND date <= :endDate ORDER BY date ASC, timestamp ASC")
    fun getSnapshotsInDateRange(startDate: String, endDate: String): Flow<List<AssetSnapshot>>

    /** 周期窗口前最近的一条基准点，用于期间增值/月度环比的基期 */
    @Query("SELECT * FROM asset_snapshots WHERE date < :startDate ORDER BY date DESC, timestamp DESC LIMIT 1")
    fun getSnapshotBeforeDate(startDate: String): Flow<AssetSnapshot?>

    /**
     * date 无法解析成 yyyy-MM-dd 的历史脏行。按周期查询用 date 做范围，脏行会被范围条件
     * 漏掉；单独取出来在内存里按 timestamp 兜底纳入（不静默丢数据）。
     */
    @Query("SELECT * FROM asset_snapshots WHERE date NOT GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]' ORDER BY timestamp ASC")
    fun getInvalidDateSnapshots(): Flow<List<AssetSnapshot>>

    /** 「全部」周期分页读取的失效信号：新增/删除会改变行数 */
    @Query("SELECT COUNT(*) FROM asset_snapshots")
    fun getSnapshotCountFlow(): Flow<Int>

    /** 「全部」周期分页读取的失效信号：同日覆盖写会改变最大创建时刻 */
    @Query("SELECT MAX(timestamp) FROM asset_snapshots")
    fun getSnapshotMaxTimestampFlow(): Flow<Long?>

    // 同步版：日历按"显示月"查询用。只拉当月起（含当月）的快照，而不是把整张
    // asset_snapshots 表都 load 进内存再按月筛——老用户积攒几百天每日快照时整表读取纯属浪费。
    @Query("SELECT * FROM asset_snapshots WHERE timestamp >= :startTime ORDER BY timestamp ASC")
    suspend fun getSnapshotsSinceSync(startTime: Long): List<AssetSnapshot>
    
    @Query("SELECT * FROM asset_snapshots ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentSnapshots(limit: Int): Flow<List<AssetSnapshot>>
    
    // ORDER BY timestamp DESC：万一同日多行的存量行没被 repairDuplicateSnapshots 清掉
    // （比如首次启动时修复失败了），读取侧也确定拿到最后写入的那条，而不是碰运气
    @Query("SELECT * FROM asset_snapshots WHERE date = :date ORDER BY timestamp DESC LIMIT 1")
    suspend fun getSnapshotByDate(date: String): AssetSnapshot?

    /**
     * 删掉某一天的全部快照行。
     *
     * 不能用 getSnapshotByDate + deleteSnapshot 组合：那条查询无 ORDER BY 且 LIMIT 1，
     * 同日多行时只删得掉一条，剩下的行会一直在库里（date/timestamp 错位的历史数据
     * 就可能造成同日多行）。createSnapshot 的「同日覆盖」语义必须删全量才成立。
     */
    @Query("DELETE FROM asset_snapshots WHERE date = :date")
    suspend fun deleteSnapshotsByDate(date: String)
    
    @Query("SELECT * FROM asset_snapshots ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestSnapshot(): AssetSnapshot?
    
    @Query("SELECT * FROM asset_snapshots ORDER BY timestamp DESC LIMIT 1")
    fun getLatestSnapshotFlow(): Flow<AssetSnapshot?>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSnapshot(snapshot: AssetSnapshot): Long

    /** 快照编辑：只改金额与汇总字段；date/timestamp 原样带回，快照日期不可改 */
    @Update
    suspend fun updateSnapshot(snapshot: AssetSnapshot)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSnapshots(snapshots: List<AssetSnapshot>)
    
    @Delete
    suspend fun deleteSnapshot(snapshot: AssetSnapshot)
    
    @Query("DELETE FROM asset_snapshots WHERE timestamp < :beforeTime")
    suspend fun deleteOldSnapshots(beforeTime: Long)
    
    @Query("SELECT * FROM asset_snapshots ORDER BY timestamp ASC")
    suspend fun getAllSnapshotsSync(): List<AssetSnapshot>

    @Query("SELECT * FROM asset_snapshots ORDER BY timestamp ASC LIMIT :limit OFFSET :offset")
    suspend fun getSnapshotsPaged(offset: Int, limit: Int): List<AssetSnapshot>

    @Query("DELETE FROM asset_snapshots")
    suspend fun deleteAllSnapshots()

    @Query("SELECT * FROM asset_snapshot_details ORDER BY snapshotId")
    suspend fun getAllSnapshotDetailsSync(): List<AssetSnapshotDetail>

    @Query("SELECT * FROM asset_snapshot_details ORDER BY snapshotId LIMIT :limit OFFSET :offset")
    suspend fun getSnapshotDetailsPaged(offset: Int, limit: Int): List<AssetSnapshotDetail>

    @Query("DELETE FROM asset_snapshot_details")
    suspend fun deleteAllSnapshotDetails()
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllSnapshots(snapshots: List<AssetSnapshot>)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllSnapshotDetails(details: List<AssetSnapshotDetail>)
}

// 这里原本还有一个 `SnapshotDetailDao`（按 snapshotId / assetId 查明细的 4 个方法）。
// 它已于本轮删除：**从未在 `@Database(entities/daos)` 里注册过**，Room 不会为未注册的
// DAO 生成实现、`StockDatabase` 也没有对应的 `snapshotDetailDao()` 访问器，于是全仓
// 0 引用、任何代码都拿不到它的实例——它不是「差一行就接上」，而是连实例都取不到的骨架。
//
// 若将来真要按资产查历史明细：重新引入接口 + **在 `@Database` 里注册** + 加访问器，
// 三件事一起做；只写接口不注册等于又留一份会误导人的死代码。
