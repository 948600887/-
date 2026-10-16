package com.example.stockdiary.data.sync

import com.example.stockdiary.utils.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
