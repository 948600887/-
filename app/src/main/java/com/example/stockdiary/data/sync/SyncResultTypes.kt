package com.example.stockdiary.data.sync

import com.example.stockdiary.data.contract.SyncErrorType

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
    val skippedByReason: Map<String, Int> = emptyMap()
) {
    val hasRetryableErrors: Boolean
        get() = networkErrorCount > 0 || serverErrorCount > 0 || unknownErrorCount > 0

    /**
     * 把 [skippedByReason] 渲染成给用户看的一句补充（供刷新结果提示拼接）。
     *
     * 只对「用户会困惑」的两类说话：
     * - 行情源不可达（币安熔断）：这些标的价格**压根没更新**，不说明的话用户会以为
     *   刷新成功却没生效；
     * - 退市/停牌/不可交易：这些本来就没有新价，说明一下免得被当成失败。
     *
     * 其余原因（如「非行情类型」）用户不关心，不占用提示篇幅。
     */
    val skippedHint: String
        get() {
            if (skippedByReason.isEmpty()) return ""
            val gated = skippedByReason["币安熔断"] ?: 0
            val others = skippedByReason.filterKeys { it != "币安熔断" }.values.sum()
            return buildString {
                if (gated > 0) append("；$gated 个加密货币行情源不可达，本次未更新")
                if (others > 0) append("；$others 个无需更新（退市/停牌等）")
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
