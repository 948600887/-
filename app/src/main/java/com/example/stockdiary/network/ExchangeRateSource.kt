package com.example.stockdiary.network

import com.example.stockdiary.common.APIEndpoints
import com.example.stockdiary.utils.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.nio.charset.Charset

/**
 * 汇率数据源。
 *
 * 与 [com.example.stockdiary.utils.ExchangeRateManager] 的解耦点：管理器只依赖
 * 本接口，默认实现走网络，测试可整体替换为假源——单测因此完全离线可跑。
 */
interface RateSource {
    /**
     * 拉取当前汇率。
     *
     * @return 币种代码（大写）→ 1 单位外币兑人民币的汇率；**拉不到返回 null**
     *   （网络失败 / 响应不可解析 / 一条有效数据都没有）。
     *   合理性校验不在这里做，由调用方（管理器）统一负责。
     */
    suspend fun fetch(): Map<String, Double>?
}

/**
 * 默认数据源：新浪外汇行情 `fx_s<币种>cny`。
 *
 * ## 选型理由（实测对比过东财 push2）
 *
 * - **无需 Key、无配额概念**，国内直连稳定（本机实测一次请求拿到全部 9 个币种）；
 * - **一次请求拉全部币种**：`list=` 支持逗号分隔多代码。东财 push2 的
 *   `secid=133.XXXCNY` 要逐个探查可用代码，批量接口返回的还是按小数位
 *   放大的整数（f43 需再猜一个缩放因子），解析更脆；
 * - 响应是固定形态的 `var hq_str_fx_susdcny="时间,价格1,价格2,...";` 文本，
 *   正则取「第一个数值字段」即可；无效代码返回空串 `""`，自然跳过；
 * - **必须带 Referer 头**：缺了直接返回 `Forbidden`（已实测）。
 *
 * ## 已知取舍
 *
 * - 字段语义（开盘/前收/最新）随交易日波动只有千分位量级差异，对本 App 的
 *   折算用途无影响；管理器侧还有 0.2x~5x 护栏兜底。
 * - 接口只提供「当前值」，没有按历史日期的汇率——与原先静态表的语义一致，
 *   只是静态值现在会变。
 */
class SinaExchangeRateSource : RateSource {

    override suspend fun fetch(): Map<String, Double>? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(URL)
                .addHeader("Referer", APIEndpoints.Headers.SINA_REFERER)
                .addHeader("User-Agent", APIEndpoints.Headers.USER_AGENT)
                .build()

            NetworkClient.defaultClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    AppLogger.w("汇率源 HTTP ${response.code}", TAG)
                    return@use null
                }
                val body = response.body ?: return@use null
                // 响应为 GB18030 文本。只解析数字字段时字符集其实无关，
                // 仍显式按 GB18030 解码，不依赖 Content-Type 头是否可靠
                val text = String(body.bytes(), Charset.forName("GB18030"))
                parse(text).takeIf { it.isNotEmpty() }
            }
        } catch (e: CancellationException) {
            // 协程取消必须原样抛出，不能被当成刷新失败吞掉（项目铁律）
            throw e
        } catch (e: Exception) {
            AppLogger.w("汇率源请求失败: ${e.message}", TAG)
            null
        }
    }

    /**
     * 解析 `var hq_str_fx_susdcny="时间,价格1,价格2,...";` 形态的响应。
     *
     * 取每个代码的**第一个数值字段**作为行情价：无效代码返回 `""`，
     * split 后取不到下标 1，自然跳过；某个币种解析失败不影响其余币种。
     */
    private fun parse(text: String): Map<String, Double> {
        val result = HashMap<String, Double>()
        for (match in QUOTE_REGEX.findAll(text)) {
            val code = match.groupValues[1].uppercase()
            // 取时间字段（index 0）之后的第一个数值字段作为行情价，避免依赖固定下标：
            // 新浪若改字段顺序，固定 index 1 会把名称/时间当价格，导致 USD 等币种被静默跳过。
            val price = match.groupValues[2].split(',')
                .drop(1)
                .firstNotNullOfOrNull { it.trim().toDoubleOrNull() }
            if (price != null) {
                result[code] = price
            }
        }
        return result
    }

    companion object {
        private const val TAG = "ExchangeRateSource"

        /**
         * 与 [com.example.stockdiary.utils.ExchangeRateManager] 静态默认表一致的外币集合。
         * CNY 恒为 1.0，无需拉取。
         */
        private val FOREIGN_CURRENCIES =
            listOf("USD", "EUR", "HKD", "JPY", "GBP", "AUD", "CAD", "SGD", "CHF")

        /** 一次请求拉全部币种：新浪 list= 支持逗号分隔多代码 */
        private val URL: String =
            APIEndpoints.Sina.HQ_API + "/list=" +
                FOREIGN_CURRENCIES.joinToString(",") { "fx_s${it.lowercase()}cny" }

        /** 形如 `var hq_str_fx_susdcny="..."` 的声明；组 1 为币种代码（小写），组 2 为字段串 */
        private val QUOTE_REGEX = Regex("""var hq_str_fx_s([a-z]+)cny="([^"]*)"""")
    }
}

