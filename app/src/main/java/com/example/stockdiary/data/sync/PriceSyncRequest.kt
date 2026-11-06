package com.example.stockdiary.data.sync

import com.example.stockdiary.database.Asset

/**
 * 统一同步入口的「请求契约」（G1 仅建数据结构，[PriceSyncCoordinator] 在 G4 接入）。
 *
 * [PriceSyncRequest] 此刻不被任何调用方使用：它只是数据结构 + 单测存在，
 * 后续把分散的 `AssetSyncManager.syncAllAssets` 调用点收敛到统一入口时再消费。
 * Kotlin 不会因「公共类未使用」报错，故此处安全预留，不接入任何同步流。
 */
enum class SyncScenario(val triggerLabel: String) {
    /** 后台 WorkManager 周期同步 */
    BACKGROUND_PERIODIC("后台周期同步"),

    /** 币安导入保存时的回填同步 */
    BINANCE_IMPORT_BACKFILL("币安导入保存"),

    /** 资产详情页手动刷新 */
    ASSET_DETAIL_REFRESH("资产详情页刷新"),

    /** 仪表盘（首页）手动刷新 */
    DASHBOARD_FULL_REFRESH("仪表盘刷新"),

    /** 持仓页手动刷新 */
    PORTFOLIO_FULL_REFRESH("持仓页刷新"),

    /** 持仓页批量刷新 */
    PORTFOLIO_BATCH_REFRESH("持仓页批量刷新"),

    /** 导入持仓快照保存 */
    HOLDING_SNAPSHOT_IMPORT("持仓快照保存")
}

/**
 * 一次同步请求的完整描述。
 *
 * @param scenario 触发场景，决定 [SyncScenario.triggerLabel] 文案与策略分支
 * @param assets 本次要同步的资产列表
 * @param onProgress 进度回调（默认空实现，UI 注入真实回调）
 * @param startTime 同步起点时间戳；<= 0 表示 UI 手动触发（走互斥 + 默认预算）
 * @param timeLimitMs 时间预算上限（毫秒）；<= 0 表示不限制
 * @param correctNames 是否顺带校正资产名称
 */
data class PriceSyncRequest(
    val scenario: SyncScenario,
    val assets: List<Asset>,
    val onProgress: (SyncProgress) -> Unit = {},
    val startTime: Long = 0L,
    val timeLimitMs: Long = 0L,
    val correctNames: Boolean = false
)
