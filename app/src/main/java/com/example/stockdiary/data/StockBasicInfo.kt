package com.example.stockdiary.data

/**
 * 股票基本信息
 * 用于存储股票的基本信息，如代码、名称、市场等
 */
data class StockBasicInfo(
    val code: String,          // 股票代码（纯数字）
    val name: String,          // 股票名称
    val market: String,        // 市场代码（sh/sz/bj）
    val sinaCode: String       // 新浪API代码格式（如sh600000）
)
