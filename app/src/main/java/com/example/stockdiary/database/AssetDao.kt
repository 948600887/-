package com.example.stockdiary.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AssetDao {
    
    @Query("SELECT * FROM assets ORDER BY type, name")
    fun getAllAssets(): Flow<List<Asset>>
    
    @Query("SELECT * FROM assets ORDER BY type, name")
    suspend fun getAllAssetsSync(): List<Asset>
    
    @Query("SELECT * FROM assets WHERE type = :type ORDER BY name")
    fun getAssetsByType(type: AssetType): Flow<List<Asset>>
    
    @Query("SELECT * FROM assets WHERE account = :account ORDER BY name")
    fun getAssetsByAccount(account: String): Flow<List<Asset>>
    
    @Query("SELECT * FROM assets WHERE id = :id")
    suspend fun getAssetById(id: Long): Asset?
    
    @Query("SELECT * FROM assets WHERE symbol = :symbol LIMIT 1")
    suspend fun getAssetBySymbol(symbol: String): Asset?

    @Query("SELECT * FROM assets WHERE symbol = :symbol AND account = :account LIMIT 1")
    suspend fun getAssetBySymbolAndAccount(symbol: String, account: String): Asset?
    
    @Query("SELECT * FROM assets WHERE symbol IN (:symbols)")
    suspend fun getAssetsBySymbols(symbols: List<String>): List<Asset>
    
    @Query("SELECT * FROM assets WHERE type IN (:types) ORDER BY type, name")
    suspend fun getAssetsByTypes(types: List<AssetType>): List<Asset>
    
    @Query("SELECT SUM(balance * COALESCE(currentPrice, purchasePrice)) FROM assets WHERE currency = :currency")
    fun getTotalValueByCurrency(currency: String): Flow<Double?>
    
    @Query("SELECT DISTINCT account FROM assets WHERE type = :type")
    suspend fun getAccountsByType(type: AssetType): List<String>
    
    @Query("SELECT DISTINCT account FROM assets")
    suspend fun getAllAccounts(): List<String>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAsset(asset: Asset): Long
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAssets(assets: List<Asset>)
    
    @Update
    suspend fun updateAsset(asset: Asset)
    
    @Query("UPDATE assets SET currentPrice = :price, lastSyncTime = :syncTime WHERE symbol = :symbol")
    suspend fun updatePriceBySymbol(symbol: String, price: Double, syncTime: Long)
    
    @Query("UPDATE assets SET currentPrice = :price, lastSyncTime = :syncTime WHERE id = :id")
    suspend fun updatePriceById(id: Long, price: Double, syncTime: Long)

    @Query("UPDATE assets SET name = :name, lastSyncTime = :syncTime WHERE id = :id")
    suspend fun updateNameById(id: Long, name: String, syncTime: Long)
    
    @Query("UPDATE assets SET balance = :balance, lastUpdateTime = :updateTime WHERE id = :id")
    suspend fun updateBalance(id: Long, balance: Double, updateTime: Long)
    
    @Delete
    suspend fun deleteAsset(asset: Asset)
    
    @Query("DELETE FROM assets WHERE id = :id")
    suspend fun deleteAssetById(id: Long)
    
    @Query("DELETE FROM assets WHERE type = :type")
    suspend fun deleteAssetsByType(type: AssetType)
    
    @Query("DELETE FROM assets")
    suspend fun deleteAllAssets()

    @Query("SELECT * FROM assets ORDER BY type, name LIMIT :limit OFFSET :offset")
    suspend fun getAssetsPaged(offset: Int, limit: Int): List<Asset>

    @Query("UPDATE assets SET balance = :balance, purchasePrice = :purchasePrice, lastUpdateTime = :updateTime WHERE id = :id")
    suspend fun updateBalanceAndPrice(id: Long, balance: Double, purchasePrice: Double, updateTime: Long)

    @Query("DELETE FROM assets WHERE id IN (:ids)")
    suspend fun deleteAssetsByIds(ids: Set<Long>)

    @Query("UPDATE assets SET account = :account, lastUpdateTime = :updateTime WHERE id IN (:ids)")
    suspend fun updateAccountForAssets(ids: Set<Long>, account: String, updateTime: Long)
}
