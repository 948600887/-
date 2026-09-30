package com.example.stockdiary.data

/**
 * 分红API接口
 * 定义所有分红API服务必须实现的方法
 */
interface DividendApi {
    /**
     * 获取股票历史分红数据
     * @param stockCode 股票代码，如600519
     */
    suspend fun getDividendHistory(stockCode: String): Result<List<DividendData>>
    
    /**
     * 获取最新分红数据
     * @param stockCode 股票代码，如600519
     */
    suspend fun getLatestDividend(stockCode: String): Result<List<DividendData>>
}