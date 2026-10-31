package com.example.stockdiary.data.contract

import com.example.stockdiary.database.AssetSnapshot
import com.example.stockdiary.utils.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 跨 repository / sync 两个包共享的契约类型与纯函数。
 *
 * ## 为什么这个包存在
 *
 * [com.example.stockdiary.data.repository] 与 [com.example.stockdiary.data.sync]
 * 原本互相 import，形成循环依赖：sync 需要 repository 的资产/分红数据，repository
 * 又反过来 import 了 sync 里的 `planSnapshotDedup` 与 `withHeartbeat`。把这两类
 * 被双向引用的符号抽到本中立包（叶子层，只依赖 database 与 utils），依赖方向就变成
 * 单向的「sync → repository → contract」+「repository → contract」，循环被打破。
 */

/**
 * 一条「待落库的价格」意图。
 *
 * 同步侧拿到价后**不立刻写库**，先把意图放进批量缓冲，凑够一批才整体落盘
 * （见 [com.example.stockdiary.data.repository.AssetRepository.updatePricesInTransaction]）：
 * 单独一次 UPDATE 就会让 Room 的观察查询整表重查重发一次，逐个写即「每只标的 = 一次整页重组」。
 *
 * @param symbol 落价按代码匹配（与单条 `AssetRepository.updatePriceBySymbol` 同一口径）
 * @param price 现价；非正/NaN 在落库前被拦，不会把市值清零
 * @param millionCopiesIncome 场外基金的万份收益，股票/加密为 null
 */
data class PendingPriceWrite(
    val symbol: String,
    val price: Double,
    val millionCopiesIncome: Double? = null
)

/** 心跳日志的默认间隔：5 秒一条，既不刷屏又能把「卡死」定位到分钟级以内 */
internal const val HEARTBEAT_INTERVAL_MS = 5_000L

/**
 * 给一段长耗时同步阶段加心跳日志。
 *
 * ## 为什么非有它不可
 *
 * 上一轮排查「点刷新卡死」最大的痛点就是**日志全静默**：刷新链路只在开始和
 * 结束打日志，中间十几分钟一条都没有，无法判断是卡在分红级联、某个标的的
 * 网络请求上，还是整个进程已经死了。心跳把「走到哪一步、卡在哪个标的、
 * 已耗时多久」周期性暴露出来，下次再卡死能直接从日志定位。
 *
 * 心跳是 [block] 所在作用域的子协程，[block] 结束（正常或异常）时在
 * finally 里取消，不会拖慢收尾；[status] 每次心跳时重新求值，所以看到的
 * 永远是新数字，不会同一句话刷屏。
 *
 * @param tag 日志 tag（与所在业务类一致，方便按 tag 过滤）
 * @param phase 阶段名，会原样出现在日志里，如「价格同步」「分红预取」
 * @param intervalMs 心跳间隔，见 [HEARTBEAT_INTERVAL_MS]
 * @param status 心跳内容，每次心跳时调用，应返回当前进度摘要
 */
internal suspend fun <T> withHeartbeat(
    tag: String,
    phase: String,
    intervalMs: Long = HEARTBEAT_INTERVAL_MS,
    status: () -> String,
    block: suspend CoroutineScope.() -> T
): T = coroutineScope {
    val safeInterval = intervalMs.coerceAtLeast(1L)
    val startedAt = System.currentTimeMillis()
    val heartbeat = launch {
        while (isActive) {
            delay(safeInterval)
            val now = System.currentTimeMillis()
            if (!shouldEmitHeartbeat(startedAt, now, safeInterval)) continue
            AppLogger.i(
                "【$phase】进行中：${status()}，已耗时 ${(now - startedAt) / 1000} 秒",
                tag
            )
        }
    }
    try {
        block()
    } finally {
        // 取消而非 join：心跳的 delay 是可取消的，取消后立刻结束，
        // 不会让 coroutineScope 多等一个间隔
        heartbeat.cancel()
    }
}

/**
 * 这一跳要不要发。
 *
 * 纯函数：节流规则（间隔内不重发、到点必发）是心跳不刷屏的保证，
 * 被手改成「每次都发」就得靠这个测试兜底。
 */
internal fun shouldEmitHeartbeat(startedAt: Long, now: Long, intervalMs: Long): Boolean {
    if (intervalMs <= 0L) return true
    return now - startedAt >= intervalMs
}

/**
 * 快照表去重计划：同日多行时**保留 timestamp 最大的一条**（最后一次写入为准，
 * 与 createSnapshot「同日覆盖」的语义一致），返回其余待删行的 id 升序列表。
 *
 * 纯函数：只读入参列表做决策，删除动作由调用方执行——这样去重策略本身可被单测钉死，
 * 不需要 Robolectric 跑真库。原归属 [com.example.stockdiary.data.sync.AutoSnapshotPolicy]，
 * 因 repository 需调用而 sync 又依赖 repository，抽到中立包打破循环。
 */
fun planSnapshotDedup(snapshots: List<AssetSnapshot>): List<Long> {
    if (snapshots.isEmpty()) return emptyList()
    return snapshots
        .groupBy { it.date }
        .values
        .filter { it.size > 1 }
        .flatMap { sameDay ->
            // timestamp 最大者保留；timestamp 相等（同时写入）时保留 id 较大者（后插入）
            val keeper = sameDay.maxWithOrNull(
                compareBy<AssetSnapshot> { it.timestamp }.thenBy { it.id }
            ) ?: return@flatMap emptyList()
            sameDay.filter { it.id != keeper.id }.map { it.id }
        }
        .sorted()
}
