package com.example.stockdiary.network.model

import com.example.stockdiary.network.base.MarketData
import com.example.stockdiary.network.base.PriceData

data class StockData(
    override val code: String,
    override val name: String,
    override val price: Double,
    override val change: Double?,
    override val changePercent: Double?,
    override val timestamp: Long = System.currentTimeMillis(),
    override val high: Double? = null,
    override val low: Double? = null,
    override val open: Double? = null,
    override val volume: Long? = null,
    override val amount: Double? = null,
    val yesterdayClose: Double? = null,
    val market: String? = null
) : PriceData, MarketData {
    
    val isUp: Boolean get() = (change ?: 0.0) > 0
    val isDown: Boolean get() = (change ?: 0.0) < 0
    val isFlat: Boolean get() = (change ?: 0.0) == 0.0
    
    companion object {
        fun fromSina(code: String, data: String): StockData? {
            val parts = data.split("=").getOrNull(1)?.trim()?.removePrefix("\"")?.removeSuffix("\";")?.split(",")
                ?: return null
            
            return try {
                StockData(
                    code = code,
                    name = parts.getOrNull(0) ?: "",
                    price = parts.getOrNull(3)?.toDoubleOrNull() ?: 0.0,
                    yesterdayClose = parts.getOrNull(2)?.toDoubleOrNull(),
                    open = parts.getOrNull(1)?.toDoubleOrNull(),
                    volume = parts.getOrNull(8)?.toLongOrNull(),
                    high = parts.getOrNull(33)?.toDoubleOrNull(),
                    low = parts.getOrNull(34)?.toDoubleOrNull(),
                    amount = parts.getOrNull(37)?.toDoubleOrNull(),
                    change = parts.getOrNull(3)?.toDoubleOrNull()?.minus(parts.getOrNull(2)?.toDoubleOrNull() ?: 0.0),
                    changePercent = calculateChangePercent(parts),
                    timestamp = System.currentTimeMillis()
                )
            } catch (e: Exception) { null }
        }
        
        private fun calculateChangePercent(parts: List<String>): Double? {
            val current = parts.getOrNull(3)?.toDoubleOrNull() ?: return null
            val yesterday = parts.getOrNull(2)?.toDoubleOrNull() ?: return null
            return if (yesterday != 0.0) ((current - yesterday) / yesterday * 100) else null
        }
    }
}

data class FundData(
    override val code: String,
    override val name: String,
    override val price: Double,
    override val change: Double?,
    override val changePercent: Double?,
    override val timestamp: Long = System.currentTimeMillis(),
    val netWorth: Double? = null,
    val netWorthDate: String? = null,
    val fundType: String? = null,
    val estimatedValue: Double? = null,
    val millionCopiesIncome: Double? = null
) : PriceData {
    
    val isUp: Boolean get() = (changePercent ?: 0.0) > 0
    val isEstimated: Boolean get() = netWorth != null && price != netWorth
    
    companion object {
        fun fromEastmoney(code: String, json: String): FundData? {
            val cleanJson = json.removePrefix("jsonpgz(").removeSuffix(")").trim()
            
            return try {
                FundData(
                    code = parseValue(cleanJson, "fundcode") ?: code,
                    name = parseValue(cleanJson, "name") ?: "",
                    estimatedValue = parseValue(cleanJson, "gsz")?.toDoubleOrNull(),
                    netWorth = parseValue(cleanJson, "dwjz")?.toDoubleOrNull(),
                    price = parseValue(cleanJson, "gsz")?.toDoubleOrNull() ?: 0.0,
                    changePercent = parseValue(cleanJson, "gszzl")?.toDoubleOrNull(),
                    change = calculateChange(parseValue(cleanJson, "gsz"), parseValue(cleanJson, "dwjz")),
                    netWorthDate = parseValue(cleanJson, "jztime")
                )
            } catch (e: Exception) { null }
        }
        
        private fun parseValue(json: String, key: String): String? {
            val pattern = "\"$key\"\\s*:\\s*\"?([^,\"}]+)\"?".toRegex()
            return pattern.find(json)?.groupValues?.getOrNull(1)?.trim()
        }
        
        private fun calculateChange(gsz: String?, dwjz: String?): Double? {
            val gszVal = gsz?.toDoubleOrNull() ?: return null
            val dwjzVal = dwjz?.toDoubleOrNull() ?: return null
            return if (dwjzVal != 0.0) gszVal - dwjzVal else null
        }
    }
}

data class CryptoData(
    override val code: String,
    override val name: String,
    override val price: Double,
    override val change: Double?,
    override val changePercent: Double?,
    override val timestamp: Long = System.currentTimeMillis(),
    override val high: Double? = null,
    override val low: Double? = null,
    override val open: Double? = null,
    override val volume: Long? = null,
    override val amount: Double? = null
) : PriceData, MarketData {
    
    val isUp: Boolean get() = (changePercent ?: 0.0) > 0
}

sealed class AssetPrice {
    data class Stock(val data: StockData) : AssetPrice()
    data class Fund(val data: FundData) : AssetPrice()
    data class Crypto(val data: CryptoData) : AssetPrice()
    
    val price: Double get() = when (this) {
        is Stock -> data.price
        is Fund -> data.price
        is Crypto -> data.price
    }
    
    val code: String get() = when (this) {
        is Stock -> data.code
        is Fund -> data.code
        is Crypto -> data.code
    }
}

data class MarketStatus(
    val isOpen: Boolean,
    val nextOpen: String?,
    val nextClose: String?
) {
    companion object {
        fun current(): MarketStatus {
            val now = java.time.LocalTime.now()
            val weekday = java.time.LocalDate.now().dayOfWeek
            
            val isWeekday = weekday != java.time.DayOfWeek.SATURDAY && weekday != java.time.DayOfWeek.SUNDAY
            val isDuringHours = now >= java.time.LocalTime.of(9, 30) && now <= java.time.LocalTime.of(15, 0)
            
            return MarketStatus(
                isOpen = isWeekday && isDuringHours,
                nextOpen = if (!isDuringHours) "09:30:00" else null,
                nextClose = if (isDuringHours) "15:00:00" else null
            )
        }
    }
}
