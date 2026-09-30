package com.example.stockdiary.network

import com.example.stockdiary.common.Result
import com.example.stockdiary.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/**
 * 基金数据 API：从东方财富 pingzhongdata 接口获取基金净值数据。
 * 原 fundgz.1234567.com.cn 实时估值接口已于 2023 年因监管要求下线，
 * 现改用 fund.eastmoney.com/pingzhongdata/{code}.js 获取最新净值。
 */
object FundAPI {
    private const val TAG = "FundAPI"
    private const val BASE_URL = "https://fund.eastmoney.com/pingzhongdata"
    private const val CACHE_DURATION_MS = 5 * 60 * 1000L

    private val client = NetworkClient.fundClient
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    data class FundInfo(
        val name: String,
        val code: String,
        val price: Double,
        val netWorth: Double?,
        val changePercent: Double?,
        val date: String?,
        val isFromCache: Boolean = false
    )

    private data class CacheEntry(
        val data: FundInfo,
        val timestamp: Long
    )

    /** 获取基金净值（无缓存，始终请求最新） */
    suspend fun getFundPrice(fundCode: String): Double? {
        return getFundInfo(fundCode)?.price
    }

    /** 获取基金信息（无缓存，始终请求最新） */
    suspend fun getFundInfo(fundCode: String): FundInfo? {
        return fetchFundInfo(fundCode)
    }

    /** 获取基金信息（带缓存，forceRefresh=true 跳过缓存） */
    suspend fun getFundInfoWithCache(
        fundCode: String,
        forceRefresh: Boolean = false
    ): Result<FundInfo> = withContext(Dispatchers.IO) {
        try {
            val cachedEntry = cache[fundCode]
            val now = System.currentTimeMillis()

            if (!forceRefresh && cachedEntry != null &&
                (now - cachedEntry.timestamp) < CACHE_DURATION_MS
            ) {
                AppLogger.d("使用缓存数据: $fundCode", TAG)
                return@withContext Result.success(
                    cachedEntry.data.copy(isFromCache = true)
                )
            }

            val fundInfo = fetchFundInfo(fundCode)
            if (fundInfo != null) {
                cache[fundCode] = CacheEntry(fundInfo, now)
                AppLogger.d("获取基金数据成功: ${fundInfo.name}", TAG)
                Result.success(fundInfo)
            } else {
                Result.failure(Exception("基金数据获取失败"))
            }
        } catch (e: Exception) {
            AppLogger.e("获取基金数据异常: ${e.message}", e, TAG)
            Result.failure(e)
        }
    }

    /** 批量获取多个基金净值（带缓存和并发控制） */
    suspend fun getMultipleFundPrices(
        fundCodes: List<String>,
        concurrency: Int = 5
    ): Map<String, Result<FundInfo>> = withContext(Dispatchers.IO) {
        val results = ConcurrentHashMap<String, Result<FundInfo>>()

        fundCodes.chunked(concurrency).forEach { batch ->
            val deferredResults = batch.map { code ->
                async { code to getFundInfoWithCache(code) }
            }
            deferredResults.awaitAll().forEach { (code, result) ->
                results[code] = result
            }
        }

        results.toMap()
    }

    /** 清除缓存 */
    fun clearCache() {
        cache.clear()
        AppLogger.d("缓存已清空", TAG)
    }

    // ==================== 私有方法 ====================

    private suspend fun fetchFundInfo(fundCode: String): FundInfo? {
        return withContext(Dispatchers.IO) {
            try {
                val url = "$BASE_URL/${fundCode}.js?v=${System.currentTimeMillis()}"
                AppLogger.apiUrl("GET", url, TAG)

                val request = Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", "Mozilla/5.0")
                    .addHeader("Referer", "https://fund.eastmoney.com/")
                    .build()

                val response = client.newCall(request).execute()
                response.use {
                    if (!it.isSuccessful) {
                        AppLogger.e("基金请求失败: HTTP ${it.code}", tag = TAG)
                        return@withContext null
                    }

                    val body = it.body?.string() ?: return@withContext null
                    if (body.length < 100) {
                        AppLogger.e("基金响应数据过短", tag = TAG)
                        return@withContext null
                    }

                    val name = FundDataParser.extractJsVar(body, "fS_name") ?: ""
                    val code = FundDataParser.extractJsVar(body, "fS_code") ?: fundCode

                    if (name.isEmpty()) {
                        AppLogger.e("未找到基金名称", tag = TAG)
                        return@withContext null
                    }

                    val nawData = FundDataParser.parseNawData(body)

                    return@withContext FundInfo(
                        name = name,
                        code = code,
                        price = nawData.naw,
                        netWorth = nawData.naw,
                        changePercent = nawData.equityReturn,
                        date = nawData.date
                    )
                }
            } catch (e: Exception) {
                AppLogger.e("获取基金信息失败: ${e.message}", e, TAG)
                null
            }
        }
    }
}