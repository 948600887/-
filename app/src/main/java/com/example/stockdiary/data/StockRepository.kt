package com.example.stockdiary.data

import android.content.Context
import com.example.stockdiary.common.Result
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * 股票数据仓库
 * 集成StockDataManager，提供统一的数据访问接口
 */
class StockRepository(
    private val stockDataManager: StockDataManager,
    private val localDataSource: LocalDataSource // 本地数据库（可选）
) {
    /**
     * 获取单只股票数据
     */
    suspend fun getStockData(symbol: String): Result<StockData> {
        return stockDataManager.getStockData(symbol)
    }
    
    /**
     * 批量获取股票数据
     */
    suspend fun getMultipleStocks(symbols: List<String>): Result<List<StockData>> {
        return coroutineScope {
            val results = symbols.map { symbol ->
                async { stockDataManager.getStockData(symbol) }
            }.awaitAll()
            
            val successes = results.mapNotNull { it.getOrNull() }
            val failures = results.mapNotNull { it.exceptionOrNull() }
            
            if (successes.isNotEmpty()) {
                Result.success(successes)
            } else {
                Result.failure(failures.firstOrNull() ?: Exception("No data"))
            }
        }
    }
    
    /**
     * 刷新股票数据（忽略缓存）
     */
    suspend fun refreshStockData(symbol: String): Result<StockData> {
        stockDataManager.clearCache()
        return stockDataManager.getStockData(symbol)
    }
    
    /**
     * 获取数据源状态
     */
    fun getDataSourceStatus(): List<Pair<String, Boolean>> {
        return stockDataManager.getDataSourceStatus()
    }
    
    /**
     * 获取缓存大小
     */
    fun getCacheSize(): Int {
        return stockDataManager.getCacheSize()
    }
    
    /**
     * 清除所有缓存
     */
    fun clearAllCache() {
        stockDataManager.clearCache()
    }
    
    companion object {
        @Volatile
        private var instance: StockRepository? = null
        
        fun getInstance(context: Context): StockRepository {
            return instance ?: synchronized(this) {
                instance ?: StockRepository(
                    stockDataManager = StockDataManager.getInstance(context),
                    localDataSource = LocalDataSource.getInstance(context)
                ).also { instance = it }
            }
        }
    }
}
