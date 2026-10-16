package com.example.stockdiary.network

import com.example.stockdiary.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import com.example.stockdiary.common.Result as AppResult
import com.example.stockdiary.common.Timeouts
import com.example.stockdiary.utils.AppLogger
import kotlinx.coroutines.CancellationException

object EastmoneySkillApi {
    
    private const val TAG = "EastmoneySkillApi"
    private const val BASE_URL = "https://mkapi2.dgcfs.com/finskillshub/api/claw/"
    private val API_KEY = BuildConfig.EASTMONEY_SKILL_API_KEY
    
    private val client = NetworkClient.defaultClient.newBuilder()
        .connectTimeout(Timeouts.CONNECT_MS.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(Timeouts.READ_MS.toLong(), TimeUnit.MILLISECONDS)
        .writeTimeout(Timeouts.WRITE_MS.toLong(), TimeUnit.MILLISECONDS)
        .build()
    
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    
    private suspend fun executeApi(endpoint: String, query: String): AppResult<String> = withContext(Dispatchers.IO) {
        if (API_KEY.isEmpty()) {
            return@withContext AppResult.Error(Exception("未配置东方财富 Skill API Key"))
        }
        try {
            val json = JSONObject().put("toolQuery", query).toString()
            val request = Request.Builder()
                .url("${BASE_URL}$endpoint")
                .post(json.toRequestBody(jsonMediaType))
                .addHeader("Content-Type", "application/json")
                .addHeader("apikey", API_KEY)
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) AppResult.Success(response.body?.string() ?: "")
                else AppResult.Error(Exception("请求失败: ${response.code}"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("API请求失败: ${e.message}", e, TAG)
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
