package com.example.stockdiary.data

/**
 * 统一的股票数据模型
 * 用于在应用内部传递数据，屏蔽不同API的返回格式差异
 */
data class StockData(
    val symbol: String,          // 股票代码，如 "sh600000"
    val name: String,            // 股票名称
    val price: Double,           // 当前价格
    val open: Double,            // 今日开盘价
    val prevClose: Double,       // 昨日收盘价
    val high: Double,            // 今日最高价
    val low: Double,             // 今日最低价
    val volume: Long,            // 成交量（股）
    val amount: Double,          // 成交金额（元）
    val bidPrice: Double = 0.0,  // 买一价
    val askPrice: Double = 0.0,  // 卖一价
    val timestamp: String = ""   // 数据时间戳
)
