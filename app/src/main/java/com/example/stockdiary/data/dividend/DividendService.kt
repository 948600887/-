package com.example.stockdiary.data.dividend

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
import okhttp3.OkHttpClient

/**
 * 分红数据获取服务
 */
class DividendService {
    /**
     * 分红抓取使用的 OkHttpClient。
     *
     * 保持 `var` 供单测注入伪造客户端（见 DividendServiceTest）；
     * 初始值经 [createDividendHttpClient] 构造，gzip 契约回归测试
     * （见 DividendServiceGzipDecompressionTest）从同一工厂派生真实配置，
     * 不在测试里另建裸 client——否则恰好会把要防的回归漏掉。
     */
    internal var client: OkHttpClient = createDividendHttpClient()

    companion object {
        private const val BASE_URL = "https://vip.stock.finance.sina.com.cn/corp/go.php/vISSUE_ShareBonus/stockid/"

        /**
         * 构造分红抓取专用 OkHttpClient。
         *
         * 抽成 internal 供单测直接驱动**真实** client 配置做端到端 gzip 契约验证
         * （见 DividendServiceGzipDecompressionTest）——测试里若另建一个裸 client，
         * 恰好会把要防的回归（有人给请求加回手动 Accept-Encoding）漏掉。
         *
         * 当前经 [NetworkClient.defaultClient] 派生且无附加 newBuilder 链，
         * 与修复前的构造保持逐字一致（该 client 本就只是共享池的一个别名）。
         */
        internal fun createDividendHttpClient(): OkHttpClient = NetworkClient.defaultClient

        // 请求头
        private val HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "Referer" to "https://finance.sina.com.cn",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
            // 注意：**绝不能**手动加 Accept-Encoding（如 gzip, deflate, br）！
            // OkHttp 的 BridgeInterceptor 规则：请求若已带 Accept-Encoding，它就认为
            // 「调用方要自己解压」而**不再自动解压**。新浪这个页面（vip.stock.finance.sina.com.cn）
            // 对稍大的响应会返回 gzip 压缩体（魔数 1f 8b），App 拿到压缩字节直接按 GBK
            // 解码成乱码 → Jsoup 找不到任何分红表格 → 解析 0 条记录 → 上层吞成
            // 「新浪分红源未应答」→ markFailed 6 小时退避：整条兜底源静默失效
            // （2026-10-25 curl 实证：带该头请求返回 Content-Encoding: gzip 的压缩体，
            // 不带则返回 58740 字节明文 HTML）。
            // 删掉这个头后，OkHttp 自动加 `Accept-Encoding: gzip` 并自动解压，
            // 上游拿到明文，GBK 解码与表格解析恢复正常。
            // 这与 SinaStockAPI.createSinaHttpClient 里同型 bug（commit ee1fbba）是同一根因。
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
    internal fun parseDividendHtml(html: String, @Suppress("UNUSED_PARAMETER") stockCode: String, @Suppress("UNUSED_PARAMETER") market: String): List<DividendRecord> {
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

                        val rawExDate = cells.getOrNull(headerIndices.exDateIndex)?.text()?.trim() ?: ""
                        // 除息日为空保持既有行为（未实施的预案行可能没有除息日）；
                        // 非空但非法（如 2026-13-01、2026-02-30）则丢弃整行——
                        // 脏日期会让消费端 month=13 炸 IndexOutOfBounds，必须在入口拦住。
                        val normalizedExDate = DividendDateValidator.normalizeOrNull(rawExDate)
                        if (rawExDate.isNotEmpty() && normalizedExDate == null) {
                            AppLogger.w("新浪分红行除权除息日非法，丢弃该行: $rawExDate", "DividendService")
                            continue
                        }
                        val fiscalYear = StockCodeManager.extractFiscalYear(announceDate).toString()

                        AppLogger.d("解析到分红记录: 日期=$announceDate, 派息=$dividendPerShare, 除权日=${normalizedExDate.orEmpty()}, 财年=$fiscalYear", "DividendService")

                        records.add(
                            DividendRecord(
                                exDividendDate = normalizedExDate.orEmpty(),
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