package com.example.stockdiary.data.sync

import com.example.stockdiary.database.Asset

/**
 * 统一同步入口的「请求契约」。
 *
 * G4/G5 起由 [PriceSyncCoordinator] 消费：7 个 UI/Worker 调用点统一构造本对象，
 * 由 [PriceSyncRequest.scenario] 推导 forceRefresh 与日志文案，再委托给
 * [AssetSyncManager.syncAllAssets]。不要再在调用方直接传 9 个散参数。
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
