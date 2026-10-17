package com.example.stockdiary.network

import com.example.stockdiary.common.Result
import com.example.stockdiary.utils.AppLogger
import com.example.stockdiary.utils.StockCodeManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * 股票行情源接口。
 *
 * 实现：[SinaStockAPI]（新浪财经）、[TencentStockAPI]（腾讯财经）。
 * 由 `data/price/StockDataManager` 的多源容错链按用户偏好排序调用。
 */
interface StockAPI {
    suspend fun getStockData(stockCode: String): Result<SinaStockData>
}

/**
 * 腾讯财经API实现。
 *
 * 注意：本对象从已删除的 [StockAPIManager] 迁到这里——它是
 * `data/price/StockDataManager` 多源容错链的「腾讯」数据源，
 * 绝不可随入口归一被连带删除（那会让容错链少一条腿，
 * 且测试全绿也看不出来）。
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
