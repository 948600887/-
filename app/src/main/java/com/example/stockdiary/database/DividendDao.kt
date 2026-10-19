package com.example.stockdiary.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DividendDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(dividend: Dividend): Long

    @Update
    suspend fun update(dividend: Dividend)

    @Delete
    suspend fun delete(dividend: Dividend)

    @Query("DELETE FROM dividend WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM dividend ORDER BY date DESC")
    fun getAllFlow(): Flow<List<Dividend>>

    @Query("SELECT * FROM dividend ORDER BY date DESC")
    suspend fun getAll(): List<Dividend>

    @Query("SELECT * FROM dividend WHERE symbol = :symbol ORDER BY date DESC")
    fun getBySymbolFlow(symbol: String): Flow<List<Dividend>>

    @Query("SELECT * FROM dividend WHERE symbol = :symbol ORDER BY date DESC")
    suspend fun getBySymbol(symbol: String): List<Dividend>

    @Query("SELECT SUM(amount) FROM dividend WHERE symbol = :symbol")
    suspend fun getTotalDividendBySymbol(symbol: String): Double?

    @Query("SELECT SUM(amount) FROM dividend WHERE date BETWEEN :startDate AND :endDate")
    suspend fun getDividendsBetweenDates(startDate: String, endDate: String): Double?

    @Query("DELETE FROM dividend WHERE symbol = :symbol")
    suspend fun deleteBySymbol(symbol: String)

    @Query("DELETE FROM dividend WHERE symbol = :symbol AND isManual = 0")
    suspend fun deleteNonManualBySymbol(symbol: String)
    
    @Query("SELECT * FROM dividend WHERE symbol IN (:symbols) ORDER BY date DESC")
    suspend fun getBySymbols(symbols: List<String>): List<Dividend>

    /**
     * 整表改 symbol。只给「场外基金两种存库形态归一」这类一次性修复用 ——
     * 分红是按 symbol 查的，资产改了 symbol 而分红没改，历史分红会整段失联。
     */
    @Query("UPDATE dividend SET symbol = :newSymbol WHERE symbol = :oldSymbol")
    suspend fun renameSymbol(oldSymbol: String, newSymbol: String)
    
    @Query("SELECT * FROM dividend ORDER BY date DESC")
    suspend fun getAllSync(): List<Dividend>

    @Query("SELECT * FROM dividend ORDER BY date DESC LIMIT :limit OFFSET :offset")
    suspend fun getDividendsPaged(offset: Int, limit: Int): List<Dividend>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(dividends: List<Dividend>)
    
    @Query("DELETE FROM dividend")
    suspend fun deleteAll()
    
    @Query("SELECT * FROM dividend WHERE symbol = :symbol AND date = :date LIMIT 1")
    suspend fun getBySymbolAndDate(symbol: String, date: String): Dividend?
}
