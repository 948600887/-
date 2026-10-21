package com.example.stockdiary.network

import com.example.stockdiary.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import com.example.stockdiary.common.Result as AppResult
import com.example.stockdiary.common.Timeouts
import com.example.stockdiary.utils.AppLogger
import kotlinx.coroutines.CancellationException

object EastmoneySkillApi {
    
    private const val TAG = "EastmoneySkillApi"
    private const val BASE_URL = "https://mkapi2.dgcfs.com/finskillshub/api/claw/"
    internal var apiKey: String = BuildConfig.EASTMONEY_SKILL_API_KEY

    internal var client: OkHttpClient = NetworkClient.defaultClient.newBuilder()
        .connectTimeout(Timeouts.CONNECT_MS.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(Timeouts.READ_MS.toLong(), TimeUnit.MILLISECONDS)
        .writeTimeout(Timeouts.WRITE_MS.toLong(), TimeUnit.MILLISECONDS)
        .build()

    /**
     * 进程级主机退避窗口（毫秒时间戳）。
     *
     * 弱网下每只股票各自走 StockDataManager → [NetworkRetryHelper] 的 3 次重试，
     * 请求会短时间堆叠成雪崩；一旦命中限流(403/429)/超时/连接失败，在此时间戳之前，
     * [SkillDataSource.getStockData] 直接短路返回、不再向该主机发请求、也不再触发重试——
     * 既避免重复猛打同一个被限流/超时主机，也避免连续 3 次失败后整源被禁用 30 分钟、
     * 把流量一股脑甩给 Sina/Tencent 放大另一源的请求。退避时长与 FundDividendApiService
     * 同量级（60s）。这是防御性的：不改 Skill 默认首选源优先级，也不动 StockDataManager
     * 既有的「3 次失败禁 30 分钟」逻辑。
     */
    @Volatile
    internal var skillHostBlockedUntil: Long = 0L

    /** 主机退避时长：60s，覆盖绝大多数限流/超时窗口，与 FundDividendApiService.RATE_LIMIT_BACKOFF_MS 同量级 */
    private const val SKILL_BACKOFF_MS = 60_000L

    /** 退避窗口内返回 true —— 调用方应短路、不再向该主机发请求 */
    fun isSkillHostBlocked(): Boolean = System.currentTimeMillis() < skillHostBlockedUntil

    /**
     * 是否已配置 API Key。
     *
     * 供 [com.example.stockdiary.data.price.StockDataManager] 在组候选源时判断：未配置则把妙想
     * 移出候选链，避免每次刷新都白试一遍、还计入源级禁用计数（[executeApi] 对空 Key 直接返回
     * 「未配置东方财富 Skill API Key」）。用户**显式**选中妙想时不静默移除，由调用方另行处理。
     */
    fun isApiKeyConfigured(): Boolean = apiKey.isNotEmpty()

    /** 标记主机进入退避：置退避窗口结束时间戳 */
    private fun markSkillHostBlocked() {
        skillHostBlockedUntil = System.currentTimeMillis() + SKILL_BACKOFF_MS
    }
    
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    
    private suspend fun executeApi(endpoint: String, query: String): AppResult<String> = withContext(Dispatchers.IO) {
        if (apiKey.isEmpty()) {
            return@withContext AppResult.Error(Exception("未配置东方财富 Skill API Key"))
        }
        try {
            val json = JSONObject().put("toolQuery", query).toString()
            val request = Request.Builder()
                .url("${BASE_URL}$endpoint")
                .post(json.toRequestBody(jsonMediaType))
                .addHeader("Content-Type", "application/json")
                .addHeader("apikey", apiKey)
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    AppResult.Success(response.body?.string() ?: "")
                } else {
                    // 限流（403/429）：标记主机退避并直接失败，绝不再对同一个被限流主机补刀重试
                    if (response.code == 403 || response.code == 429) {
                        AppLogger.w("妙想Skill限流（${response.code}），本周期进程级退避", TAG)
                        markSkillHostBlocked()
                    }
                    AppResult.Error(Exception("请求失败: ${response.code}"))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("API请求失败: ${e.message}", e, TAG)
            // 超时/连接类失败（弱网典型）：标记主机退避，避免 StockDataManager 的 3 次重试
            // 让每个标的都去猛打同一个已经超时/不可达的主机，请求量瞬间堆叠成雪崩。
            if (e is ConnectException || e is SocketTimeoutException || e is UnknownHostException || e is IOException) {
                AppLogger.w("妙想Skill网络异常（${e.javaClass.simpleName}），本周期进程级退避", TAG)
                markSkillHostBlocked()
            }
            AppResult.Error(e)
        }
    }
    
    suspend fun queryStock(query: String): AppResult<String> = executeApi("query", query)
    
    suspend fun searchNews(keyword: String): AppResult<String> = executeApi("news-search", keyword)
    
    suspend fun stockScreen(condition: String): AppResult<String> = executeApi("stock-screen", condition)
    
    suspend fun manageWatchlist(action: String, stockCode: String): AppResult<String> =
        executeApi("watchlist", "$action $stockCode")
    
    suspend fun managePortfolio(action: String, details: String): AppResult<String> =
        executeApi("portfolio", "$action $details")
}
