package com.example.stockdiary.network.model

import com.example.stockdiary.data.contract.SyncStrategy
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
            // 交易时段判断统一收敛到 SyncStrategy.isInTradingHours()（含工作日 + 9:30-11:30/13:00-15:00，
            // 且正确排除午间休市 11:30-13:00）。删除此处重复的本地副本，避免两处口径漂移。
            val isDuringHours = SyncStrategy.isInTradingHours()
            
            return MarketStatus(
                isOpen = isDuringHours,
                nextOpen = if (!isDuringHours) "09:30:00" else null,
                nextClose = if (isDuringHours) "15:00:00" else null
            )
        }
    }
}
