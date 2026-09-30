package com.example.stockdiary.network

import com.example.stockdiary.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

object BinanceAPI {
    private const val BASE_URL = "https://api.binance.com/api/v3"
    private const val PRICE_URL = "$BASE_URL/ticker/price"
    private const val KLINE_URL = "$BASE_URL/klines"
    
    private val client = NetworkClient.binanceClient
    
    suspend fun getPrice(symbol: String): Double? {
        return withContext(Dispatchers.IO) {
            try {
                val tradingPair = formatSymbol(symbol)
                val url = "$PRICE_URL?symbol=$tradingPair"
                AppLogger.apiUrl("GET", url, "BinanceAPI")
                
                val request = Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", "Mozilla/5.0")
                    .build()
                
                val response = client.newCall(request).execute()
                response.use {
                    if (!it.isSuccessful) {
                        AppLogger.e("API响应错误: ${it.code}", tag = "BinanceAPI")
                        return@withContext null
                    }

                    val body = it.body?.string() ?: return@withContext null
                    AppLogger.d("响应长度: ${body.length}", "BinanceAPI")

                    val json = JSONObject(body)
                    return@withContext json.getString("price").toDoubleOrNull()
                }
            } catch (e: Exception) {
                AppLogger.e("获取价格失败: ${e.message}", e, "BinanceAPI")
                null
            }
        }
    }
    
    suspend fun get24hrStats(symbol: String): Binance24hrStats? {
        return withContext(Dispatchers.IO) {
            try {
                val tradingPair = formatSymbol(symbol)
                val url = "$BASE_URL/ticker/24hr?symbol=$tradingPair"
                
                val request = Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", "Mozilla/5.0")
                    .build()
                
                val response = client.newCall(request).execute()
                response.use {
                    if (!it.isSuccessful) return@withContext null

                    val body = it.body?.string() ?: return@withContext null
                    return@withContext parse24hrStats(body)
                }
            } catch (e: Exception) {
                AppLogger.e("获取24小时统计失败: ${e.message}", e, "BinanceAPI")
                null
            }
        }
    }
    
    private fun parse24hrStats(json: String): Binance24hrStats? {
        return try {
            val obj = JSONObject(json)
            Binance24hrStats(
                symbol = obj.getString("symbol"),
                lastPrice = obj.getString("lastPrice").toDoubleOrNull() ?: 0.0,
                priceChange = obj.getString("priceChange").toDoubleOrNull() ?: 0.0,
                priceChangePercent = obj.getString("priceChangePercent").toDoubleOrNull() ?: 0.0,
                highPrice = obj.getString("highPrice").toDoubleOrNull() ?: 0.0,
                lowPrice = obj.getString("lowPrice").toDoubleOrNull() ?: 0.0,
                volume = obj.getString("volume").toDoubleOrNull() ?: 0.0
            )
        } catch (e: Exception) {
            null
        }
    }
    
    private fun formatSymbol(symbol: String): String {
        val cleanSymbol = symbol.uppercase().replace(" ", "").replace("-", "")
        
        return when {
            cleanSymbol.endsWith("USDT") -> cleanSymbol
            cleanSymbol.endsWith("BTC") -> cleanSymbol
            cleanSymbol.endsWith("ETH") -> cleanSymbol
            cleanSymbol.endsWith("CNY") -> {
                val base = cleanSymbol.removeSuffix("CNY")
                "${base}USDT"
            }
            cleanSymbol.endsWith("USD") -> {
                val base = cleanSymbol.removeSuffix("USD")
                "${base}USDT"
            }
            else -> "${cleanSymbol}USDT"
        }
    }
    
    fun getSupportedSymbols(): List<String> {
        return listOf(
            "BTCUSDT", "ETHUSDT", "BNBUSDT",
            "ADAUSDT", "DOGEUSDT", "XRPUSDT",
            "DOTUSDT", "UNIUSDT", "LTCUSDT",
            "SOLUSDT", "MATICUSDT", "LINKUSDT"
        )
    }
}

data class Binance24hrStats(
    val symbol: String,
    val lastPrice: Double,
    val priceChange: Double,
    val priceChangePercent: Double,
    val highPrice: Double,
    val lowPrice: Double,
    val volume: Double
)
