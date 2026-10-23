package com.example.stockdiary.network

/**
 * 「币安主机当前不可达」——主机级熔断（见 [BinanceHostGate]）期间发出的信号。
 *
 * 用户所在网络连不上币安公开行情主机（data-api.binance.vision）时，逐个加密货币标的
 * 重复试错没有意义（换标的、换交易对都一样连不上），熔断器会在一段冷却期内直接短路请求。
 * 这个异常就是那条短路路径的返回值。
 *
 * 调用方约定（照 [QuoteSuspendedException] 的跨层判断写法）：
 * - **不计同步失败**：熔断是临时自我保护，不是故障，计进 `failedCount`
 *   会触发「同步多次失败，请检查网络」的通知，误导用户；
 * - 价格保持上一次的值不动，等冷却过去网络恢复后自然更新；
 * - 只对加密货币路径产生：股票 / 基金行情链路不经过币安，不会收到它。
 */
class BinanceHostUnavailableException(message: String) : Exception(message) {

    companion object {
        /**
         * 供只拿到 Throwable 的记账处判断。
         *
         * 走 cause 链遍历：跨层传递后异常可能被重新包装（如批量同步包一层
         * IllegalStateException），只看顶层类型会漏判。
         */
        fun isUnavailable(e: Throwable?): Boolean {
            var error: Throwable? = e
            while (error != null) {
                if (error is BinanceHostUnavailableException) return true
                error = error.cause
            }
            return false
        }
    }
}
