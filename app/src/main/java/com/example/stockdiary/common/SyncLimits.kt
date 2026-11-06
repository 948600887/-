package com.example.stockdiary.common

/**
 * 价格同步的「并发度」与「网络级失败熔断阈值」的**唯一来源**。
 *
 * [FAIL_THRESHOLD] 必须**严格大于** [MAX_CONCURRENT_SYNC]：否则单波并发请求
 * （一波恰好 [MAX_CONCURRENT_SYNC] 个）同时挂满 callTimeout，一次就凑满阈值即误熔，
 * 把整个加密货币组合 / 某个数据源整批停更。
 *
 * 历史事故：阈值曾为 5、并发度为 6（5 ≤ 6）。2026-10-26 真机日志 10:33 与 20:07
 * 各有一波 6 个并发请求同时 `InterruptedIOException: timeout`，一波就打满阈值 5；
 * 10:33 那轮直接跳过 130 个标的（币安熔断 129）、耗时 32s（正常轮只要 5~11s）。
 * 抬到 8 后单波抖动不再误熔，真实不可达仍会在约两波（≈40s）内熔断。
 *
 * 为什么不在这里加运行时 `require(...)` / `init {}` 断言：这两个常量被
 * AssetSyncManager、BinanceHostGate、SourceCircuitBreaker 在各自的类初始化期读取，
 * 运行时校验会引入初始化顺序与依赖方向风险。该不变量改由
 * `tools/check_invariants.py` 的 A1 规则在提交前静态钉住（毫秒级、零运行期开销），
 * 并由 `tools/check_invariants_selftest.py` 反证校验器自己不会假绿。
 */
object SyncLimits {
    const val MAX_CONCURRENT_SYNC = 6
    const val FAIL_THRESHOLD = 8
}
