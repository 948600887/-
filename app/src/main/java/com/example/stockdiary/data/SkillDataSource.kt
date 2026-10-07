package com.example.stockdiary.data

import com.example.stockdiary.common.Result
import com.example.stockdiary.network.EastmoneySkillApi
import com.example.stockdiary.utils.AppLogger
import com.example.stockdiary.utils.StockCodeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlinx.coroutines.CancellationException

/**
 * 妙想Skill数据源实现
 * 东方财富官方AI增强数据接口
 */
class SkillDataSource : StockDataSource {
    
    companion object {
        private const val TAG = "SkillDataSource"
    }
    
    override suspend fun getStockData(symbol: String): Result<StockData> = withContext(Dispatchers.IO) {
        try {
            when (val result = EastmoneySkillApi.queryStock("$symbol 最新行情")) {
                is Result.Success -> {
                    val stockData = parseStockData(result.data, symbol)
                    if (stockData != null) {
                        Result.success(stockData)
                    } else {
                        Result.failure("解析股票数据失败")
                    }
                }
                is Result.Error -> {
                    AppLogger.e("妙想Skill获取股票数据失败: ${result.message}", Exception(result.message), TAG)
                    Result.failure(Exception(result.message))
                }
                else -> Result.failure("未知状态")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("妙想Skill获取股票数据异常: ${e.message}", e, TAG)
            Result.failure(e)
        }
    }
    
    override suspend fun getStockDataBatch(symbols: List<String>): Result<List<StockData>> = withContext(Dispatchers.IO) {
        try {
            val results = mutableListOf<StockData>()
            val errors = mutableListOf<String>()
            
            for (symbol in symbols) {
                when (val result = EastmoneySkillApi.queryStock("$symbol 最新行情")) {
                    is Result.Success -> {
                        parseStockData(result.data, symbol)?.let { results.add(it) }
                    }
                    is Result.Error -> {
                        errors.add("$symbol: ${result.message}")
                    }
                    else -> {
                        errors.add("$symbol: 未知状态")
                    }
                }
            }
            
            if (results.isNotEmpty()) {
                Result.success(results)
            } else {
                Result.failure("批量获取失败: ${errors.joinToString("; ")}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("妙想Skill批量获取股票数据异常: ${e.message}", e, TAG)
            Result.failure(e)
        }
    }
    
    override fun getSourceName(): String = "妙想Skill"
    
    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            EastmoneySkillApi.queryStock("上证指数最新行情")
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }
    
    private fun parseStockData(jsonString: String, fallbackSymbol: String): StockData? {
        return try {
            val json = JSONObject(jsonString)
            val dataObj = if (json.has("data")) {
                json.getJSONObject("data")
            } else {
                json
            }
            
            // 提取股票代码
            val code = dataObj.optString("code", 
                dataObj.optString("symbol", fallbackSymbol))
            
            // 处理市场前缀
            val symbol = StockCodeManager.normalizeSymbol(code)
            
            val price = dataObj.optDouble("price", 0.0)
            if (!price.isFinite() || price <= 0.0) {
                // 妙想返回文本里没有 price 字段时 optDouble 默认给 0.0。这里不能把它当成一条成功数据，
                // 否则 0 价会一路写进三级缓存、把持仓市值算成 0。
                AppLogger.w("妙想Skill未返回有效价格: $symbol price=$price", TAG)
                return null
            }

            StockData(
                symbol = symbol,
                name = dataObj.optString("name", ""),
                price = price,
                open = dataObj.optDouble("open", 0.0),
                prevClose = dataObj.optDouble("previousClose", 
                    dataObj.optDouble("prevClose", 0.0)),
                high = dataObj.optDouble("high", 0.0),
                low = dataObj.optDouble("low", 0.0),
                volume = dataObj.optLong("volume", 0L),
                amount = dataObj.optDouble("amount", 0.0),
                bidPrice = dataObj.optDouble("bidPrice", 0.0),
                askPrice = dataObj.optDouble("askPrice", 0.0),
                timestamp = dataObj.optString("time", 
                    dataObj.optString("timestamp", ""))
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("解析妙想Skill股票数据失败: ${e.message}", e, TAG)
            null
        }
    }
}
