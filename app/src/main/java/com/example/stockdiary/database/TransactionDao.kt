package com.example.stockdiary.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Delete
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
    @Insert
    suspend fun insert(transaction: Transaction): Long

    @Insert
    @androidx.room.Transaction
    suspend fun insertAll(transactions: List<Transaction>)

    @Update
    suspend fun update(transaction: Transaction)

    @Delete
    suspend fun delete(transaction: Transaction)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM transactions ORDER BY date DESC")
    fun getAllFlow(): Flow<List<Transaction>>

    @Query("SELECT * FROM transactions ORDER BY date DESC")
    suspend fun getAll(): List<Transaction>

    @Query("SELECT * FROM transactions WHERE symbol = :symbol ORDER BY date DESC")
    fun getBySymbolFlow(symbol: String): Flow<List<Transaction>>

    @Query("SELECT * FROM transactions WHERE symbol = :symbol ORDER BY date DESC")
    suspend fun getBySymbol(symbol: String): List<Transaction>

    @Query("SELECT * FROM transactions WHERE date BETWEEN :startDate AND :endDate ORDER BY date DESC")
    suspend fun getByDateRange(startDate: String, endDate: String): List<Transaction>

    @Query("SELECT * FROM transactions WHERE type = :type ORDER BY date DESC")
    suspend fun getByType(type: String): List<Transaction>

    @Query("DELETE FROM transactions WHERE symbol = :symbol")
    suspend fun deleteBySymbol(symbol: String)

    @Query("SELECT COUNT(*) FROM transactions")
    suspend fun getCount(): Int

    @Query("SELECT SUM(CASE WHEN type = 'BUY' THEN -price * quantity WHEN type = 'SELL' THEN price * quantity ELSE 0 END) FROM transactions WHERE symbol = :symbol")
    suspend fun getNetCostBySymbol(symbol: String): Double?

    @Query("SELECT SUM(CASE WHEN type = 'BUY' THEN quantity WHEN type = 'SELL' THEN -quantity ELSE 0 END) FROM transactions WHERE symbol = :symbol")
    suspend fun getNetQuantityBySymbol(symbol: String): Double?
    
    @Query("SELECT * FROM transactions ORDER BY date DESC")
    suspend fun getAllTransactionsSync(): List<Transaction>

    @Query("SELECT * FROM transactions ORDER BY date DESC LIMIT :limit OFFSET :offset")
    suspend fun getTransactionsPaged(offset: Int, limit: Int): List<Transaction>

    @Query("DELETE FROM transactions")
    suspend fun deleteAllTransactions()

    @Query("SELECT * FROM transactions WHERE symbol = :symbol AND type = :type AND quantity = :quantity AND price = :price AND date = :date LIMIT 1")
    suspend fun findDuplicate(symbol: String, type: String, quantity: Double, price: Double, date: String): Transaction?
}
