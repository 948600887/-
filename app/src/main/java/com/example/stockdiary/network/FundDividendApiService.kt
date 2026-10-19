package com.example.stockdiary.network

import com.example.stockdiary.data.dividend.DividendHtmlParser
import com.example.stockdiary.data.dividend.DividendRecord
import com.example.stockdiary.network.NetworkClient
import com.example.stockdiary.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.HttpStatusException
import org.jsoup.Jsoup
import kotlinx.coroutines.CancellationException

/**
 * 场外基金分红API服务
 * 使用天天基金分红配股页面获取基金分红数据
 */
class FundDividendApiService {

    companion object {
        private const val TAG = "FundDividendApi"
        private const val FHSP_URL = "https://fundf10.eastmoney.com/fhsp_{code}.html"
        // 天天基金页面可能给出"每份"或"每10份"两种单位，统一转换为"每10份"
        private const val MAX_DIVIDEND_PER_TEN_SHARES = 100.0

        /**
         * 进程级限流退避窗口（毫秒时间戳）。一旦被天天基金以 514/429 限流，
         * 在此时间戳之前，所有场外基金分红抓取统一短路跳过，不再对同一个被限流的主机
         * 用 OkHttp 或 Jsoup 反复猛打——那只会拉长封禁窗口。
         */
        @Volatile
        internal var rateLimitedUntil: Long = 0L

        /** 限流后强制退避时长（60 秒），覆盖绝大多数天天基金限流窗口。 */
        private const val RATE_LIMIT_BACKOFF_MS = 60_000L
    }

