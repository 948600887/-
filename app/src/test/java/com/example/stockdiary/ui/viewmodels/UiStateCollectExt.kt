package com.example.stockdiary.ui.viewmodels

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 在 [scope] 内收集 [StateFlow]，返回 (收集 Job, 取最新值的闭包)。
 *
 * 为什么需要：Dashboard/Portfolio 的 `uiState` 是
 * `combine(...).stateIn(SharingStarted.WhileSubscribed(5000))` 派生的——
 * **没有订阅者时上游不活跃**，`.value` 永远停在初始态。单测直接读 `.value`
 * 会拿到陈旧值，表现为 waitUntil 空转 3 秒后放过、assertEquals 读到 0.0/null
 * （UiState 合流重构时两个 VM 的测试各踩一次，且有个「预期 null」的用例
 * 原本就在假绿）。UI 天然是订阅者，测试必须显式订阅才有意义。
 *
 * 为什么是 Unconfined：本项目的 VM 单测用 UnconfinedTestDispatcher +
 * 阻塞式 waitUntil（Thread.sleep 轮询）——sleep 期间 runBlocking 的
 * event loop 不推进，普通 launch 的 collect 协程排不上队。Unconfined 下
 * collect 在 launch 调用处立即执行，且 VM 更新 _uiState 时（同一线程、
 * 同样 unconfined）会沿 combine → uiState → 本 collect 同步传播回来，
 * latest 在 waitUntil 轮询前就已就位。
 *
 * 用法：
 * ```
 * val (trackJob, latest) = vm.uiState.trackIn(this)
 * try { ... waitUntil { latest().xxx } ... } finally { trackJob.cancel() }
 * ```
 */
fun <T> StateFlow<T>.trackIn(scope: CoroutineScope): Pair<Job, () -> T> {
    var latest = value
    val job = scope.launch(Dispatchers.Unconfined) { collect { latest = it } }
    return job to { latest }
}

