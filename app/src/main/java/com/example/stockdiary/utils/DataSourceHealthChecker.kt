package com.example.stockdiary.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import com.example.stockdiary.network.NetworkClient
import kotlinx.coroutines.CancellationException

data class DataSourceHealth(
    val name: String,
    val url: String,
    val isAvailable: Boolean,
    val latency: Long,
    val lastCheck: Long,
    val errorMessage: String? = null
)

object DataSourceHealthChecker {
    
    private val client = NetworkClient.defaultClient
    
    suspend fun checkAllHealth(): List<DataSourceHealth> = withContext(Dispatchers.IO) {
        listOf(
            checkSinaStock(),
            checkFundAPI(),
            checkBinanceAPI()
        )
    }
    
    suspend fun checkSinaStock(): DataSourceHealth = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        try {
            val request = Request.Builder()
                .url("https://hq.sinajs.cn/list=sh600519")
                .addHeader("User-Agent", "Mozilla/5.0")
                .addHeader("Referer", "https://finance.sina.com.cn")
                .build()
            
            // 必须 use{}：ResponseBody 不 close 的话连接不会归还连接池，
            // 每次打开设置页都会泄漏 3 条，直到 keepAlive 60s 超时才释放
            client.newCall(request).execute().use { response ->
                val latency = System.currentTimeMillis() - startTime

                DataSourceHealth(
                    name = "新浪财经",
                    url = "hq.sinajs.cn",
                    isAvailable = response.isSuccessful,
                    latency = latency,
                    lastCheck = System.currentTimeMillis(),
                    errorMessage = if (!response.isSuccessful) "HTTP ${response.code}" else null
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DataSourceHealth(
                name = "新浪财经",
                url = "hq.sinajs.cn",
                isAvailable = false,
                latency = System.currentTimeMillis() - startTime,
                lastCheck = System.currentTimeMillis(),
                errorMessage = e.message
            )
        }
    }
    
    suspend fun checkFundAPI(): DataSourceHealth = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        try {
            val request = Request.Builder()
                .url("https://fund.eastmoney.com/pingzhongdata/110022.js?v=${System.currentTimeMillis()}")
                .addHeader("User-Agent", "Mozilla/5.0")
                .addHeader("Referer", "https://fund.eastmoney.com/")
                .build()
            
            client.newCall(request).execute().use { response ->
                val latency = System.currentTimeMillis() - startTime

                DataSourceHealth(
                    name = "天天基金",
                    url = "fund.eastmoney.com",
                    isAvailable = response.isSuccessful,
                    latency = latency,
                    lastCheck = System.currentTimeMillis(),
                    errorMessage = if (!response.isSuccessful) "HTTP ${response.code}" else null
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DataSourceHealth(
                name = "天天基金",
                url = "fund.eastmoney.com",
                isAvailable = false,
                latency = System.currentTimeMillis() - startTime,
                lastCheck = System.currentTimeMillis(),
                errorMessage = e.message
            )
        }
    }
    
    suspend fun checkBinanceAPI(): DataSourceHealth = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        try {
            val request = Request.Builder()
                .url("https://api.binance.com/api/v3/ping")
                .addHeader("User-Agent", "Mozilla/5.0")
                .build()
            
            client.newCall(request).execute().use { response ->
                val latency = System.currentTimeMillis() - startTime

                DataSourceHealth(
                    name = "Binance",
                    url = "api.binance.com",
                    isAvailable = response.isSuccessful,
                    latency = latency,
                    lastCheck = System.currentTimeMillis(),
                    errorMessage = if (!response.isSuccessful) "HTTP ${response.code}" else null
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DataSourceHealth(
                name = "Binance",
                url = "api.binance.com",
                isAvailable = false,
                latency = System.currentTimeMillis() - startTime,
                lastCheck = System.currentTimeMillis(),
                errorMessage = e.message
            )
        }
    }
}
