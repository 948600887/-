package com.example.stockdiary.network

import com.example.stockdiary.data.dividend.DividendData
import com.example.stockdiary.data.dividend.DividendDateValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.stockdiary.utils.AppLogger
import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import com.example.stockdiary.network.NetworkClient
import com.example.stockdiary.utils.StockCodeManager
import com.example.stockdiary.utils.getLatest
import kotlinx.coroutines.CancellationException

/**
 * 东方财富股票分红API服务
 * 使用 datacenter-web.eastmoney.com 接口获取股票分红数据
 */
class EastmoneyDividendApiService(private val context: Context) {
    
    internal var client: OkHttpClient = NetworkClient.fundClient

    /**
     * 进程级「主机不可达」退避窗口（毫秒时间戳）：datacenter-web.eastmoney.com 的 DNS
     * 解析失败是确定性故障，批量接口撞到之后回退成逐只重试，每只都会再等满一个超时
     * 然后同样失败——实测整轮同步因此被拖到 40~60 秒，结果全是失败。
     *
     * 与天天基金（fundf10，见 FundDividendApiService.hostUnreachableUntil）**分开记**：
     * 两个域名虽同属东财，但可能只有一个不通，共用窗口会误伤还能用的那个。
     */
    @Volatile
    internal var hostUnreachableUntil: Long = 0L

    private val hostUnreachableBackoffMs = 60_000L
    
    suspend fun getDividendHistory(stockCode: String): Result<List<DividendData>> {
        val code = StockCodeManager.extractDigits(stockCode)
        return fetchBySecurityCode(code, code)
    }
    
    /**
     * 获取分红历史（支持指定市场前缀）
     * @param stockCode 股票代码
     * @param marketPrefix 市场前缀，如 "SH" 或 "SZ"，用于ETF等需要带市场的代码
     */
    suspend fun getDividendHistory(stockCode: String, marketPrefix: String?): Result<List<DividendData>> {
        val code = StockCodeManager.extractDigits(stockCode)
        val securityCode = if (marketPrefix != null) {
            // 东财 SECURITY_CODE 的标准形态是「市场前缀+代码」（如 SH510050），**不带点**。
            // 带点的 SH.510050 在这个 filter 上可能一条都匹配不到 —— 返回空被当成"确实没分红"，
            // 分红被静默丢掉且不会被失败计数捕获。这里统一拼成无点形态。
            "$marketPrefix$code"
        } else {
            code
        }
        return fetchBySecurityCode(securityCode, code)
    }
    
    /**
     * 获取分红历史（直接指定 SECURITY_CODE 格式）
     * @param securityCode 完整的 SECURITY_CODE 值，如 "SH.510050", "SH510050", "510050.SH"
     */
    suspend fun getDividendHistoryBySecurityCode(securityCode: String): Result<List<DividendData>> {
        return fetchBySecurityCode(securityCode, securityCode)
    }
    
