package com.example.stockdiary.network

import java.io.IOException

/**
 * 「只有**已过 TTL** 的旧价可用」——无网络（或行情源全挂）且
 * [com.example.stockdiary.data.price.PriceCacheManager.isCacheValid] 判定缓存已过期时，
 * [com.example.stockdiary.data.price.StockDataManager] 用它替代原先的
 * `Result.success(旧价)` 返回。
 *
 * ## 为什么不能再用 Result.success 返回旧价
 *
 * 旧价一回到 [com.example.stockdiary.data.sync.AssetSyncManager.syncSingleAsset]
 * 就被判 `isSuccess = true` 并重新落库，`AssetDao.updatePriceBySymbol` 会把该行
 * `lastSyncTime` 刷成「刚刚」——一个三天前的收盘价在卡片上显示成刚更新，正是
 * 「数据陈旧透明化」要消灭的伪装。本异常让调用方明确知道「有价但不是新价」，
 * 从而**不写库**（既不刷新 lastSyncTime，也不清空旧价 / 不写 0）。
 *
 * ## 调用方约定（对齐 [QuoteSuspendedException] / [NonTradableAssetException]）
 *
 * - 由同步编排器归到「跳过」（原因：缓存过期），**不计同步失败**：跳过不算失败是刻意设计，
 *   断网 + 过期旧价不是「网络故障重试」也不是「数据源坏了」，不该推高
 *   「同步多次失败，请检查网络」的通知；
 * - 价格保持库里上一次的值不动，等网络 / 行情源恢复后自然更新；
 * - 走 cause 链判断，跨层包装后仍可识别（与 BinanceHostUnavailableException 同款）。
 *
 * 注意：message 刻意不含「停牌 / 无有效报价 / 未获取到股票数据，请检查股票代码」，
 * 避免被 [QuoteSuspendedException.isSuspended] 或符号级失败分类误吞。
 */
class StalePriceCacheException(
    val symbol: String,
    message: String = "价格缓存已过期且无新鲜行情: $symbol"
) : IOException(message) {

    companion object {
        /** 供只拿到 Throwable 的记账处判断（跨层可能被重新包装）。 */
        fun isStaleCache(error: Throwable?): Boolean {
            var e: Throwable? = error
            while (e != null) {
                if (e is StalePriceCacheException) return true
                e = e.cause
            }
            return false
        }
    }
}
