package com.example.stockdiary.network

import java.io.IOException

/**
 * 「该标的当前没有有效报价」——停牌、退市整理期、集合竞价前、代码已失效等情形。
 *
 * 这类失败**不是数据源的锅**：换一个源去问同一只停牌股，答案还是没有报价。
 * 如果当成普通失败去累计 `failureCount`，几只长期停牌股就能把数据源顶到
 * 「失败 3 次 → 禁用 30 分钟」，整个 App 的行情同步跟着停摆，
 * 而且日志只有一句「所有数据源都失败」，看不出真实原因。
 *
 * 调用方约定：
 * - 不要对这类失败做 `recordFailure`（不禁用数据源）；
 * - 不要再对同一标的上其它数据源抱有期望，但可以尝试（如腾讯源会退到昨收）；
 * - 不计入同步失败数、不给用户发「网络异常」通知——持仓停牌不是故障。
 */
class QuoteSuspendedException(
    val symbol: String,
    message: String = "停牌或无有效报价: $symbol"
) : IOException(message) {

    companion object {
        /** 供只拿到 message 的地方（如跨层传递后的字符串匹配）判断 */
        fun isSuspended(error: Throwable?): Boolean {
            var e: Throwable? = error
            while (e != null) {
                if (e is QuoteSuspendedException) return true
                val msg = e.message
                if (msg != null && (msg.contains("停牌") || msg.contains("无有效报价"))) return true
                e = e.cause
            }
            return false
        }
    }
}