    private suspend fun fetchBySecurityCode(securityCode: String, refererCode: String): Result<List<DividendData>> {
        return withContext(Dispatchers.IO) {
            // 主机不可达退避：DNS 都解析不了时，逐只重试只会让每只都白等一个超时
            if (System.currentTimeMillis() < hostUnreachableUntil) {
                AppLogger.w("东财主机不可达退避中，跳过 $securityCode 本次抓取", "EastmoneyDividend")
                return@withContext Result.failure(Exception("东财主机不可达退避中，跳过"))
            }
            try {
                val allDividendList = mutableListOf<DividendData>()
                var currentPage = 1
                var totalPages = 1
                
                while (currentPage <= totalPages) {
                    val baseUrl = "https://datacenter-web.eastmoney.com/api/data/v1/get"
                    val filter = "(SECURITY_CODE=\"${securityCode}\")"
                    val url = "$baseUrl?reportName=RPT_SHAREBONUS_DET&columns=ALL&filter=${URLEncoder.encode(filter, "UTF-8")}&pageNumber=$currentPage&pageSize=50&sortColumns=EX_DIVIDEND_DATE&sortTypes=-1&source=WEB&client=WEB"
                    
                    AppLogger.apiUrl("GET", url, "EastmoneyDividend")
                    
                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                        .header("Accept", "application/json")
                        .header("Referer", "https://emweb.securities.eastmoney.com/PC_HSF10/Dividend/index?type=web&code=$refererCode")
                        .build()
                    
                    val response = client.newCall(request).execute()
                    val responseText = response.body?.string() ?: ""
                    response.close()
                    
                    if (responseText.isEmpty()) {
                        AppLogger.w("响应为空", "EastmoneyDividend")
                        break
                    }
                    
                    if (currentPage == 1) {
                        AppLogger.d("响应: ${responseText.take(500)}", "EastmoneyDividend")
                    }
                    
                    val jsonData = JSONObject(responseText)
                    val resultCode = jsonData.optInt("code", -1)
                    
                    if (resultCode != 0) {
                        AppLogger.w("API返回错误码: $resultCode, 消息: ${jsonData.optString("message")}", "EastmoneyDividend")
                        break
                    }
                    
                    val result = jsonData.optJSONObject("result")
                    if (result == null) {
                        AppLogger.w("无result字段", "EastmoneyDividend")
                        break
                    }
                    
                    totalPages = result.optInt("pages", 1)
                    val data = result.optJSONArray("data")
                    
                    if (data == null || data.length() == 0) {
                        AppLogger.d("无分红数据", "EastmoneyDividend")
                        break
                    }
                    
                    for (i in 0 until data.length()) {
                        val item = data.getJSONObject(i)
                        mapDividendItem(item, securityCode)?.let { allDividendList.add(it) }
                    }
                    
                    currentPage++
                }
                
                allDividendList.sortByDescending { it.date }
                
                AppLogger.d("解析到 ${allDividendList.size} 条分红数据", "EastmoneyDividend")
                Result.success(allDividendList)
                
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("获取分红数据失败: ${e.message}", e, "EastmoneyDividend")
                // DNS 解析失败是确定性故障：本轮后续标的必然同样失败，整段时间内短路跳过，
                // 把「标的数 × 一个超时」的纯等待压成一次
                if (isHostUnreachable(e)) {
                    hostUnreachableUntil = System.currentTimeMillis() + hostUnreachableBackoffMs
                    AppLogger.w("东财主机不可达（DNS 解析失败），本轮退避 60s", "EastmoneyDividend")
                }
                Result.failure(e)
            }
        }
    }
    
    /**
     * 把接口返回的一条记录映射成 [DividendData]。
     *
     * 抽出来是为了让批量查询与逐条查询共用同一套字段口径 ——
     * 两份映射逻辑迟早会因为一边改了另一边没改而给出不同结果，
     * 而这种事在分红金额上错一位就是一个数量级。
     *
     * @param ticker 回填给 [DividendData.ticker] 的代码。逐条查询时是**请求用的**代码，
     *              批量查询时必须是**接口返回的** SECURITY_CODE（可能带市场后缀），
     *              否则调用方没法把结果发回给对应的标的。
     */
    internal fun mapDividendItem(item: JSONObject, ticker: String): DividendData? {
        // 除息日过语义校验：trim + 取前 10 字符（接口给的是 "2023-12-19 00:00:00"）+
        // 真实日历日期。脏日期（2026-13-01 等）直接丢弃整条，不带进分红管线——
        // 它曾让消费端 month=13 炸 IndexOutOfBounds（见 DividendDateValidator 注释）。
        val exDate = DividendDateValidator.normalizeOrNull(item.optString("EX_DIVIDEND_DATE", ""))
            ?: return null
        val pretaxBonus = item.optDouble("PRETAX_BONUS_RMB", -1.0)
        val noticeDate = item.optString("PLAN_NOTICE_DATE", "")
        val assignProgress = item.optString("ASSIGN_PROGRESS", "")

        if (pretaxBonus <= 0 ||
            (!assignProgress.contains("实施") && !assignProgress.contains("完毕"))) {
            return null
        }
        // 东方财富 PRETAX_BONUS_RMB 实际为每十股分红（如 10派280.2423元），
        // 与应用内 dividendPerShare（每十股分红）单位一致，无需转换
        return DividendData(
            ticker = ticker,
            date = exDate,
            dividend = 0.0,
            dividendPerShare = pretaxBonus,
            reportDate = if (noticeDate.isNotEmpty()) noticeDate.take(10) else ""
        )
    }

