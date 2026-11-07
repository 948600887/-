package com.example.stockdiary.data.price

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 一轮价格同步内「源级失败记账」的协程上下文（显式轮次 ID）。
 *
 * ## 为什么需要它
 *
 * 股票侧曾有重复计数：批量预取整体 0 命中时 [StockDataManager.reportBatchTotalMiss]
 * 按源级失败记 1 次；随后每个逐标的回落的 `getStockData` 又对**同一个源**各记 1 次
 * （[StockDataManager] 源级失败分支）。于是「1 次批量 + N 个回落标的」= 1+N 次，
 * 一波并发抖动就能凑满 [SourceCircuitBreaker.FAIL_THRESHOLD]、误熔整源。
 * 对照币安侧 `UnifiedPriceService.prewarmCryptoPrices` 的「整轮只记一次成败」语义，
 * 这里让同一轮里已被批量记过账的源不再被逐标的回落重复喂熔断。
 *
 * ## 为什么用协程上下文、而不是进程级状态 / ThreadLocal
 *
 * - 由 `AssetSyncManager` 在**批量预取之前**按显式 [roundId] 创建，一轮一个对象；
 *   只在扇出前写入 [reportedSources]，扇出期间只读（`ConcurrentHashMap.newKeySet` 保证并发读安全）。
 * - 通过 `withContext(scope)` 沿「同步扇出 → 行情门面 → StockDataManager」的回落路径传递，
 *   子协程自动继承、**作用域结束即自动失效**，不存在需要手工清理的全局状态，
 *   也不会像参数透传那样在 4 层调用里某一支忘记传就静默失效。
 * - 不引入进程级隐式全局状态，也没有无清理路径的 ThreadLocal。
 * - 前台单标的查价、导入回显等非同步路径没有这个上下文 → 保持原样的「真实 IO 失败照常记账」。
 *
 * 三条不变量因此保持：① 真实持续多波故障仍会在累计到阈值后 OPEN（每轮仍记 1 次）；
 * ② 停牌 / 符号级确定性失败在更早的 `continue` 分支分流、完全不接触熔断；
 * ③ 非同步轮次的前台真实 IO 失败仍记账。
 */
class RoundSourceFailureScope(
    val roundId: Int,
    val reportedSources: MutableSet<String> = ConcurrentHashMap.newKeySet()
) : AbstractCoroutineContextElement(Key) {

    companion object Key : CoroutineContext.Key<RoundSourceFailureScope> {
        /**
         * 当前协程所属的同步轮次号；非同步路径（没有该上下文）返回 0。
         *
         * 日志拼装用它统一 `[轮次#N]` 前缀，避免每个调用点各写一遍
         * `currentCoroutineContext()[Key]?.roundId ?: 0`。
         */
        suspend fun currentRoundId(): Int =
            kotlinx.coroutines.currentCoroutineContext()[Key]?.roundId ?: 0
    }

    /** 该源本轮是否已由「批量预取整体 0 命中」记过一次源级失败。 */
    fun isAlreadyReported(sourceName: String): Boolean = sourceName in reportedSources

    override fun toString(): String =
        "RoundSourceFailureScope(roundId=$roundId, reported=${reportedSources.sorted()})"
}
