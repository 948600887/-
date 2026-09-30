package com.example.stockdiary.network

import com.example.stockdiary.common.Result

/**
 * 增强版基金 API（已合并到 FundAPI，保留此类作为兼容别名）。
 * 所有方法直接委托给 FundAPI。
 */
object EnhancedFundAPI {
    
    suspend fun getFundPrice(
        fundCode: String,
        forceRefresh: Boolean = false
    ): Result<FundAPI.FundInfo> = FundAPI.getFundInfoWithCache(fundCode, forceRefresh)
    
    suspend fun getMultipleFundPrices(
        fundCodes: List<String>,
        concurrency: Int = 5
    ): Map<String, Result<FundAPI.FundInfo>> = FundAPI.getMultipleFundPrices(fundCodes, concurrency)
    
    fun clearCache() = FundAPI.clearCache()
}