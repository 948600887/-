package com.example.stockdiary.network

import com.example.stockdiary.common.Result
import com.example.stockdiary.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException

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
        val millionCopiesIncome: Double? = null,
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

            // forceRefresh 也要分情况，不能一律绕过缓存。
            //
            // 场外基金净值**每个交易日只公布一次**（收盘后），缓存里已经是今天
            // 公布的净值时，重新下载那份 0.5~2MB 的完整历史净值文件也拿不到
            // 更新的数字。而这份文件恰恰是刷新时峰值内存的主要来源：
            // 106 个标的 × 每份最多 2MB，再乘上同步并发数，实测能把进程压到
            // 被系统回收（用户看到的就是「点刷新黑屏然后闪退」，零崩溃报告）。
            //
            // 所以「缓存里已是今日净值」时，即使调用方要求强制刷新也走缓存 ——
            // 这不是偷懒，是这份数据客观上没有更新的可能。第一个同步日全量下载，
            // 同日后续刷新几乎零下载。
            if (cachedEntry != null &&
                (now - cachedEntry.timestamp) < CACHE_DURATION_MS &&
                (!forceRefresh || isTodaysNav(
                    navDate = cachedEntry.data.date,
                    today = java.time.LocalDate.now().toString()
                ))
            ) {
                AppLogger.d(
                    if (forceRefresh) "已是今日净值，复用缓存: $fundCode" else "使用缓存数据: $fundCode",
                    TAG
                )
                return@withContext Result.success(
                    cachedEntry.data.copy(isFromCache = true)
                )
            }

            val fundInfo = fetchFundInfo(fundCode)
            if (fundInfo != null) {
                // 用写入时刻而不是请求开始时刻：天天基金的响应常在 0.5~2MB，
                // 弱网下耗时可观，拿请求开始时刻会把 5 分钟 TTL 白白吃掉一段。
                cache[fundCode] = CacheEntry(fundInfo, System.currentTimeMillis())
                AppLogger.d("获取基金数据成功: ${fundInfo.name}", TAG)
                Result.success(fundInfo)
            } else {
                // 刷新失败时宁可给上一轮的旧净值，也不要返回失败：
                // 一次网络抖动就让持仓显示"未找到基金数据"，还会被计成同步失败。
                val stale = cachedEntry?.data
                if (stale != null) {
                    AppLogger.w("基金数据刷新失败，回退旧缓存: $fundCode", TAG)
                    return@withContext Result.success(stale.copy(isFromCache = true))
                }
                Result.failure(Exception("基金数据获取失败"))
            }
        } catch (e: CancellationException) {
            throw e
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

                    // 净值解析失败时 parseNawData 会返回默认值 0.0。0 净值会让场外基金市值归零，
                    // 且因为没有明显报错用户很难察觉，所以这里宁可不返回这条数据（触发下一数据源）。
                    if (nawData.naw <= 0.0) {
                        AppLogger.w("基金净值无效，丢弃该条: $code naw=${nawData.naw}", tag = TAG)
                        return@withContext null
                    }

                    return@withContext FundInfo(
                        name = name,
                        code = code,
                        price = nawData.naw,
                        netWorth = nawData.naw,
                        changePercent = nawData.equityReturn,
                        date = nawData.date,
                        millionCopiesIncome = nawData.millionCopiesIncome
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("获取基金信息失败: ${e.message}", e, TAG)
                null
            }
        }
    }
}

/**
 * 这条缓存里的净值是不是**今天公布**的。
 *
 * 抽成纯函数是为了能单测：判断错了两个方向都有代价 ——
 * 该认而不认，每次刷新都重下 0.5~2MB 的历史文件，内存压力直接把进程压死；
 * 不该认而认，用户在同一天内拿到旧净值（但场外基金当日只公布一次，
 * 所以「已是今日净值」时客观上没有更新的可能，这个方向的代价为零）。
 *
 * 净值日期来自 [FundAPI.FundInfo.date]，格式 `yyyy-MM-dd`；
 * 为空或格式不对时一律返回 false（宁可重下，也不要把脏数据当今日净值）。
 *
 * @param navDate 缓存净值对应的公布日期
 * @param today 今天，做成入参而不是内部读时钟，才能单测
 */
internal fun isTodaysNav(navDate: String?, today: String): Boolean {
    if (navDate.isNullOrBlank()) return false
    return navDate.trim() == today
}