/**
 * 兜底数据源：open.er-api.com 的免费汇率接口（以 USD 为基准，一次请求返回全币种）。
 *
 * ## 为什么选它做主源之外的兜底
 *
 * - **无需 Key、无配额**，返回标准 JSON，不含 HTML/GB18030 这类易变文本；
 * - 一次请求就能算出全部外币兑人民币：先取 `rates["CNY"]` 得到「1 USD = ? CNY」，
 *   再用 `1 USD = rates[cur]` 反推「1 cur = ? CNY」；
 * - 只做**主源失败时**的兜底（见 [FallbackRateSource]），正常链路不会多打一次网络。
 *
 * ## 取舍
 *
 * - 接口以 USD 为基准，人民币汇率是换算出来的，与新浪直接报 CNY 存在千分位差异；
 *   管理器侧 0.2x~5x 护栏与静态表兜底不变，折算用途足够。
 * - 只返回 [FOREIGN_CURRENCIES] 白名单内的币种；缺失/非有限/非正数的条目**直接跳过**，
 *   绝不落成 0（0 会让该币种资产在汇总时凭空消失）。
 */
class ErApiExchangeRateSource : RateSource {

    override suspend fun fetch(): Map<String, Double>? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(URL)
                .addHeader("User-Agent", APIEndpoints.Headers.USER_AGENT)
                .build()

            NetworkClient.defaultClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    AppLogger.w("汇率兜底源 HTTP ${response.code}", TAG)
                    return@use null
                }
                val body = response.body?.string() ?: return@use null
                parse(body)
            }
        } catch (e: CancellationException) {
            // 协程取消原样抛出，不能被当成兜底失败吞掉（项目铁律）
            throw e
        } catch (e: Exception) {
            AppLogger.w("汇率兜底源请求失败: ${e.message}", TAG)
            null
        }
    }

    /**
     * 解析 `{"result":"success","rates":{"USD":1.0,"CNY":7.2,...}}`。
     *
     * 只接受 `result == success` 且 `rates.CNY` 为有限正数的响应；单个币种缺失/为 0/NaN
     * 一律跳过，保证返回的 Map 里没有「0 汇率」这种会把资产折算成 0 的脏值。
     */
    internal fun parse(json: String): Map<String, Double>? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (root.optString("result") != "success") return null
        val rates = root.optJSONObject("rates") ?: return null

        val cnyPerUsd = rates.optDouble("CNY", Double.NaN)
        if (!cnyPerUsd.isFinite() || cnyPerUsd <= 0.0) return null

        val result = HashMap<String, Double>()
        for (code in FOREIGN_CURRENCIES) {
            val perUsd = rates.optDouble(code, Double.NaN)
            if (!perUsd.isFinite() || perUsd <= 0.0) continue
            val cnyPerUnit = cnyPerUsd / perUsd
            if (cnyPerUnit.isFinite() && cnyPerUnit > 0.0) {
                result[code] = cnyPerUnit
            }
        }
        return result.takeIf { it.isNotEmpty() }
    }

    companion object {
        private const val TAG = "ErApiExchangeRateSource"

        /** 与 [SinaExchangeRateSource] / 管理器静态默认表一致的外币集合。CNY 由 USD 基准换算，不单列 */
        private val FOREIGN_CURRENCIES =
            listOf("USD", "EUR", "HKD", "JPY", "GBP", "AUD", "CAD", "SGD", "CHF")

        /** USD 基准：一次请求拿全币种，避免按币种逐个请求 */
        private const val URL = "https://open.er-api.com/v6/latest/USD"
    }
}

/**
 * 主源 + 兜底源：**只有主源失败**（返回 null / 空 Map / 抛异常）才请求兜底源。
 *
 * 刻意不做并联：正常刷新只有一次请求，请求数与单源完全一致；也不会因为两个源
 * 各回一份数据而做取舍/平均。兜底源返回的数据仍由管理器统一过 0.2x~5x 护栏。
 */
class FallbackRateSource(
    private val primary: RateSource,
    private val fallback: RateSource
) : RateSource {

    override suspend fun fetch(): Map<String, Double>? {
        val primaryResult = try {
            primary.fetch()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w("汇率主源失败，转兜底源: ${e.message}", TAG)
            null
        }
        if (!primaryResult.isNullOrEmpty()) return primaryResult

        if (primaryResult != null) {
            AppLogger.w("汇率主源返回空数据，转兜底源", TAG)
        }
        return try {
            fallback.fetch()?.takeIf { it.isNotEmpty() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w("汇率兜底源失败: ${e.message}", TAG)
            null
        }
    }

    companion object {
        private const val TAG = "FallbackRateSource"
    }
}

/**
 * 空数据源：fetch 恒返回 null。
 *
 * 仅供 [com.example.stockdiary.utils.ExchangeRateManager.resetForTesting] 默认注入，
 * 保证单元测试在任何情况下都不会误触真实网络。
 */
class NoOpRateSource : RateSource {
    override suspend fun fetch(): Map<String, Double>? = null
}
