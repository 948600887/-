package com.example.stockdiary.data.repository

import com.example.stockdiary.database.Asset
import com.example.stockdiary.utils.StockCodeManager

/**
 * 一次性修复「同一只场外基金在库里存成两种形态」的历史数据。
 *
 * ## 背景
 *
 * [StockCodeManager.normalizeSymbol] 从前对场外号段的纯数字代码**原样返回**
 * （`004502`），而基金E账户导入与基金持仓截图一律拼 `fund_` 前缀（`fund_004502`）。
 * 两种形态 [StockCodeManager.isOTCFund] 都认，但字符串不相等 —— 快照对齐按 symbol
 * 精确分组，于是同一只基金被判成两只：落两条资产、持仓翻倍，还分别显示在「场内/
 * 场外」两个分组里（持仓页按前缀分市场）。
 *
 * 现在 normalizeSymbol 统一落成 `fund_` 形态，**新数据不会再分裂**；这里修的是
 * 已经按纯数字落库的历史数据。
 *
 * ## 修复规则
 *
 * 对 symbol 的 6 位纯数字属于场外号段（[StockCodeManager.isOTCFundCode]）且尚未带
 * `fund_` 前缀的资产，改写为 `fund_` + 纯码；同时把它在 dividend / transactions
 * 表里的 symbol 一起改过去，否则分红与交易流水会因为 symbol 对不上而整段失联。
 *
 * ### 碰撞保护
 *
 * 若规范形态已被另一条资产占用（说明用户库里已经同时存在两种形态 —— 正是这个 bug
 * 的产物），**跳过**该条：不覆盖、不合并（合并持仓与成本需要用户确认，不该在启动
 * 时偷偷做），记一条警告留给用户手工处理。
 *
 * ## 幂等 & 纯函数
 *
 * 本函数是纯函数：输出只取决于入参，便于单测，也保证 repair 幂等 —— 已修过的库
 * 再跑一遍，[OtcSymbolRepairPlan.updates] 为空。
 */
fun computeOtcSymbolRepairs(assets: List<Asset>): OtcSymbolRepairPlan {
    // 库里现有 symbol 全集，用于碰撞检测（同一遍内复用，避免链式改名误判）
    val existingSymbols = assets.mapNotNull { it.symbol }.toSet()
    val updates = linkedMapOf<Long, String>()
    val skipped = mutableListOf<Long>()

    for (asset in assets) {
        val symbol = asset.symbol ?: continue
        // 已经是规范（或显式标记）形态的不用动
        if (StockCodeManager.hasFundPrefix(symbol)) continue
        val pure = StockCodeManager.extractDigits(symbol)
        // 只有号段明确属于场外的才改：LOF 的场内外份额（sz163415 / fund_163415）
        // 与老三板（400030 既是场外基金号段也是退市股票号段）刻意保持两态
        if (pure.length != 6 || !StockCodeManager.isOTCFundCode(pure)) continue
        val canonical = StockCodeManager.FUND_PREFIX + pure
        if (canonical == symbol) continue
        // 碰撞：规范形态已被另一条资产占用 → 跳过，绝不覆盖、不丢数据
        if (existingSymbols.contains(canonical)) {
            skipped.add(asset.id)
            continue
        }
        updates[asset.id] = canonical
    }
    return OtcSymbolRepairPlan(updates = updates, skipped = skipped)
}

/**
 * [computeOtcSymbolRepairs] 的产出。
 *
 * @param updates 需要改 symbol 的资产 id → `fund_` 前缀形态
 * @param skipped 因规范形态已被占用而跳过的资产 id（不覆盖、不丢数据）
 */
data class OtcSymbolRepairPlan(
    val updates: Map<Long, String>,
    val skipped: List<Long>
)

/**
 * [AssetRepository.repairOtcSymbolForms] 的产出摘要，便于日志与测试断言。
 *
 * @param updated  实际改了 symbol 的条数
 * @param skipped  因碰撞被跳过的条数
 * @param alreadyDone 本次是否因 SharedPreferences 守卫而直接跳过（true 表示以前已修过）
 */
data class OtcRepairSummary(
    val updated: Int,
    val skipped: Int,
    val alreadyDone: Boolean
)
