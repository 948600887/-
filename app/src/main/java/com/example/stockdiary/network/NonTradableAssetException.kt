package com.example.stockdiary.network

/**
 * 「该标的根本没有可交易的行情」——币安理财/锁仓（LD 前缀）这类持仓是典型：
 * 它是真实资产、要继续出现在持仓里，但 `LD` 只是 Simple Earn / Locked Staking
 * 的持仓壳层标记，**不是现货交易对**，币安没有 `LDLTCUSDT` 这种市场。
 *
 * 调用方约定：
 * - 不要为它发任何取价请求（发了也必然失败，纯属浪费一次连接）；
 * - **不计同步失败**：既不算成功也不算失败。它不像 [QuoteSuspendedException]
 *   那样「暂时没报价」，也不是网络故障——是「本就没有行情可取」，
 *   计进 `failedCount` 会让用户收到「同步失败，请检查网络」的通知。
 * - 价格保持上一次的值不动。
 */
class NonTradableAssetException(message: String) : Exception(message) {

    companion object {
        /** 供只拿到 Throwable 的记账处判断（手动刷新等其它路径也会拿到这个异常） */
        fun isNonTradable(e: Throwable?): Boolean {
            var error: Throwable? = e
            while (error != null) {
                if (error is NonTradableAssetException) return true
                error = error.cause
            }
            return false
        }
    }
}
