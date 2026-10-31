package com.example.stockdiary.data.sync

import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetSnapshot

/**
 * 自动快照的准入判定与快照表去重计划。纯函数、无 IO 无时钟（[now] 由调用方注入）。
 *
 * ## 为什么要有这个文件
 *
 * 每日自动快照（[com.example.stockdiary.data.sync.SnapshotWorker]）原先无条件
 * 「今天没有快照就建一条」。但自动**价格**同步默认是关的——用户几天才手动刷一次时，
 * 中间每天 15:10 的快照全是同一批旧价：月度收益被拉平、波动率样本被零收益污染，
 * 与 2026-10-18 之前「逐日回填补快照」造出的同值假快照同族（那次是 date/时刻
 * 双写，这次是同值单写，危害同级）。所以建之前先问 [shouldCreateAutoSnapshot]。
 *
 * [planSnapshotDedup] 处理另一宗历史遗留：同日多行快照。旧版回填 + `getSnapshotByDate`
 * 无 ORDER BY 只删一条，导致库里可能存在同 date 多行；`createSnapshot` 已改为按
 * date 删全量，但存量行需要一次性修复（见 AssetRepository.repairDuplicateSnapshots）。
 */

/** 价格「还算新鲜」的窗口：24 小时。手动刷新一般每天一次，日内自动同步的用户价格总是新的 */
const val AUTO_SNAPSHOT_STALE_THRESHOLD_MS: Long = 24 * 60 * 60_000L

/**
 * 这次自动快照该不该建。
 *
 * 规则：**过半（严格多于一半）非负债资产的 `lastSyncTime` 落在 [staleThresholdMs] 内**。
 * - 没有任何资产 → false（空组合建出来的净资产 0 快照会腐蚀统计，见 dropLeadingEmptySnapshots 的 KDoc）；
 * - 从没同步过（lastSyncTime 全 0）→ false，宁可没有自动快照，也不要旧价假数据；
 * - 负债不参与判定——它们没有行情价，lastSyncTime 恒 0，算进去会把分子稀释。
 *
 * 这不是「价格必须最新」的硬门槛，而是「多数标的价格是近期刷过的」的软门槛：
 * 用户早上手动全量刷新过，晚上自动快照就能存；一整天没刷，就跳过。
 */
fun shouldCreateAutoSnapshot(
    assets: List<Asset>,
    now: Long,
    staleThresholdMs: Long = AUTO_SNAPSHOT_STALE_THRESHOLD_MS
): Boolean {
    val holdings = assets.filter { !it.type.isLiability }
    if (holdings.isEmpty()) return false
    val freshCount = holdings.count { it.lastSyncTime > 0 && now - it.lastSyncTime <= staleThresholdMs }
    return freshCount * 2 > holdings.size
}

/**
 * 快照表去重计划：同日多行时**保留 timestamp 最大的一条**（最后一次写入为准，
 * 与 createSnapshot「同日覆盖」的语义一致），返回其余待删行的 id 升序列表。
 *
 * 纯函数：只读入参列表做决策，删除动作由调用方执行——这样去重策略本身可被单测钉死，
 * 不需要 Robolectric 跑真库。
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
