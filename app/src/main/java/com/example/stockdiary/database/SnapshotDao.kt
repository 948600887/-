package com.example.stockdiary.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SnapshotDao {
    @Query("SELECT * FROM asset_snapshots ORDER BY timestamp DESC")
    fun getAllSnapshots(): Flow<List<AssetSnapshot>>
    
    @Query("SELECT * FROM asset_snapshots WHERE timestamp >= :startTime ORDER BY timestamp ASC")
    fun getSnapshotsSince(startTime: Long): Flow<List<AssetSnapshot>>
    
    @Query("SELECT * FROM asset_snapshots ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentSnapshots(limit: Int): Flow<List<AssetSnapshot>>
    
    @Query("SELECT * FROM asset_snapshots WHERE date = :date LIMIT 1")
    suspend fun getSnapshotByDate(date: String): AssetSnapshot?
    
    @Query("SELECT * FROM asset_snapshots ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestSnapshot(): AssetSnapshot?
    
    @Query("SELECT * FROM asset_snapshots ORDER BY timestamp DESC LIMIT 1")
    fun getLatestSnapshotFlow(): Flow<AssetSnapshot?>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSnapshot(snapshot: AssetSnapshot): Long
    
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

@Dao
interface SnapshotDetailDao {
    @Query("SELECT * FROM asset_snapshot_details WHERE snapshotId = :snapshotId")
    fun getDetailsBySnapshotId(snapshotId: Long): Flow<List<AssetSnapshotDetail>>
    
    @Query("SELECT * FROM asset_snapshot_details WHERE assetId = :assetId ORDER BY snapshotId DESC")
    fun getHistoryByAssetId(assetId: Long): Flow<List<AssetSnapshotDetail>>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDetails(details: List<AssetSnapshotDetail>)
    
    @Query("DELETE FROM asset_snapshot_details WHERE snapshotId = :snapshotId")
    suspend fun deleteDetailsBySnapshotId(snapshotId: Long)
}
