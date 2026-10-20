package com.example.stockdiary.data.repository

import com.example.stockdiary.database.Asset
import com.example.stockdiary.utils.StockCodeManager

/**
 * 一次性修复「历史库里存在形态不规范的老三板（退市股）symbol」的数据。
 *
 * ## 背景
 *
 * 老三板代码段是 400xxx / 420xxx，在本 App 里没有任何行情源可覆盖，
 * [com.example.stockdiary.data.sync.AssetSyncManager] 在同步链路用
 * [com.example.stockdiary.utils.StockCodeManager.isDelistedForSync] **主动跳过**它们
 * （这是有意设计，不是 bug）。按项目硬约定，存库 symbol 一律应是
 * [com.example.stockdiary.utils.StockCodeManager.normalizeSymbol] 的结果，对老三板
 * 即 `bj400xxx`（如 `400264` → `bj400264`）。
 *
 * 但历史数据里可能存在形态不规范的退市 symbol：`sh400030`（带沪市前缀）、裸
 * `400030`（无前缀）。注意 `isDelistedForSync` 基于纯数字判定，这些形态都能被正确
 * 识别并跳过，所以同步不会炸；但 symbol 形态不统一会造成：快照对齐（按 symbol 精确
 * 分组）把同一只判成两只、持仓页市场分组错乱、分红/交易流水按 symbol 关联对不上等隐患。
 *
 * 因此这里把老三板 symbol 归一成规范 `bj400xxx`，完全照抄 [OtcSymbolRepair]
 * 的范式（同为「一次性启动修复 + 纯函数计算 + 碰撞跳过」），只是标的从场外基金换成
 * 老三板退市股。
 *
 * ## 修复规则
 *
 * 对 `isDelistedForSync` 判定为退市的 symbol（普通股票/基金/加密都不会命中），取其
 * 6 位纯数字并用 `normalizeSymbol` 归一成 `bj400xxx`；同时把 dividend / transactions
 * 表里同名记录一起改名，否则分红与交易流水会因为 symbol 对不上而整段失联。
 *
 * ### 碰撞保护
 *
 * 若规范形态已被另一条资产占用（说明库里已经同时存在两种形态），**跳过**该条：
 * 不覆盖、不合并（合并持仓与成本需用户确认，不该在启动时偷偷做），记一条警告留给
 * 用户手工处理。
 *
 * ### 幂等 & 纯函数
 *
 * 本函数是纯函数：输出只取决于入参，便于单测，也保证 repair 幂等 —— 已修过的库
 * 再跑一遍，[DelistedSymbolRepairPlan.updates] 为空。
 *
 * ### ⚠️ 归一必须走 `normalizeSymbol(extractDigits(symbol))`
 *
 * 不能写 `normalizeSymbol(symbol)`：后者对 `sh400030`（已带 `sh` 前缀）会走
 * 「已含市场前缀就原样返回」的短路，结果仍是 `sh400030`，归不正。先 `extractDigits`
 * 剥成纯 `400030` 再归一，才落得 `bj400030`。`normalizeSymbol("400030")` 同样正确
 * 落到 `bj400030`。
 */
fun computeDelistedSymbolRepairs(assets: List<Asset>): DelistedSymbolRepairPlan {
    // 库里现有 symbol 全集，用于碰撞检测（同一遍内复用，避免链式改名误判）
    val existingSymbols = assets.mapNotNull { it.symbol }.toSet()
    val updates = linkedMapOf<Long, String>()
    val skipped = mutableListOf<Long>()

    for (asset in assets) {
        val symbol = asset.symbol ?: continue
        // 只处理老三板退市股：isDelistedForSync 已排除 fund_ 场外份额、且要求 6 位
        // 400/420，所以基金/股票/加密都不会被误伤
        if (!StockCodeManager.isDelistedForSync(symbol)) continue
        val pure = StockCodeManager.extractDigits(symbol)
        if (pure.length != 6) continue // 双保险：非 6 位不参与归一
        // 必须用 normalizeSymbol(extractDigits(symbol))：先剥前缀再归一，否则
        // sh400030 会在 normalizeSymbol 里被「已带前缀就原样返回」短路成 sh400030
        val canonical = StockCodeManager.normalizeSymbol(pure)
        if (canonical == symbol) continue // 已是规范形态
        // 碰撞：规范形态已被另一条资产占用 → 跳过，绝不覆盖、不丢数据
        if (existingSymbols.contains(canonical)) {
            skipped.add(asset.id)
            continue
        }
        updates[asset.id] = canonical
    }
    return DelistedSymbolRepairPlan(updates = updates, skipped = skipped)
}

/**
 * [computeDelistedSymbolRepairs] 的产出。
 *
 * @param updates 需要改 symbol 的资产 id → `bj400xxx` 规范形态
 * @param skipped 因规范形态已被占用而跳过的资产 id（不覆盖、不丢数据）
 */
data class DelistedSymbolRepairPlan(
    val updates: Map<Long, String>,
    val skipped: List<Long>
)

/**
 * [AssetRepository.repairDelistedSymbolForms] 的产出摘要，便于日志与测试断言。
 *
 * @param updated     实际改了 symbol 的条数
 * @param skipped     因碰撞被跳过的条数
 * @param alreadyDone 本次是否因 SharedPreferences 守卫而直接跳过（true 表示以前已修过）
 */
data class DelistedRepairSummary(
    val updated: Int,
    val skipped: Int,
    val alreadyDone: Boolean
)
