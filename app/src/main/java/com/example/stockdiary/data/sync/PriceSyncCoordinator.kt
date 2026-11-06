package com.example.stockdiary.data.sync

import android.content.Context
import com.example.stockdiary.data.repository.AssetRepository

/**
 * 统一同步入口的薄协调器（G4）。
 *
 * 只做两件事：**场景归一化** + **委托给 [AssetSyncManager.syncAllAssets]**。
 * - `forceRefresh` 完全由 [PriceSyncRequest.scenario] 推导（见 [deriveForceRefresh]）；
 * - `syncTrigger` 取 [SyncScenario.triggerLabel]，与 7 个调用方原先散落的文案对齐；
 * - 不持 Context 字段、不接管任何收尾逻辑（分红 / 重排 / 快照 / 通知都在调用方里）。
 *
 * 本对象是「把散落的 `syncAllAssets` 调用点收敛到一个入口」的开关层；运行时行为与原先
 * 直接调用 `syncAllAssets` 完全一致（9 参逐一对齐），可整批 revert 回滚。
 */
object PriceSyncCoordinator {

    /**
     * 发起一轮同步：把请求归一化后原样委托给 [AssetSyncManager.syncAllAssets]。
     *
     * @return 底层同步引擎的 [SyncResult]
     */
    suspend fun sync(
        context: Context,
        repository: AssetRepository,
        request: PriceSyncRequest
    ): SyncResult {
        val forceRefresh = deriveForceRefresh(request.scenario)
        val syncTrigger = request.scenario.triggerLabel
        return AssetSyncManager.syncAllAssets(
            context = context,
            repository = repository,
            assets = request.assets,
            onProgress = request.onProgress,
            forceRefresh = forceRefresh,
            startTime = request.startTime,
            timeLimitMs = request.timeLimitMs,
            correctNames = request.correctNames,
            syncTrigger = syncTrigger
        )
    }

    /**
     * 由场景推导 `forceRefresh`。
     *
     * 仅 [SyncScenario.BACKGROUND_PERIODIC] 与 [SyncScenario.BINANCE_IMPORT_BACKFILL]
     * 为 `false`：
     * - 后台周期同步：分红走「24h TTL + 空结果 7 天」分层，股票仍按自身 TTL 自然刷新，
     *   不应每次强制重抓（见 [AssetSyncManager.syncAllAssets] 的后台调用点注释）；
     * - 币安导入回填：是对已落库持仓的补价，同样无需强制。
     * 其余场景（手动刷新 / 详情 / 持仓 / 快照保存）一律 `true`，保证用户点刷新拿到的是新价。
     */
    internal fun deriveForceRefresh(scenario: SyncScenario): Boolean = when (scenario) {
        SyncScenario.BACKGROUND_PERIODIC,
        SyncScenario.BINANCE_IMPORT_BACKFILL -> false
        else -> true
    }
}
