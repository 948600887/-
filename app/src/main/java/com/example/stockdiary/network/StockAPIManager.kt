package com.example.stockdiary.network

import com.example.stockdiary.utils.AppLogger
import com.example.stockdiary.common.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import com.example.stockdiary.utils.StockCodeManager
import kotlinx.coroutines.CancellationException

/**
 * 股票API管理器
 * 实现主备API切换、故障检测和缓存机制
 */
class StockAPIManager {
    private val primaryAPI = TencentStockAPI
    private val backupAPI = SinaStockAPI
    private val currentAPIRef = AtomicReference<StockAPI>(primaryAPI)
    private val failCount = AtomicInteger(0)
    private val maxFailures = 3
    @Volatile
    private var lastSwitchTime = 0L
    private val switchCooldown = 60_000L
    
    private val stockDataCache = ConcurrentHashMap<String, Pair<Long, SinaStockData>>()
    private val CACHE_DURATION = 5.minutes.inWholeMilliseconds
    
    private val switchLock = Any()
    
    /**
     * 获取股票数据
     * @param stockCode 股票代码
     * @return Result<SinaStockData> 股票数据或错误信息
     */
    suspend fun getStockData(stockCode: String): Result<SinaStockData> {
        val cachedData = getCachedData(stockCode)
        if (cachedData != null) {
            AppLogger.d("使用缓存数据: ${cachedData.name}", "StockAPIManager")
            return Result.success(cachedData)
        }
        
        return withContext(Dispatchers.IO) {
            try {
                val currentAPI = currentAPIRef.get()
                val result = currentAPI.getStockData(stockCode)
                if (result.isSuccess) {
                    failCount.set(0)
                    result.getOrNull()?.let { cacheData(stockCode, it) }
                    AppLogger.d("获取API数据成功并缓存: ${result.getOrNull()?.name}", "StockAPIManager")
                    return@withContext result
                } else {
                    val currentFail = failCount.incrementAndGet()
                    AppLogger.e("API调用失败 ($currentFail/$maxFailures): ${result.exceptionOrNull()?.message}", tag = "StockAPIManager")
                    
                    if (currentFail >= maxFailures) {
                        switchAPI()
                    }
                    
                    val apiAfterSwitch = currentAPIRef.get()
                    if (apiAfterSwitch != primaryAPI) {
                        val backupResult = apiAfterSwitch.getStockData(stockCode)
                        if (backupResult.isSuccess) {
                            backupResult.getOrNull()?.let { cacheData(stockCode, it) }
                            AppLogger.d("备用API获取数据成功并缓存: ${backupResult.getOrNull()?.name}", "StockAPIManager")
                        }
                        return@withContext backupResult
                    } else {
                        return@withContext result
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val currentFail = failCount.incrementAndGet()
                AppLogger.e("API调用异常 ($currentFail/$maxFailures): ${e.message}", e, "StockAPIManager")
                
                if (currentFail >= maxFailures) {
                    switchAPI()
                }
                
                val apiAfterSwitch = currentAPIRef.get()
                if (apiAfterSwitch != primaryAPI) {
                    try {
                        val backupResult = apiAfterSwitch.getStockData(stockCode)
                        if (backupResult.isSuccess) {
                            backupResult.getOrNull()?.let { cacheData(stockCode, it) }
                            AppLogger.d("备用API获取数据成功并缓存: ${backupResult.getOrNull()?.name}", "StockAPIManager")
                        }
                        return@withContext backupResult
                    } catch (backupException: Exception) {
                        return@withContext Result.failure(backupException)
                    }
                } else {
                    return@withContext Result.failure(e)
                }
            }
        }
    }
    
    /**
     * 执行API切换
     */
    private fun switchAPI() {
        synchronized(switchLock) {
            val now = System.currentTimeMillis()
            if (now - lastSwitchTime < switchCooldown) {
                return
            }
            
            val current = currentAPIRef.get()
            if (current == primaryAPI) {
                AppLogger.d("切换到备用API（新浪财经）", "StockAPIManager")
                currentAPIRef.set(backupAPI)
            } else {
                AppLogger.d("切换回主API（腾讯财经）", "StockAPIManager")
                currentAPIRef.set(primaryAPI)
            }
            
            failCount.set(0)
            lastSwitchTime = now
        }
    }
    
    /**
     * 定期健康检查，尝试恢复主API
     */
    suspend fun healthCheck() {
        if (currentAPIRef.get() == backupAPI) {
            withContext(Dispatchers.IO) {
                try {
                    val testResult = primaryAPI.getStockData("sh600000")
                    if (testResult.isSuccess) {
                        synchronized(switchLock) {
                            AppLogger.d("主API（腾讯财经）已恢复，切换回主API", "StockAPIManager")
                            currentAPIRef.set(primaryAPI)
                            failCount.set(0)
                            lastSwitchTime = System.currentTimeMillis()
                        }
                    } else {
                        AppLogger.d("主API（腾讯财经）仍未恢复", "StockAPIManager")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.d("主API（腾讯财经）仍未恢复: ${e.message}", "StockAPIManager")
                }
            }
        } else {
            AppLogger.d("当前使用主API（腾讯财经），跳过健康检查", "StockAPIManager")
        }
    }
    
    /**
     * 从缓存获取数据
     */
    private fun getCachedData(stockCode: String): SinaStockData? {
        val cached = stockDataCache[stockCode]
        if (cached != null) {
            val (timestamp, data) = cached
            if (System.currentTimeMillis() - timestamp < CACHE_DURATION) {
                return data
            } else {
                // 缓存过期，移除
                stockDataCache.remove(stockCode)
            }
        }
        return null
    }
    
    /**
     * 缓存数据
     */
    private fun cacheData(stockCode: String, data: SinaStockData) {
        stockDataCache[stockCode] = Pair(System.currentTimeMillis(), data)
        // 限制缓存大小
        if (stockDataCache.size > 100) {
            // 移除最旧的缓存
            val oldestKey = stockDataCache.minByOrNull { it.value.first }?.key
            oldestKey?.let { stockDataCache.remove(it) }
        }
    }
    
    /**
     * 清除缓存
     */
    fun clearCache() {
        stockDataCache.clear()
    }
    
    companion object {
        // 单例模式
        val instance by lazy { StockAPIManager() }
    }
}

/**
 * 股票API接口
 */
interface StockAPI {
    suspend fun getStockData(stockCode: String): Result<SinaStockData>
}

/**
 * 腾讯财经API实现
 */
object TencentStockAPI : StockAPI {
    private const val BASE_URL = "https://qt.gtimg.cn/q="
    
    private val client = NetworkClient.tencentClient.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36")
                .addHeader("Referer", "https://finance.qq.com")
                .build()
            chain.proceed(request)
        }
        .build()
    
    override suspend fun getStockData(stockCode: String): Result<SinaStockData> {
        return withContext(Dispatchers.IO) {
            try {
                val formattedCode = formatStockCode(stockCode)
                val url = "${BASE_URL}$formattedCode"
                AppLogger.apiUrl("GET", url, "TencentStockAPI")
                
                val request = Request.Builder()
                    .url(url)
                    .build()
                
                val response = client.newCall(request).execute()
                response.use {
                    if (!it.isSuccessful) {
                        val errorMsg = "API响应错误: ${it.code} ${it.message}"
                        AppLogger.e(errorMsg, tag = "TencentStockAPI")
                        return@withContext Result.failure(IOException(errorMsg))
                    }

                    val responseBody = it.body
                    if (responseBody == null) {
                        AppLogger.e("API返回空数据", tag = "TencentStockAPI")
                        return@withContext Result.failure(IOException("API返回空数据"))
                    }

                    val text = responseBody.use { body -> body.string() }
                    AppLogger.d("响应长度: ${text.length}", "TencentStockAPI")

                    val suspended = booleanArrayOf(false)
                    val stockData = parseTencentData(text, formattedCode, suspended)
                    if (stockData != null) {
                        AppLogger.d("成功解析股票: ${stockData.name} (${stockData.code}), 价格: ${stockData.price}", "TencentStockAPI")
                        return@withContext Result.success(stockData)
                    } else if (suspended[0]) {
                        // 响应里有这只票但报价无效＝停牌。这不是数据源故障，
                        // 不能走「未获取到股票数据」那条会被累计失败次数的路径。
                        return@withContext Result.failure(QuoteSuspendedException(formattedCode))
                    } else {
                        AppLogger.e("未获取到股票数据，请检查股票代码", tag = "TencentStockAPI")
                        return@withContext Result.failure(IOException("未获取到股票数据，请检查股票代码"))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("异常: ${e.message}", e, "TencentStockAPI")
                Result.failure(e)
            }
        }
    }
    
    /**
     * 格式化股票代码为腾讯格式
     */
    private fun formatStockCode(code: String): String {
        return StockCodeManager.normalizeSymbol(code)
    }
    
    /**
     * 解析腾讯财经返回的数据
     * 腾讯API字段说明（以~分隔）：
     * 0: 未知 1: 股票名称 2: 股票代码 3: 当前价格 4: 昨收 5: 今开 6: 成交量(手)
     * 7: 外盘 8: 内盘 9: 买一价 10: 买一量 11: 买二价 12: 买二量 ... 19: 卖一价 20: 卖一量...
     * 29: 最近逐笔成交 30: 时间 31: 涨跌 32: 涨跌% 33: 最高价 34: 最低价 35: 价格/成交量(手)/成交额
     * 36: 成交量(手) 37: 成交额(万) 38: 换手率 39: 市盈率 40: 未知 41: 最高价 42: 最低价
     * 43: 振幅 44: 流通市值 45: 总市值 46: 市净率 47: 涨停价 48: 跌停价
     */
    private fun parseTencentData(
        responseText: String,
        @Suppress("UNUSED_PARAMETER") stockCode: String,
        suspended: BooleanArray = booleanArrayOf(false)
    ): SinaStockData? {
        try {
            // 腾讯API返回格式: v_sh600000="1~浦发银行~600000~9.99~10.00~9.98~..."
            val pattern = Regex("v_([a-zA-Z0-9]+)=\"([^\"]*)\"")
            val matchResult = pattern.find(responseText)
            
            if (matchResult != null) {
                val code = matchResult.groupValues[1]
                val dataString = matchResult.groupValues[2]
                val fields = dataString.split("~")
                
                AppLogger.d("股票代码: $code, 字段数量: ${fields.size}", "TencentStockAPI")
                
                // 确保至少有基本的字段
                if (fields.size >= 11) {
                    val name = fields[1]
                    val price = fields[3].toDoubleOrNull() ?: 0.0
                    val open = fields[5].toDoubleOrNull() ?: 0.0
                    val prevClose = fields[4].toDoubleOrNull() ?: 0.0
                    val high = fields[33].toDoubleOrNull() ?: fields[5].toDoubleOrNull() ?: 0.0
                    val low = fields[34].toDoubleOrNull() ?: fields[6].toDoubleOrNull() ?: 0.0
                    val volume = fields[6].toLongOrNull() ?: 0L
                    val amount = fields[37].toDoubleOrNull() ?: fields[8].toDoubleOrNull() ?: 0.0
                    val date = fields.getOrNull(30) ?: ""
                    val time = fields.getOrNull(30) ?: ""
                    
                    // 新增字段：市盈率、总市值、流通市值、换手率、市净率
                    val peRatio = fields.getOrNull(39)?.toDoubleOrNull() ?: 0.0  // 市盈率
                    val marketCap = fields.getOrNull(45)?.toDoubleOrNull() ?: 0.0  // 总市值(万)
                    val circulatingCap = fields.getOrNull(44)?.toDoubleOrNull() ?: 0.0  // 流通市值(万)
                    val turnoverRate = fields.getOrNull(38)?.toDoubleOrNull() ?: 0.0  // 换手率
                    val pbRatio = fields.getOrNull(46)?.toDoubleOrNull() ?: 0.0  // 市净率
                    val amplitude = fields.getOrNull(43)?.toDoubleOrNull() ?: 0.0  // 振幅
                    
                    val change = price - prevClose
                    val changePercent = if (prevClose > 0) (change / prevClose * 100) else 0.0

                    // 停牌时腾讯返回的现价是 0，昨收有效。同 SinaStockAPI 的处理：
                    // 拿 0 当现价会让市值归零并按 -100% 展示，这里退到昨收、涨跌记 0。
                    val effectivePrice = if (price > 0) price else prevClose
                    if (effectivePrice <= 0) {
                        suspended[0] = true
                        AppLogger.w("无有效报价（疑似停牌）: $code（现价=$price, 昨收=$prevClose）", "TencentStockAPI")
                        return null
                    }

                    return SinaStockData(
                        code = code,
                        name = name,
                        open = open,
                        prevClose = prevClose,
                        price = effectivePrice,
                        high = high,
                        low = low,
                        volume = volume,
                        amount = amount,
                        date = date,
                        time = time,
                        change = change,
                        changePercent = changePercent,
                        peRatio = peRatio,
                        marketCap = marketCap,
                        circulatingCap = circulatingCap,
                        turnoverRate = turnoverRate,
                        pbRatio = pbRatio,
                        amplitude = amplitude
                    )
                } else {
                    AppLogger.d("字段数量不足: ${fields.size}", "TencentStockAPI")
                }
            } else {
                AppLogger.d("未匹配到股票数据", "TencentStockAPI")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("解析错误: ${e.message}", e, "TencentStockAPI")
        }
        return null
    }
}