package com.example.stockdiary.data.repository

import com.example.stockdiary.database.Asset
import com.example.stockdiary.network.BinanceAPI

/**
 * 一次性修复已按错误符号落库的 LD 锁仓脏符号。
 *
 * ## 背景
 *
 * 旧版币安导入把 `LDBCH` 这类锁仓币落库成 `LDBCHUSDT`（[com.example.stockdiary.data.importer.baseAssetToSymbol]
 * 盲加 USDT），但币安根本没有这个交易对，导致之后每次行情同步都拿它问币安 → 永远 400。
 * 导入路径的修复见 [com.example.stockdiary.data.importer.BinanceImportService]；这里修
 * **已经按错符号落库**的历史数据。
 *
 * ## 修复规则
 *
 * 对所有 `symbol` 被 [BinanceAPI.isEarnProduct] 认作理财/锁仓产品（即「以 LD 开头 &&
 * 非例外如 LDO && 长度>2」，见 [com.example.stockdiary.network.BINANCE_LD_EXCEPTIONS]）的
 * 资产，用 [BinanceAPI.earnUnderlyingPair] 算底层交易对；若非 null 且 ≠ 当前 symbol，
 * 就把它改成底层（如 `LDBCHUSDT` → `BCHUSDT`）。[BinanceAPI.earnUnderlyingPair] 对
 * `LDBCHUSDT` 也能正确剥 LD（isEarnProduct 认 `LD` 前缀），所以直接喂存储的脏符号即可，
 * 不用先反推原始资产名。
 *
 * ### 碰撞保护
 *
 * 若目标底层 symbol 已存在另一条资产，跳过该条（记日志说明、不覆盖、不丢数据）。
 *
 * ## 幂等 & 纯函数
 *
 * 本函数是纯函数：输出只取决于入参，便于单测，也保证 repair 幂等——已修过的库再跑
 * 一遍，[LdSymbolRepairPlan.updates] 为空，不会重复改动。
 *
 * @param assets 待检查的资产列表（调用方应传入加密货币资产；非 LD 的不会被改动）
 * @return [LdSymbolRepairPlan]：需要改 symbol 的资产 id → 底层交易对（updates），
 *         以及因碰撞被跳过的资产 id（skipped）
 */
fun computeLdRepairs(assets: List<Asset>): LdSymbolRepairPlan {
    // 当前库里所有 symbol 的集合，用于碰撞检测（同一遍内复用，避免链式改名误判）
    val existingSymbols = assets.mapNotNull { it.symbol }.toSet()
    val updates = linkedMapOf<Long, String>()
    val skipped = mutableListOf<Long>()

    for (asset in assets) {
        val symbol = asset.symbol ?: continue
        // 只用 isEarnProduct 判定：它已编码「以 LD 开头 && 非例外(LDO) && 长度>2」
        if (!BinanceAPI.isEarnProduct(symbol)) continue
        val underlying = BinanceAPI.earnUnderlyingPair(symbol) ?: continue
        if (underlying == symbol) continue
        // 碰撞：底层交易对已被另一条资产占用 → 跳过，绝不覆盖、不丢数据
        if (existingSymbols.contains(underlying)) {
            skipped.add(asset.id)
            continue
        }
        updates[asset.id] = underlying
    }
    return LdSymbolRepairPlan(updates = updates, skipped = skipped)
}

/**
 * [computeLdRepairs] 的产出。
 *
 * @param updates 需要改 symbol 的资产 id → 底层交易对
 * @param skipped 因目标底层 symbol 已被占用而跳过的资产 id（不覆盖、不丢数据）
 */
data class LdSymbolRepairPlan(
    val updates: Map<Long, String>,
    val skipped: List<Long>
)

/**
 * [AssetRepository.repairLdDirtySymbols] 的产出摘要，便于日志与测试断言。
 *
 * @param updated  实际改了 symbol 的条数
 * @param skipped  因碰撞被跳过的条数
 * @param alreadyDone 本次是否因 SharedPreferences 守卫而直接跳过（true 表示以前已修过）
 */
data class LdRepairSummary(
    val updated: Int,
    val skipped: Int,
    val alreadyDone: Boolean
)
