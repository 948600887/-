package com.example.stockdiary.data.repository

import android.content.Context
import com.example.stockdiary.database.StockDatabase
import com.example.stockdiary.database.Transaction
import com.example.stockdiary.database.TransactionDao
import kotlinx.coroutines.flow.Flow

/**
 * 交易流水的唯一访问入口，收口对 [TransactionDao] 的直接访问，
 * 避免 ViewModel 直连 DAO（与 [AssetRepository] / [DividendRepository] 同一架构边界）。
 */
class TransactionRepository private constructor(
    private val transactionDao: TransactionDao
) {
    /** 设置页计数用：全部交易流水（按时间倒序） */
    fun getAllFlow(): Flow<List<Transaction>> = transactionDao.getAllFlow()

    /** 导出用：同步取全部交易流水 */
    suspend fun getAllSync(): List<Transaction> = transactionDao.getAllTransactionsSync()

    companion object {
        fun getInstance(context: Context): TransactionRepository =
            TransactionRepository(StockDatabase.getInstance(context).transactionDao())
    }
}
