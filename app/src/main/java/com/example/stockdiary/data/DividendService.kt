package com.example.stockdiary.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import org.jsoup.Jsoup
import java.io.IOException
import com.example.stockdiary.utils.AppLogger
import kotlin.math.min
import com.example.stockdiary.network.NetworkClient
import com.example.stockdiary.utils.StockCodeManager
import com.example.stockdiary.common.Result as AppResult
import kotlinx.coroutines.CancellationException

/**
 * 分红数据获取服务
 */
class DividendService {
    private val client = NetworkClient.defaultClient
    
    companion object {
        private const val BASE_URL = "https://vip.stock.finance.sina.com.cn/corp/go.php/vISSUE_ShareBonus/stockid/"
        
        // 请求头
        private val HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "Referer" to "https://finance.sina.com.cn",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
            "Accept-Encoding" to "gzip, deflate, br",
            "Connection" to "keep-alive"
        )
    }
    
    /**
     * 获取股票分红数据
     * @param stockCode 股票代码，如 "600519"
     * @param market 市场类型："sh" 或 "sz"
     */
    suspend fun fetchDividendData(stockCode: String, market: String): AppResult<List<DividendRecord>> {
        return withContext(Dispatchers.IO) {
            try {
                // 1. 构建请求URL
                val url = "$BASE_URL$stockCode.phtml"
                AppLogger.apiUrl("GET", url, "DividendService")
                
                // 2. 构建请求
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .apply {
                        HEADERS.forEach { (key, value) ->
                            addHeader(key, value)
                        }
                    }
                    .build()
                
                // 3. 执行请求
                val response = client.newCall(request).execute()
                response.use {
                    if (!it.isSuccessful) {
                        return@withContext AppResult.Error(
                            IOException("HTTP ${it.code}: ${it.message}")
                        )
                    }

                    // 4. 解析HTML响应
                    //
                    // **必须显式按 GBK 解码**。新浪这个页面的响应头是
                    // `Content-Type: text/html`，不带 charset，OkHttp 的 body.string()
                    // 会退回 UTF-8 —— 而页面实体是 GBK，中文全部变成乱码，
                    // 于是 parseDividendHtml 里 `tableText.contains("分红")` 永远不命中，
                    // 表现就是日志里刷屏的「找到 0 个表格」：**整条兜底链路静默失效**，
                    // 既拿不到数据，又每次白跑一个请求。
                    //
                    // 响应头真带了 charset 时以它为准，没带才用 GBK（这个站点的既成事实）。
                    // 用 Charset.forName 而不是 Charsets.GBK：Kotlin 的 Charsets 里没有 GBK
                    val body = it.body ?: return@withContext AppResult.Error(
                        IOException("响应体为空")
                    )
                    val charset = body.contentType()?.charset() ?: java.nio.charset.Charset.forName("GBK")
                    val html = String(body.bytes(), charset)
                    val dividendRecords = parseDividendHtml(html, stockCode, market)

                    if (dividendRecords.isEmpty()) {
                        return@withContext AppResult.Error(
                            IllegalStateException("未找到分红数据")
                        )
                    }

                    return@withContext AppResult.Success(dividendRecords)
                }
            } catch (e: IOException) {
                AppResult.Error(e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppResult.Error(e)
            }
        }
    }
    
    /**
     * 解析新浪财经分红HTML页面
     * 表格结构（以 600519 为例）：
     * 公告日期 | 送股(股) | 转增(股) | 派息(税前)(元) | 进度 | 除权除息日 | ...
     * 派息列数据仅为数字，不带"元"，因此不能靠包含"元"来定位。
     */
    private fun parseDividendHtml(html: String, @Suppress("UNUSED_PARAMETER") stockCode: String, @Suppress("UNUSED_PARAMETER") market: String): List<DividendRecord> {
        val records = mutableListOf<DividendRecord>()

        try {
            val doc = Jsoup.parse(html)
            val tables = doc.select("table")
            AppLogger.d("找到 ${tables.size} 个表格", "DividendService")

            for (table in tables) {
                val tableText = table.text()
                if (!tableText.contains("分红") || (!tableText.contains("派息") && !tableText.contains("分红方案"))) {
                    continue
                }

                AppLogger.d("找到分红表格", "DividendService")
                val headerIndices = parseDividendTableHeaders(table)

                val rows = table.select("tr")
                AppLogger.d("表格行数: ${rows.size}", "DividendService")

                for (i in 1 until rows.size) {
                    val row = rows[i]
                    val cells = row.select("td")
                    if (cells.size < 4) continue

                    try {
                        val announceDate = cells.getOrNull(headerIndices.announceDateIndex)?.text()?.trim()
                            ?: continue
                        if (!announceDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) continue

                        val dividendText = cells.getOrNull(headerIndices.dividendIndex)?.text()?.trim() ?: "0"
                        val dividendPerShare = StockCodeManager.parseDividendAmount(dividendText)
                        if (dividendPerShare <= 0) continue

                        val exDividendDate = cells.getOrNull(headerIndices.exDateIndex)?.text()?.trim() ?: ""
                        val fiscalYear = StockCodeManager.extractFiscalYear(announceDate).toString()

                        AppLogger.d("解析到分红记录: 日期=$announceDate, 派息=$dividendPerShare, 除权日=$exDividendDate, 财年=$fiscalYear", "DividendService")

                        records.add(
                            DividendRecord(
                                exDividendDate = exDividendDate,
                                dividendPerShare = dividendPerShare.toString(),
                                announceDate = announceDate,
                                fiscalYear = fiscalYear
                            )
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLogger.e("解析表格行失败: ${e.message}", tag = "DividendService")
                        continue
                    }
                }

                if (records.isNotEmpty()) break
            }

            AppLogger.d("最终解析到 ${records.size} 条分红记录", "DividendService")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("HTML解析异常", e, "DividendService")
        }

        return records
    }

    /**
     * 识别分红表格表头关键列索引。
     * 若表头解析失败，回退到新浪标准列索引。
     */
    private fun parseDividendTableHeaders(table: org.jsoup.nodes.Element): HeaderIndices {
        val idx = DividendHtmlParser.resolveHeaderIndices(
            table,
            listOf(
                DividendHtmlParser.ColumnSpec("announceDate", listOf("公告日期", "公告日"), 0),
                DividendHtmlParser.ColumnSpec("dividend", listOf("派息"), 3),
                DividendHtmlParser.ColumnSpec("exDate", listOf("除权除息日", "除息日"), 5)
            )
        )
        return HeaderIndices(
            announceDateIndex = idx["announceDate"] ?: -1,
            dividendIndex = idx["dividend"] ?: -1,
            exDateIndex = idx["exDate"] ?: -1
        )
    }

    private data class HeaderIndices(
        val announceDateIndex: Int,
        val dividendIndex: Int,
        val exDateIndex: Int
    )
}