package com.example.stockdiary.network

import com.example.stockdiary.common.APIEndpoints
import com.example.stockdiary.utils.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
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
 * 空数据源：fetch 恒返回 null。
 *
 * 仅供 [com.example.stockdiary.utils.ExchangeRateManager.resetForTesting] 默认注入，
 * 保证单元测试在任何情况下都不会误触真实网络。
 */
class NoOpRateSource : RateSource {
    override suspend fun fetch(): Map<String, Double>? = null
}
