package com.example.stockdiary.data.sync

import com.example.stockdiary.data.contract.SyncErrorType
import com.example.stockdiary.data.price.SourceStats

/**
 * 同步结果相关的纯数据类型，从 [AssetSyncManager] 抽出独立成文件，
 * 避免「对象 + 结果类型」全挤在同一个 1200 行文件里。
 * 仍位于 data.sync 包，调用方 import 路径不变。
 */

data class SyncProgress(
    val current: Int,
    val total: Int,
    val message: String
)

data class SyncResult(
    val successCount: Int,
    val failedCount: Int,
    val networkErrorCount: Int = 0,
    val serverErrorCount: Int = 0,
    val dataErrorCount: Int = 0,
    val unknownErrorCount: Int = 0,
    val dominantErrorType: SyncErrorType? = null,
    /** 是否因接近后台任务时间上限而提前结束（仍有未同步的资产） */
    val truncatedByTimeLimit: Boolean = false,
    /**
     * 本轮各类「跳过」的计数（键为原因，如「币安熔断」→ 130、「退市」→ 1）。
     *
     * 必须带回给 UI：只报「成功 107」的话，用户看不到还有 130 个加密货币因行情源
     * 不可达压根没更新（诊断日志里的典型一轮：成功 107 / 跳过 131（币安熔断 130）），
     * 会以为刷新成功却没生效、于是反复去点刷新——每点一次又白等一轮。
     */
    val skippedByReason: Map<String, Int> = emptyMap(),
    /**
     * 本轮各数据源的成败与耗时快照（成功 / 源级失败 / 符号级失败 / 耗时 / 最近失败原因）。
     *
     * 与 [skippedByReason] 的分工：这边答「哪个源在掉链子、慢在哪」，那边答
     * 「哪些标的没更新、为什么」。两者都随结果回给 UI，不再只埋在日志里。
     */
    val sourceStats: List<SourceStats> = emptyList(),
    /** 本轮同步总耗时（毫秒），用于和下一轮对比判断是否变慢 */
    val elapsedMs: Long = 0L
) {
    val hasRetryableErrors: Boolean
        get() = networkErrorCount > 0 || serverErrorCount > 0 || unknownErrorCount > 0

    /**
     * 本轮跳过的标的**总数**（[skippedByReason] 各原因计数之和）。
     *
     * 跳过从来没有标量字段，只在 map 里，日志里才 `skipReasons.values.sum()` 现算一次
     * （见 AssetSyncManager.logRoundSummary）。现在后台 Worker 的成功判定、通知、日志、
     * 快照策略都要用这个数，收成一个只读派生属性，避免五处各写一遍 sum、日后又漂移。
     */
    val skippedCount: Int
        get() = skippedByReason.values.sum()

    /**
     * 「本轮无有效更新」：0 成功 / 0 失败 / 有跳过。
     *
     * 这是后台成功判定必须让出来的分支：这一轮一个价格都没更新，
     * 不能刷新全局 `last_price_sync_time`、不能清零连续失败、不能写当日快照、
     * 更不能报「成功同步 0 个资产」。
     *
     * 刻意**不**包含 `success == 0 && failed == 0 && skipped == 0`（全 0 极端：
     * 资产为空 / 全被偏好过滤）——那仍按原有「无害覆盖」语义处理。
     */
    val hasNoEffectiveUpdate: Boolean
        get() = successCount == 0 && failedCount == 0 && skippedCount > 0

    /**
     * 把 [skippedByReason] 渲染成给用户看的一句补充（供刷新结果提示拼接）。
     *
     * 只对「用户会困惑」的三类说话：
     * - 行情源不可达（币安熔断）：这些标的价格**压根没更新**，不说明的话用户会以为
     *   刷新成功却没生效；
     * - 缓存过期（断网且旧价已过 TTL）：手上有价但不是新价、且**没有重新落库**，
     *   不说明会被当成「刚更新」；
     * - 退市/停牌/不可交易：这些本来就没有新价，说明一下免得被当成失败。
     *
     * 其余原因（如「非行情类型」）用户不关心，不占用提示篇幅。
     */
    val skippedHint: String
        get() {
            if (skippedByReason.isEmpty()) return ""
            val gated = skippedByReason["币安熔断"] ?: 0
            val stale = skippedByReason["缓存过期"] ?: 0
            // 时间预算截断 / 看门狗取消级联：这两类既不是「无需更新」，也不是源故障，
            // 单独说清「没轮到它、可稍后重试」，不要混进退市/停牌那一句。
            val truncated = (skippedByReason["超时截断"] ?: 0) +
                (skippedByReason["取消级联"] ?: 0)
            val others = skippedByReason
                .filterKeys {
                    it != "币安熔断" && it != "缓存过期" &&
                        it != "超时截断" && it != "取消级联"
                }
                .values.sum()
            return buildString {
                if (gated > 0) append("；$gated 个加密货币行情源不可达，本次未更新")
                if (stale > 0) append("；$stale 个仅有过期的旧价，本次未更新（价格保持上次的值）")
                if (truncated > 0) append("；$truncated 个因本轮时间预算未更新（可稍后重试）")
                if (others > 0) append("；$others 个无需更新（退市/停牌等）")
            }
        }

    /**
     * 把「有源级故障 / 有降级事实的数据源」渲染成一句给用户看的补充（供刷新结果提示拼接）。
     *
     * 只在**源级失败**（超时/连不上/空返回/格式坏）或**降级原因**（轻量源已熔断改走
     * legacy、币安 451 粘死未请求、整片 400 切小重试）时说话：符号级失败（代码不在此源
     * 支持范围）是正常现象，说出来只会制造噪音。无异常时返回空串，不占提示篇幅。
     *
     * 包含源名、失败次数与最近一次失败原因，让用户能判断是「这个源今天不行」，
     * 而不是「App 刷新坏了」。
     */
    val sourceHealthHint: String
        get() {
            val degraded = sourceStats.filter { stat ->
                // 两种「不是故障」的事实必须排除，否则提示会天天喊狼来了：
                // ① 逐只回补成功：批量/整片失败后靠逐只兜底把价格补齐了（success > 0），
                //    源本轮实际可用，不该报「异常」；
                // ② 60s 短退避跳过：只有跳过、没有源级失败，说明源在主动冷却而不是坏了。
                val recoveredByFallback = stat.success > 0
                val backoffSkipOnly = !recoveredByFallback &&
                    stat.sourceLevelFailure == 0 &&
                    stat.skipped > 0
                val unrecoveredFailure = stat.sourceLevelFailure > 0 && !recoveredByFallback
                val unrecoveredDegrade = !stat.degradedReason.isNullOrBlank() &&
                    !recoveredByFallback &&
                    !backoffSkipOnly
                unrecoveredFailure || unrecoveredDegrade
            }
            if (degraded.isEmpty()) return ""
            return buildString {
                append("；数据源异常：")
                append(degraded.joinToString("、") { stat ->
                    val reason = stat.lastFailureReason
                        ?.takeIf { it.isNotBlank() }
                        ?.let { "，${it.take(24)}" }
                        ?: ""
                    val failure = stat.sourceLevelFailure
                        .takeIf { it > 0 }
                        ?.let { "源级失败 $it 次$reason" }
                    val degrade = stat.degradedReason
                        ?.takeIf { it.isNotBlank() }
                        ?.take(30)
                    "${stat.source} " + listOfNotNull(failure, degrade).joinToString("，")
                })
            }
        }
}

/**
 * 看门狗取消在途子任务后，收集方（父协程）该不该「吞掉这次 [CancellationException] 继续收尾」。
 *
 * 纯函数（只取决于入参），便于单测钉死判定，不碰协程/时钟：
 * - `parentActive = true`：只有子任务被看门狗砍了、父协程还活着 → 返回 true，调用方吞掉异常、
 *   继续收尾其余标的，最终照常返回带 `truncatedByTimeLimit=true` 的 [SyncResult]；
 * - `parentActive = false`：父协程也死了（退后台取消 / VM 销毁 / 看门狗自己被外部取消）→
 *   返回 false，调用方必须**原样重抛**（项目铁律：取消不吞），让取消一路传上去。
 */
internal fun shouldContinueAfterChildCancel(parentActive: Boolean): Boolean = parentActive
