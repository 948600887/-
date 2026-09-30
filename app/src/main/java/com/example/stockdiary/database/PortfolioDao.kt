package com.example.stockdiary.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Delete
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface PortfolioDao {
    @Insert
    suspend fun insert(portfolio: Portfolio): Long

    @Insert
    @Transaction
    suspend fun insertAll(portfolios: List<Portfolio>)

    @Update
    suspend fun update(portfolio: Portfolio)

    @Update
    @Transaction
    suspend fun updateAll(portfolios: List<Portfolio>)

    @Delete
    suspend fun delete(portfolio: Portfolio)

    @Query("DELETE FROM portfolio WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM portfolio ORDER BY symbol ASC")
    fun getAllFlow(): Flow<List<Portfolio>>

    @Query("SELECT * FROM portfolio ORDER BY symbol ASC")
    suspend fun getAll(): List<Portfolio>

    @Query("SELECT * FROM portfolio WHERE symbol = :symbol")
    fun getBySymbolFlow(symbol: String): Flow<Portfolio?>

    @Query("SELECT * FROM portfolio WHERE symbol = :symbol")
    suspend fun getBySymbol(symbol: String): Portfolio?

    @Query("SELECT * FROM portfolio WHERE id = :id")
    suspend fun getById(id: Long): Portfolio?

    @Query("SELECT * FROM portfolio WHERE account = :account ORDER BY symbol ASC")
    fun getByAccountFlow(account: String): Flow<List<Portfolio>>

    @Query("SELECT * FROM portfolio WHERE account = :account ORDER BY symbol ASC")
    suspend fun getByAccount(account: String): List<Portfolio>

    @Query("SELECT DISTINCT account FROM portfolio")
    suspend fun getAllAccounts(): List<String>

    @Query("DELETE FROM portfolio WHERE symbol = :symbol")
    suspend fun deleteBySymbol(symbol: String)

    @Query("SELECT COUNT(*) FROM portfolio")
    suspend fun getCount(): Int

    @Query("SELECT SUM(quantity * currentPrice) FROM portfolio")
    suspend fun getTotalMarketValue(): Double?

    @Query("SELECT SUM(quantity * purchasePrice) FROM portfolio")
    suspend fun getTotalCost(): Double?
    
    @Query("SELECT * FROM portfolio ORDER BY symbol ASC")
    suspend fun getAllPortfoliosSync(): List<Portfolio>

    @Query("SELECT * FROM portfolio ORDER BY symbol ASC LIMIT :limit OFFSET :offset")
    suspend fun getPortfoliosPaged(offset: Int, limit: Int): List<Portfolio>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllPortfolios(portfolios: List<Portfolio>)
    
    @Query("DELETE FROM portfolio")
    suspend fun deleteAll()
}
