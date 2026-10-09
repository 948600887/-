package com.example.stockdiary.data

import com.example.stockdiary.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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
    }

    // 使用独立的 OkHttpClient，避免 NetworkClient 任何配置可能带来的副作用
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    /**
     * 获取场外基金分红历史
     * @param fundCode 纯数字基金代码，如 "110011"
     */
    suspend fun getFundDividendHistory(fundCode: String): Result<List<DividendRecord>> = withContext(Dispatchers.IO) {
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
                    if (records.isNotEmpty()) {
                        return@withContext Result.success(records)
                    }
                } else {
                    AppLogger.w("OkHttp HTTP 请求失败: ${response.code}", TAG)
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
        } catch (e: Exception) {
            AppLogger.e("Jsoup 获取天天基金分红失败: ${e.message}", e, TAG)
        }

        return@withContext Result.failure(Exception("无法获取天天基金分红数据"))
    }

    /**
     * 解析天天基金分红配股HTML页面
     * 表头示例：年份 | 权益登记日 | 除息日 | 每10份分红 | 分红发放日
     */
    private fun parseDividendHtml(html: String, @Suppress("UNUSED_PARAMETER") fundCode: String): List<DividendRecord> {
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
        var exDateIndex = -1
        var regDateIndex = -1
        var dividendIndex = -1
        var payDateIndex = -1

        val headerRows = table.select("tr")
        for (row in headerRows) {
            val cells = row.select("th, td")
            for ((index, cell) in cells.withIndex()) {
                val text = cell.text().trim()
                when {
                    exDateIndex < 0 && text.contains("除息日") -> exDateIndex = index
                    regDateIndex < 0 && text.contains("权益登记日") -> regDateIndex = index
                    // 先匹配更具体的「分红发放日」，避免被下面泛化的「分红」抢走
                    payDateIndex < 0 && (text.contains("分红发放日") || text.contains("派息日")) -> payDateIndex = index
                    dividendIndex < 0 && (text.contains("每10份分红") || text.contains("每份分红") || text.contains("每10份派") || text.contains("每份派") || text.contains("分红")) -> dividendIndex = index
                }
            }
        }

        // 天天基金标准表头回退：年份=0, 权益登记日=1, 除息日=2, 每10份分红=3, 分红发放日=4
        if (exDateIndex < 0) {
            AppLogger.d("未识别除息日列，使用默认索引 2", TAG)
            exDateIndex = 2
        }
        if (dividendIndex < 0) {
            AppLogger.d("未识别分红金额列，使用默认索引 3", TAG)
            dividendIndex = 3
        }
        if (payDateIndex < 0) {
            AppLogger.d("未识别分红发放日列，使用默认索引 4", TAG)
            payDateIndex = 4
        }

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
    private fun parseFundDividendAmount(text: String): Double {
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
