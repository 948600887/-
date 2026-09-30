package com.example.stockdiary.network

data class SinaStockData(
    val name: String = "",
    val code: String = "",
    val price: Double = 0.0,
    val change: Double = 0.0,
    val changePercent: Double = 0.0,
    val open: Double = 0.0,
    val prevClose: Double = 0.0,
    val high: Double = 0.0,
    val low: Double = 0.0,
    val volume: Long = 0L,
    val amount: Double = 0.0,
    val date: String = "",
    val time: String = "",
    val peRatio: Double = 0.0,
    val marketCap: Double = 0.0,
    val circulatingCap: Double = 0.0,
    val turnoverRate: Double = 0.0,
    val pbRatio: Double = 0.0,
    val amplitude: Double = 0.0
)