package com.example.stockdiary.data

import android.annotation.SuppressLint
import android.content.Context
import com.example.stockdiary.utils.AppLogger

/**
 * 本地数据源
 * 用于存储和获取本地股票数据
 */
class LocalDataSource(
    private val context: Context
) {
    // 本地存储的股票基本信息
    private val stockBasicInfo = mutableListOf<StockBasicInfo>()
    
    /**
     * 获取所有股票基本信息
     */
    fun getAllStocks(): List<StockBasicInfo> {
        return stockBasicInfo
    }
    
    /**
     * 根据代码获取股票基本信息
     */
    fun getStockByCode(code: String): StockBasicInfo? {
        return stockBasicInfo.find { it.code == code || it.sinaCode == code }
    }
    
    /**
     * 搜索股票
     */
    fun searchStocks(keyword: String): List<StockBasicInfo> {
        val normalizedKeyword = keyword.trim().lowercase()
        return stockBasicInfo.filter {
            it.code == normalizedKeyword ||
            it.sinaCode == normalizedKeyword ||
            it.name.lowercase().contains(normalizedKeyword) ||
            "${it.market}${it.code}" == normalizedKeyword
        }
    }
    
    /**
     * 保存股票基本信息列表
     */
    fun saveStockList(stocks: List<StockBasicInfo>) {
        stockBasicInfo.clear()
        stockBasicInfo.addAll(stocks)
        AppLogger.d("已保存 ${stocks.size} 只股票", "LocalDataSource")
    }
    
    /**
     * 检查是否有股票数据
     */
    fun hasStockData(): Boolean {
        return stockBasicInfo.isNotEmpty()
    }
    
    companion object {
        // 单例模式，持有的是 applicationContext，不会泄漏 Activity
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: LocalDataSource? = null
        
        fun getInstance(context: Context): LocalDataSource {
            return instance ?: synchronized(this) {
                instance ?: LocalDataSource(context.applicationContext).also { instance = it }
            }
        }
    }
}
