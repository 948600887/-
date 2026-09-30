package com.example.stockdiary.network.base

import com.example.stockdiary.common.Timeouts
import com.example.stockdiary.network.NetworkClient
import com.example.stockdiary.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

abstract class BaseAPI<T>(
    protected val tag: String = "BaseAPI"
) {
    protected val client: OkHttpClient by lazy {
        NetworkClient.defaultClient.newBuilder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .apply { getDefaultHeaders().forEach { addHeader(it.key, it.value) } }
                    .build()
                chain.proceed(request)
            }
            .build()
    }

    protected abstract fun getDefaultHeaders(): Map<String, String>

    protected suspend fun fetch(url: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .build()
            
            val startTime = System.currentTimeMillis()
            val response = client.newCall(request).execute()
            response.use {
                val duration = System.currentTimeMillis() - startTime

                AppLogger.api("GET $url", it.message, duration, tag)

                if (it.isSuccessful) {
                    return@withContext Result.success(it.body?.string() ?: "")
                } else {
                    AppLogger.e("Request failed: ${it.code}", null, tag)
                    return@withContext Result.failure(Exception("HTTP ${it.code}"))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("Request error: ${e.message}", e, tag)
            Result.failure(e)
        }
    }

    protected fun parseJson(json: String, key: String): String? {
        val patterns = listOf(
            "\"$key\"\\s*:\\s*\"([^\"]+)\"" to 1,
            "\"$key\"\\s*:\\s*([^,}]+)" to 1
        )
        patterns.forEach { (pattern, group) ->
            Regex(pattern).find(json)?.groupValues?.getOrNull(group)?.let {
                return it.trim().removeSurrounding("\"")
            }
        }
        return null
    }

    protected fun parseJsonInt(json: String, key: String): Int? = parseJson(json, key)?.toIntOrNull()
    protected fun parseJsonDouble(json: String, key: String): Double? = parseJson(json, key)?.toDoubleOrNull()
    protected fun parseJsonBoolean(json: String, key: String): Boolean = parseJson(json, key)?.toBoolean() ?: false
}

interface PriceData {
    val code: String
    val name: String
    val price: Double
    val change: Double?
    val changePercent: Double?
    val timestamp: Long
}

interface MarketData {
    val high: Double?
    val low: Double?
    val open: Double?
    val volume: Long?
    val amount: Double?
}
