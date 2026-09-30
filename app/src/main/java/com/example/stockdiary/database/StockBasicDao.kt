package com.example.stockdiary.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface StockBasicDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(stockBasics: List<StockBasic>)

    @Query("SELECT * FROM stock_basic WHERE code LIKE :code || '%'")
    suspend fun searchByCode(code: String): List<StockBasic>

    @Query("SELECT * FROM stock_basic WHERE name LIKE :name || '%'")
    suspend fun searchByName(name: String): List<StockBasic>

    @Query("SELECT * FROM stock_basic WHERE code = :code")
    suspend fun getByCode(code: String): StockBasic?

    @Query("SELECT * FROM stock_basic")
    suspend fun getAll(): List<StockBasic>

    @Query("DELETE FROM stock_basic")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM stock_basic")
    suspend fun count(): Int
}