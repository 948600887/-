package com.example.stockdiary.data

import com.example.stockdiary.common.Result

/**
 * 股票数据源接口
 * 所有数据源都必须实现此接口
 */
interface StockDataSource {
    /**
     * 获取股票数据
     */
    suspend fun getStockData(symbol: String): Result<StockData>
    
    /**
     * 批量获取股票数据
     */
    suspend fun getStockDataBatch(symbols: List<String>): Result<List<StockData>>
    
    /**
     * 获取数据源名称
     */
    fun getSourceName(): String
    
    /**
     * 检查数据源是否可用
     */
    suspend fun isAvailable(): Boolean
}
