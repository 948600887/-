package com.example.stockdiary.data.price

import com.example.stockdiary.common.Result
import com.example.stockdiary.network.model.StockData

/**
 * 股票数据源接口
 * 所有数据源都必须实现此接口
 *
 * 数据模型统一使用 [com.example.stockdiary.network.model.StockData]：
 * data/price 栈内部只认这一套行情模型，网络层各 API 的返回格式在适配处转换。
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