    // 复用统一客户端：通过 defaultClient.newBuilder() 派生，共享底层 ConnectionPool/Dispatcher
    // （连接池复用，避免每 API 类各建一个连接池的浪费）；defaultClient 不带 BaseUrlInterceptor，
    // 不会干扰天天基金 fhsp 页面的完整 https URL 请求。仅保留基金页面所需的长超时。
    internal var client: OkHttpClient = NetworkClient.defaultClient.newBuilder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    /**
     * 获取场外基金分红历史
     * @param fundCode 纯数字基金代码，如 "110011"
     */
    suspend fun getFundDividendHistory(fundCode: String): Result<List<DividendRecord>> = withContext(Dispatchers.IO) {
        // 进程级限流退避：一旦被天天基金 514/429 限流，整段时间内所有基金统一跳过，
        // 不再对同一个被限流的主机用 OkHttp 或 Jsoup 反复猛打（只会拉长封禁窗口）。
        if (System.currentTimeMillis() < rateLimitedUntil) {
            AppLogger.w("天天基金限流退避中，跳过 $fundCode 本次抓取", TAG)
            return@withContext Result.failure(Exception("天天基金限流退避中，跳过"))
        }

        val url = FHSP_URL.replace("{code}", fundCode)

        // 第一次尝试：使用 OkHttp
        try {
            AppLogger.apiUrl("GET", url, TAG)
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .addHeader("Referer", "https://fundf10.eastmoney.com/")
                .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()

            client.newCall(request).execute().use { response ->
                AppLogger.d("天天基金响应: code=${response.code}, isSuccessful=${response.isSuccessful}, contentType=${response.header("Content-Type")}", TAG)
                if (response.isSuccessful) {
                    val html = response.body?.string() ?: ""
                    AppLogger.d("天天基金HTML长度: ${html.length}, 是否包含分红表格: ${html.contains("每10份分红") || html.contains("每份分红")}", TAG)
                    val records = parseDividendHtml(html, fundCode)
                    AppLogger.d("OkHttp 解析到 ${records.size} 条基金分红记录", TAG)

                    // **请求成功就是拿到答案了，哪怕解析出 0 条。**
                    //
                    // 以前这里写成「解析到非空才返回」，于是「这只基金确实没有分红」
                    // 被当成失败，掉到下面用 Jsoup 把**同一个 URL** 再抓一遍。
                    // 实测：一次全量抓取里 525 次抓回 0 条、只有 77 次有数据（87% 是空的），
                    // 而这些空页面上明写着「暂无分红信息!」—— 第二遍必然也是 0。
                    // 等于每个空标的付双倍流量、多解析一遍 40KB 的 DOM，
                    // 而且 Jsoup 那条路遇到非 2xx 会抛 HttpStatusException，
                    // 诊断报告里那 106 次 HTTP 514（反爬限流）就是这么撞出来的。
                    //
                    // 唯一还值得换 Jsoup 重试的情况是**拿到的根本不是分红页**
                    // （反爬页 / 错误页），判据见 [isRealDividendPage]。
                    if (isRealDividendPage(html)) {
                        return@withContext Result.success(records)
                    }
                    AppLogger.w("天天基金返回的页面不像分红页（可能是反爬页），换 Jsoup 重试", TAG)
                } else {
                    AppLogger.w("OkHttp HTTP 请求失败: ${response.code}", TAG)
                    // 限流（514/429）：设置退避窗口并直接失败，绝不再用 Jsoup 对同一个被限流
                    // 主机补刀——那只会让限流窗口更久。其它非 2xx 仍保留降级到 Jsoup 的机会。
                    if (response.code == 514 || response.code == 429) {
                        rateLimitedUntil = System.currentTimeMillis() + RATE_LIMIT_BACKOFF_MS
                        AppLogger.w("天天基金限流（${response.code}），本周期退避，不再用 Jsoup 兜底猛打", TAG)
                        return@withContext Result.failure(Exception("天天基金限流（${response.code}），本周期退避"))
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w("OkHttp 获取天天基金分红失败: ${e.message}", TAG)
        }

        // 第二次尝试：使用 Jsoup 直接连接作为备用
        try {
            AppLogger.d("尝试使用 Jsoup 备用获取天天基金分红: $url", TAG)
            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .referrer("https://fundf10.eastmoney.com/")
                .timeout(15000)
                .get()
            val html = doc.html()
            AppLogger.d("Jsoup HTML长度: ${html.length}, 是否包含分红表格: ${html.contains("每10份分红") || html.contains("每份分红")}", TAG)
            val records = parseDividendHtml(html, fundCode)
            AppLogger.d("Jsoup 解析到 ${records.size} 条基金分红记录", TAG)
            if (records.isNotEmpty()) {
                return@withContext Result.success(records)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpStatusException) {
            // 限流（514/429）：同样设置退避窗口并直接失败，不再继续走最终的兜底失败逻辑
            // 或重复对限流主机发请求。其它 HttpStatusException（如 404/503）仍按普通异常处理。
            if (e.statusCode == 514 || e.statusCode == 429) {
                rateLimitedUntil = System.currentTimeMillis() + RATE_LIMIT_BACKOFF_MS
                AppLogger.w("天天基金限流（${e.statusCode}），本周期退避，不再用 Jsoup 兜底猛打", TAG)
                return@withContext Result.failure(Exception("天天基金限流（${e.statusCode}），本周期退避"))
            }
            AppLogger.e("Jsoup 获取天天基金分红失败: ${e.message}", e, TAG)
        } catch (e: Exception) {
            AppLogger.e("Jsoup 获取天天基金分红失败: ${e.message}", e, TAG)
        }

        return@withContext Result.failure(Exception("无法获取天天基金分红数据"))
    }

    /**
     * 解析天天基金分红配股HTML页面
     * 表头示例：年份 | 权益登记日 | 除息日 | 每10份分红 | 分红发放日
     */
    internal fun parseDividendHtml(html: String, @Suppress("UNUSED_PARAMETER") fundCode: String): List<DividendRecord> {
        val records = mutableListOf<DividendRecord>()

        try {
            val doc = Jsoup.parse(html)
            val tables = doc.select("table")

            for (table in tables) {
                val tableText = table.text()
                if (!tableText.contains("权益登记日") &&
                    !tableText.contains("除息日") &&
                    !tableText.contains("分红")) {
                    continue
                }

                val headerIndices = parseFundDividendHeaders(table)
                if (headerIndices.exDateIndex < 0 || headerIndices.dividendIndex < 0) {
                    AppLogger.d("未识别到分红表头，尝试下一表格", TAG)
                    continue
                }

                val rows = table.select("tr")
                for (i in 1 until rows.size) {
                    val row = rows[i]
                    val cells = row.select("td")
                    if (cells.size <= maxOf(headerIndices.exDateIndex, headerIndices.dividendIndex)) continue

                    try {
                        val exDate = cells.getOrNull(headerIndices.exDateIndex)?.text()?.trim() ?: ""
                        val regDate = cells.getOrNull(headerIndices.regDateIndex)?.text()?.trim() ?: ""
                        val dividendText = cells.getOrNull(headerIndices.dividendIndex)?.text()?.trim() ?: ""

                        if (!exDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) continue

                        val dividendPerShare = parseFundDividendAmount(dividendText)
                        if (dividendPerShare > 0 && dividendPerShare <= MAX_DIVIDEND_PER_TEN_SHARES) {
                            val fiscalYear = if (exDate.length >= 4) exDate.substring(0, 4) else ""
                            records.add(
                                DividendRecord(
                                    exDividendDate = exDate,
                                    dividendPerShare = dividendPerShare.toString(),
                                    announceDate = regDate,
                                    fiscalYear = fiscalYear,
                                    // 钱真正到账的日子，比除息日通常晚 1~4 周
                                    paymentDate = parsePayDate(cells, headerIndices.payDateIndex)
                                )
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLogger.w("解析基金分红行失败: ${e.message}", TAG)
                        continue
                    }
                }

                if (records.isNotEmpty()) break
            }

            // 兜底：按行查找包含"每10份派"或"每份派"的分红数据
            if (records.isEmpty()) {
                val allRows = doc.select("tr")
                for (row in allRows) {
                    val cells = row.select("td")
                    if (cells.size < 3) continue

                    var date = ""
                    var dividendText = ""

                    for (cell in cells) {
                        val text = cell.text().trim()
                        if (text.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
                            date = text
                        }
                        if (text.contains("每10份派") || text.contains("每份派")) {
                            dividendText = text
                        }
                    }

                    if (date.isNotEmpty() && dividendText.isNotEmpty()) {
                        val dividendPerShare = parseFundDividendAmount(dividendText)
                        if (dividendPerShare > 0 && dividendPerShare <= MAX_DIVIDEND_PER_TEN_SHARES) {
                            val fiscalYear = if (date.length >= 4) date.substring(0, 4) else ""
                            records.add(
                                DividendRecord(
                                    exDividendDate = date,
                                    dividendPerShare = dividendPerShare.toString(),
                                    announceDate = "",
                                    fiscalYear = fiscalYear
                                )
                            )
                        }
                    }
                }
            }

            records.sortByDescending { it.exDividendDate }

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("HTML解析异常: ${e.message}", e, TAG)
        }

        return records
    }

    private data class FundDividendHeaderIndices(
        val exDateIndex: Int,
        val regDateIndex: Int,
        val dividendIndex: Int,
        val payDateIndex: Int
    )

    private fun parseFundDividendHeaders(table: org.jsoup.nodes.Element): FundDividendHeaderIndices {
        // 顺序敏感：payDate 必须先于 dividend，否则「分红发放日」会被泛化的「分红」抢走该列。
        // 与旧 when 分支顺序（除息日→权益登记日→分红发放日→分红）保持一致。
        val idx = DividendHtmlParser.resolveHeaderIndices(
            table,
            listOf(
                DividendHtmlParser.ColumnSpec("exDate", listOf("除息日"), 2),
                DividendHtmlParser.ColumnSpec("regDate", listOf("权益登记日"), 1),
                DividendHtmlParser.ColumnSpec("payDate", listOf("分红发放日", "派息日"), 4),
                DividendHtmlParser.ColumnSpec(
                    "dividend",
                    listOf("每10份分红", "每份分红", "每10份派", "每份派", "分红"),
                    3
                )
            )
        )
        val exDateIndex = idx["exDate"] ?: -1
        val regDateIndex = idx["regDate"] ?: -1
        val dividendIndex = idx["dividend"] ?: -1
        val payDateIndex = idx["payDate"] ?: -1

        if (exDateIndex < 0) AppLogger.d("未识别除息日列，使用默认索引 2", TAG)
        if (dividendIndex < 0) AppLogger.d("未识别分红金额列，使用默认索引 3", TAG)
        if (payDateIndex < 0) AppLogger.d("未识别分红发放日列，使用默认索引 4", TAG)

        return FundDividendHeaderIndices(exDateIndex, regDateIndex, dividendIndex, payDateIndex)
    }

    /** 取「分红发放日」单元格文本，格式不对就当没有 */
    private fun parsePayDate(cells: org.jsoup.select.Elements, index: Int): String? {
        if (index < 0) return null
        val text = cells.getOrNull(index)?.text()?.trim().orEmpty()
        return if (text.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) text else null
    }

    /**
     * 解析天天基金分红金额文本，统一转换为"每10份"单位
     * 支持："每10份派现金0.2000元" -> 0.2，"每份派现金0.0200元" -> 0.2
     * 注意：不能简单提取所有数字，否则会误把"每10份"中的"10"也当作金额一部分。
     */
    internal fun parseFundDividendAmount(text: String): Double {
        if (text.isBlank()) return 0.0

        // 标准格式：每10份派现金0.2000元 / 每份派现金0.0200元
        // 提取"派现金"或"派"后面的数字作为金额
        val match = Regex("派(?:现金)?([0-9]+(?:\\.[0-9]+)?)").find(text)
        val numericValue = if (match != null) {
            match.groupValues[1].toDoubleOrNull() ?: 0.0
        } else {
            // 兜底：提取所有数字（此时已无法确定单位，按默认每10份处理）
            AppLogger.w("无法按标准格式解析分红金额: $text，尝试兜底提取", TAG)
            text.filter { it.isDigit() || it == '.' }.toDoubleOrNull() ?: 0.0
        }

        if (numericValue <= 0) return 0.0

        return when {
            text.contains("每10份") -> numericValue
            text.contains("每份") -> numericValue * 10.0
            else -> {
                AppLogger.w("无法识别的分红单位: $text，默认按每10份处理", TAG)
                numericValue
            }
        }
    }
}

/**
 * 判断这页是不是**真的分红页**（而不是反爬页 / 错误页）。
 *
 * 判据是「有没有分红表的表头」，**不是**「有没有数据行」：
 * 一只从没分过红的基金，页面上照样是
 * 「年份 | 权益登记日 | 除息日 | 每10份分红 | 分红发放日」表头 + 一行「暂无分红信息!」
 * —— **表头在、数据不在**。表头在就说明拿对了页面，此时 0 条是真实答案，
 * 不该再抓一遍（以前就因为这里判错，87% 的空标的都被抓了第二遍，
 * 还把接口往 HTTP 514 限流上撞）；表头不在才说明拿到了反爬页，值得换条路重试。
 *
 * 两个关键词都收：极端情况下页面可能只渲染出表头、或只渲染出空提示。
 * 放在文件级而不是类里，是为了能直接 JVM 单测（不用构造带 OkHttp 客户端的实例）。
 */
internal fun isRealDividendPage(html: String): Boolean =
    html.contains("权益登记日") || html.contains("暂无分红信息")