    /**
     * 一次查多个标的的分红历史。
     *
     * 东财这个接口的 filter 支持 `SECURITY_CODE in ("a","b",…)`，所以不必一个代码一个请求：
     * 实测一次请求带 6 个代码（含 ETF 的三种代码格式变体）返回 57 条记录。
     * 一次全量抓取 59 个代码 + ETF 的格式重试，逐条要 100 多个请求，合并后 1~2 个。
     *
     * **返回值的 [DividendData.ticker] 是接口给的 SECURITY_CODE**（可能带 `.SH` 之类的后缀），
     * 调用方要靠它把结果发回给对应标的，不能假设等于请求时传的代码。
     *
     * @param securityCodes 完整 SECURITY_CODE 列表；调用方负责把同一标的的多种格式都放进来
     */
    suspend fun getDividendHistoryBySecurityCodes(securityCodes: List<String>): Result<List<DividendData>> {
        if (securityCodes.isEmpty()) return Result.success(emptyList())
        return withContext(Dispatchers.IO) {
            // 批量是本轮第一个打的东财请求：它若已因主机不可达退避，整批直接跳过
            if (System.currentTimeMillis() < hostUnreachableUntil) {
                AppLogger.w("东财主机不可达退避中，跳过批量抓取", "EastmoneyDividendBatch")
                return@withContext Result.failure(Exception("东财主机不可达退避中，跳过"))
            }
            try {
                val allDividendList = mutableListOf<DividendData>()
                var currentPage = 1
                var totalPages = 1

                while (currentPage <= totalPages) {
                    val quoted = securityCodes.joinToString(",") { "\"$it\"" }
                    val filter = "(SECURITY_CODE in ($quoted))"
                    val url = "https://datacenter-web.eastmoney.com/api/data/v1/get" +
                        "?reportName=RPT_SHAREBONUS_DET&columns=ALL" +
                        "&filter=${URLEncoder.encode(filter, "UTF-8")}" +
                        "&pageNumber=$currentPage&pageSize=500" +
                        "&sortColumns=EX_DIVIDEND_DATE&sortTypes=-1&source=WEB&client=WEB"

                    AppLogger.apiUrl("GET", url, "EastmoneyDividendBatch")

                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                        .header("Accept", "application/json")
                        .header("Referer", "https://emweb.securities.eastmoney.com/PC_HSF10/Dividend/index?type=web&code=${securityCodes.first()}")
                        .build()

                    val response = client.newCall(request).execute()
                    val responseText = response.body?.string() ?: ""
                    response.close()

                    if (responseText.isEmpty()) {
                        AppLogger.w("批量响应为空", "EastmoneyDividendBatch")
                        break
                    }

                    val jsonData = JSONObject(responseText)
                    if (jsonData.optInt("code", -1) != 0) {
                        AppLogger.w(
                            "批量API返回错误码: ${jsonData.optInt("code", -1)}, 消息: ${jsonData.optString("message")}",
                            "EastmoneyDividendBatch"
                        )
                        break
                    }

                    val result = jsonData.optJSONObject("result")
                    if (result == null) {
                        AppLogger.w("批量无result字段", "EastmoneyDividendBatch")
                        break
                    }

                    totalPages = result.optInt("pages", 1)
                    val data = result.optJSONArray("data")
                    if (data == null || data.length() == 0) {
                        AppLogger.d("批量无分红数据", "EastmoneyDividendBatch")
                        break
                    }

                    for (i in 0 until data.length()) {
                        // ticker 用**接口返回的** SECURITY_CODE，不是请求时传的 ——
                        // 同一个标的可能靠三种格式之一命中，只有用它才能对回去
                        val returnedCode = data.getJSONObject(i).optString("SECURITY_CODE", "")
                        if (returnedCode.isNotBlank()) {
                            mapDividendItem(data.getJSONObject(i), returnedCode)?.let { allDividendList.add(it) }
                        }
                    }

                    currentPage++
                }

                allDividendList.sortByDescending { it.date }
                AppLogger.d("批量解析到 ${allDividendList.size} 条分红数据", "EastmoneyDividendBatch")
                Result.success(allDividendList)

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("批量获取分红数据失败: ${e.message}", e, "EastmoneyDividendBatch")
                // 批量失败后调用方会回退成逐只重试。DNS 不可达时那批重试必然全部同样失败，
                // 先设上退避窗口，让逐只在入口就短路，整轮只付出一次超时代价
                if (isHostUnreachable(e)) {
                    hostUnreachableUntil = System.currentTimeMillis() + hostUnreachableBackoffMs
                    AppLogger.w("东财主机不可达（DNS 解析失败），本轮退避 60s，逐只回退将直接跳过", "EastmoneyDividendBatch")
                }
                Result.failure(e)
            }
        }
    }

    suspend fun getLatestDividend(stockCode: String): Result<List<DividendData>> {
        val result = getDividendHistory(stockCode)
        return if (result.isSuccess) {
            val allDividends = result.getOrNull() ?: emptyList()
            val latest = allDividends.getLatest()
            Result.success(latest)
        } else {
            result
        }
    }
}