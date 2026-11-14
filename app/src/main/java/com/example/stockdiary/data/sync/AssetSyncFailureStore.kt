package com.example.stockdiary.data.sync

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 按资产记录「上一次行情刷新是否失败」的轻量持久层。
 *
 * ## 为什么不用 Room 加字段
 *
 * `Asset` 加一列会让 DB 版本从 18 提到 19，而本项目**没有 adb**，v19 迁移无法
 * 真机端到端验证（见 `tools/check_invariants.py` 的 A7「锁 Room 版本 v18」）。
 * 这个状态只是界面上的一句提示，丢了也不会导致数据错误，用 SharedPreferences
 * 存一组资产 id 即可，零迁移、旧数据默认「从未失败」（不会显示任何提示）。
 *
 * ## 语义（与卡片提示严格对应）
 *
 * - **只有「这次刷新确实失败」才置位**：同步编排器把资产计进 `failedCount` 的
 *   分支（网络/解析/服务端错误等）才会调用本 store；
 * - **跳过不算失败**：断网但仍有可用旧价、行情源熔断、不可交易、停牌、退市跳过、
 *   缓存过期等分支都不置位，也不清除（上一次真失败仍然如实保留）；
 * - **真成功即清除**：成功落到新价（含稳定币 1.0、退市股东财兜底收盘）后从集合移除，
 *   提示随之消失。
 *
 * 判定与文案只看「这次刷新的结果」，与 `isStale` / `lastSyncTime` 的「价格旧」
 * 语义完全解耦——价格三天没变但每次刷新都成功时，卡片不会再出现任何年龄或警示。
 *
 * ## 为什么是单例
 *
 * 同步侧（[AssetSyncManager]）与界面（`AppContainer`）必须共用同一份
 * [failedAssetIds]，否则「同步写入的内存放不进已经在组合里的卡片」。
 * 线程安全：多标的价格同步是并发的，写入统一走 [applyOutcomes] 的 `@Synchronized`。
 */
class AssetSyncFailureStore private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _failedAssetIds = MutableStateFlow(loadFailedIds())
    val failedAssetIds: StateFlow<Set<Long>> = _failedAssetIds.asStateFlow()

    /**
     * 套用一轮同步的结果。
     *
     * @param succeeded 本轮**确实**成功落到新价的资产 id（含退市股东财兜底成功）
     * @param failed 本轮计入 `failedCount` 的资产 id（不含任何「跳过」）
     *
     * 两集合都为空时直接返回，避免每轮空写一次磁盘。先移除成功、再添加失败：
     * 同一资产若同时出现在两边（理论上不会），以「这次失败」为准。
     */
    @Synchronized
    fun applyOutcomes(succeeded: Collection<Long>, failed: Collection<Long>) {
        if (succeeded.isEmpty() && failed.isEmpty()) return
        val current = _failedAssetIds.value
        val next = computeFailedAssetIds(current, succeeded, failed)
        if (next == current) return
        _failedAssetIds.value = next
        // apply() 异步落盘：调用点在一轮同步收尾的 NonCancellable 段，不阻塞界面
        prefs.edit().putStringSet(KEY_FAILED_ASSET_IDS, next.map { it.toString() }.toSet()).apply()
    }

    /**
     * 从备份恢复后彻底复位失败集合。
     *
     * 必须**同时**清 `_failedAssetIds` 内存流与磁盘集合：只清内存的话，下一轮同步
     * [applyOutcomes] 读到的 `current` 仍是旧集合（本 store 的写入口径是「以内存流为准、
     * 差量计算后整集合回写磁盘」），会把恢复前的旧失败 id 原样写回磁盘，卡片又挂上
     * 「上次更新未成功」。这里两个地方一起清，[loadFailedIds] 读不到键值即回落空集合。
     *
     * 与 [applyOutcomes] 同样加 [Synchronized]：恢复跑在 IO 协程，同步结果可能并发写入。
     */
    @Synchronized
    fun clear() {
        if (_failedAssetIds.value.isNotEmpty()) {
            _failedAssetIds.value = emptySet()
        }
        prefs.edit().remove(KEY_FAILED_ASSET_IDS).apply()
    }

    private fun loadFailedIds(): Set<Long> =
        prefs.getStringSet(KEY_FAILED_ASSET_IDS, emptySet())
            ?.mapNotNull { it.toLongOrNull() }
            ?.toSet()
            ?: emptySet()

    companion object {
        private const val PREFS_NAME = "asset_sync_failure_prefs"
        private const val KEY_FAILED_ASSET_IDS = "failed_asset_ids"

        @Volatile
        private var instance: AssetSyncFailureStore? = null

        fun getInstance(context: Context): AssetSyncFailureStore =
            instance ?: synchronized(this) {
                instance ?: AssetSyncFailureStore(context.applicationContext).also { instance = it }
            }
    }
}

/**
 * 一轮同步后的失败集合（纯函数，JVM 单测直接钉死转移规则）。
 *
 * - [succeeded] 中出现的 id 一律从集合移除（成功即清除提示）；
 * - [failed] 中出现的 id 加入集合（这次确实失败）；
 * - 两边都没提到的 id 原样保留（本轮被跳过 / 未轮到的资产，状态不能被悄悄洗白）。
 *
 * 顺序刻意是「先清后加」：同一 id 同时出现在两个集合时以失败为准。
 */
internal fun computeFailedAssetIds(
    current: Set<Long>,
    succeeded: Collection<Long>,
    failed: Collection<Long>
): Set<Long> {
    if (failed.isEmpty() && succeeded.isEmpty()) return current
    val next = current.toMutableSet()
    next.removeAll(succeeded.toSet())
    next.addAll(failed)
    return next
}
