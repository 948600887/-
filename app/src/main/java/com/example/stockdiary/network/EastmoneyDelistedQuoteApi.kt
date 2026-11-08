package com.example.stockdiary.network

import com.example.stockdiary.utils.AppLogger
import com.example.stockdiary.utils.StockCodeManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * 退市股兜底行情（东方财富 K 线）。
 *
 * 新浪 / 腾讯对 400xxx / 420xxx 老三板退市股都没有报价；实测东财 K 线仍有最后一根
 * 周收盘（如 R东方1 2026-11-06 收盘 0.22）。本 API 只负责「取最后一根收盘价 + 交易日」，
 * 是否该用、怎么计数由 [com.example.stockdiary.data.sync.AssetSyncManager] 决定。
 *
 * 刻意不并入现有新浪 / 腾讯主链路：它只服务退市股，请求量与失败语义都应隔离，
 * 不能影响正常股票的数据源健康与熔断。
 */
object EastmoneyDelistedQuoteApi {
    private const val TAG = "EastmoneyDelistedQuoteApi"
    private const val KLINE_URL = "https://push2his.eastmoney.com/api/qt/stock/kline/get"
    private val DATE_REGEX = Regex("""\d{4}-\d{2}-\d{2}""")

    /** 东财 K 线返回的最后收盘价 + 该收盘对应的交易日（非抓取时刻） */
    data class DelistedQuote(val price: Double, val tradeDate: String)

    /**
     * 取退市股最后一根有效收盘价。
     *
     * 400/420 老三板在东财的 market id 通常是 0；个别标的可能落在 1，故先试 0 再试 1。
     * 任一网络/解析失败都返回 null（调用方回落到「跳过(退市)」，不喂数据源健康）。
     *
     * @param symbol 库里的代码（bj400264 / sh400123 均可，内部抽纯数字）
     */
    suspend fun fetchLastClose(
        symbol: String,
        client: OkHttpClient = NetworkClient.defaultClient
    ): DelistedQuote? = withContext(Dispatchers.IO) {
        val code = StockCodeManager.extractDigits(symbol)
        if (code.length != 6) return@withContext null

        for (market in listOf(0, 1)) {
            val quote = requestLastClose(code, market, client)
            if (quote != null) return@withContext quote
        }
        null
    }

    private suspend fun requestLastClose(
        code: String,
        market: Int,
        client: OkHttpClient
    ): DelistedQuote? {
        val url = "$KLINE_URL?secid=$market.$code&klt=101&fqt=1&lmt=1&end=20500101" +
            "&fields1=f1,f2,f3,f4,f5,f6&fields2=f51,f53"
        return try {
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    AppLogger.d("退市兜底 K 线返回 ${response.code}: $market.$code", TAG)
                    return@use null
                }
                val body = response.body?.string() ?: return@use null
                parseLastClose(body)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.d("退市兜底 K 线请求失败: $market.$code（${e.message}）", TAG)
            null
        }
    }

    /**
     * 解析 K 线响应，取**最后一根**（lmt=1 时即唯一一根）。
     *
     * 只认 `data.klines` 里形如 `2026-11-06,0.22` 的日期+收盘价：
     * - `data` 为 null / `rc != 0`（无此标的）→ null
     * - 收盘价非正、非有限、日期不合法 → null
     * 这样调用方拿到的一定是「真实历史收盘」，不会把抓取时刻当行情时间。
     */
    internal fun parseLastClose(json: String): DelistedQuote? {
        return try {
            val root = JSONObject(json)
            if (root.optInt("rc", -1) != 0) return null
            val data = root.optJSONObject("data") ?: return null
            val klines = data.optJSONArray("klines") ?: return null
            if (klines.length() == 0) return null

            val bar = klines.optString(klines.length() - 1)
            val parts = bar.split(',')
            if (parts.size < 2) return null

            val tradeDate = parts[0].trim()
            if (!DATE_REGEX.matches(tradeDate)) return null

            val price = parts[1].trim().toDoubleOrNull() ?: return null
            if (!price.isFinite() || price <= 0.0) return null

            DelistedQuote(price = price, tradeDate = tradeDate)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
}
