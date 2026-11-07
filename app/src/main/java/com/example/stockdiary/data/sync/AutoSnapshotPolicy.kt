package com.example.stockdiary.data.sync

import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType

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
 */

/** 价格「还算新鲜」的窗口：24 小时。手动刷新一般每天一次，日内自动同步的用户价格总是新的 */
const val AUTO_SNAPSHOT_STALE_THRESHOLD_MS: Long = 24 * 60 * 60_000L

/**
 * 这次自动快照该不该建。
 *
 * 规则：**过半（严格多于一半）有行情资产的 `lastSyncTime` 落在 [staleThresholdMs] 内**。
 * - 没有任何非负债资产 → false（空组合建出来的净资产 0 快照会腐蚀统计，见 dropLeadingEmptySnapshots 的 KDoc）；
 * - **只有银行存款等无行情资产 → true**：它们的金额是静态的，不存在“旧价”，
 *   若按 `lastSyncTime` 判会被永远判成陈旧，导致存款较多的组合长期没有自动快照；
 * - 有行情资产从没同步过（lastSyncTime 全 0）→ false，宁可没有自动快照，也不要旧价假数据；
 * - 负债不参与判定——它们没有行情价，lastSyncTime 恒 0，算进去会把分母稀释。
 *
 * 这不是「价格必须最新」的硬门槛，而是「多数有行情标的价格是近期刷过的」的软门槛：
 * 用户早上手动全量刷新过，晚上自动快照就能存；一整天没刷，就跳过。
 */
fun shouldCreateAutoSnapshot(
    assets: List<Asset>,
    now: Long,
    staleThresholdMs: Long = AUTO_SNAPSHOT_STALE_THRESHOLD_MS
): Boolean {
    val holdings = assets.filter { !it.type.isLiability }
    if (holdings.isEmpty()) return false
    // 只统计真正有行情的类型。存款/其它静态资产没有 lastSyncTime，
    // 把它们放进分母会让“达标线”永远够不到（典型：1 笔存款 + 1 只股票，股票刷新了仍判 false）。
    val priceSynced = holdings.filter { it.type in AssetType.getPriceSyncedTypes() }
    if (priceSynced.isEmpty()) return true
    val freshCount = priceSynced.count { it.lastSyncTime > 0 && now - it.lastSyncTime <= staleThresholdMs }
    return freshCount * 2 > priceSynced.size
}
