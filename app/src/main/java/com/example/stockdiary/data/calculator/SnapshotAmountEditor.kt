package com.example.stockdiary.data.calculator

import com.example.stockdiary.database.AssetSnapshot
import java.math.BigDecimal

/**
 * 快照编辑的五个分类金额输入。负债同样用正数录入，与库内 `totalLiabilities` 口径一致
 * （`AssetSnapshot.fromAssets` 里 `sumDebtValueCny()` 存的就是正数）。
 */
data class SnapshotAmountInput(
    val stock: Double = 0.0,
    val fund: Double = 0.0,
    val crypto: Double = 0.0,
    val deposit: Double = 0.0,
    val liabilities: Double = 0.0
)

/** 二次确认里的一行「修改前 → 修改后」 */
data class SnapshotAmountChange(
    val label: String,
    val oldValue: Double,
    val newValue: Double
)

/**
 * 快照手动修正的纯计算：重算汇总、生成确认列表、判断是否零改动。
 *
 * 只写 `stockValue` / `fundValue` / `cryptoValue` / `bankDepositValue` /
 * `totalLiabilities` / `totalAssets` / `netAssets` 七个字段。
 * `totalCost`/`totalProfit`/`yieldRate` 与各分类 Cost 是历史遗留列（界面、统计、
 * 导出都不读取），编辑时保持原值，不按比例编造成本；`date`/`timestamp` 也不动，
 * 快照日期不可改、创建时刻不刷新。
 */
object SnapshotAmountEditor {

    /** 五项资产全为 0 时拒绝保存的提示。全 0 会被「丢弃前导空快照」规则从图表里隐藏 */
    const val AT_LEAST_ONE_ASSET_MESSAGE = "至少填写一项资产金额"

    fun inputOf(snapshot: AssetSnapshot): SnapshotAmountInput = SnapshotAmountInput(
        stock = snapshot.stockValue,
        fund = snapshot.fundValue,
        crypto = snapshot.cryptoValue,
        deposit = snapshot.bankDepositValue,
        liabilities = snapshot.totalLiabilities
    )

    /**
     * 输入框初值：用十进制原样输出，不用 `String.format("%.2f")`。
     * 两位小数会把手里的 1234.5678 抹成 1234.57，用户什么都没改点保存也变成了修改。
     */
    fun amountText(value: Double): String =
        BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

    /** 空串按 0 处理；负数、非数字、NaN/Infinity 一律视为非法 */
    fun parseAmount(text: String): Double? {
        if (text.isBlank()) return 0.0
        val value = text.trim().toDoubleOrNull() ?: return null
        return value.takeIf { it.isFinite() && it >= 0 }
    }

    fun totalAssetsOf(input: SnapshotAmountInput): Double =
        input.stock + input.fund + input.crypto + input.deposit

    fun netAssetsOf(input: SnapshotAmountInput): Double =
        totalAssetsOf(input) - input.liabilities

    fun isValid(input: SnapshotAmountInput): Boolean = totalAssetsOf(input) > 0.0

    /** 用编辑值重算汇总；价格/成本/日期等其余字段原样保留 */
    fun apply(snapshot: AssetSnapshot, input: SnapshotAmountInput): AssetSnapshot {
        val totalAssets = totalAssetsOf(input)
        return snapshot.copy(
            stockValue = input.stock,
            fundValue = input.fund,
            cryptoValue = input.crypto,
            bankDepositValue = input.deposit,
            totalLiabilities = input.liabilities,
            totalAssets = totalAssets,
            netAssets = totalAssets - input.liabilities
        )
    }

    /** 编辑值与原值完全一致（含汇总）时视为零改动，不写库、不弹确认 */
    fun hasChanges(snapshot: AssetSnapshot, input: SnapshotAmountInput): Boolean =
        apply(snapshot, input) != snapshot

    /** 二次确认要逐项列出的七行：五个分类 + 总资产 + 净资产 */
    fun changes(snapshot: AssetSnapshot, input: SnapshotAmountInput): List<SnapshotAmountChange> {
        val updated = apply(snapshot, input)
        return listOf(
            SnapshotAmountChange("股票", snapshot.stockValue, updated.stockValue),
            SnapshotAmountChange("基金", snapshot.fundValue, updated.fundValue),
            SnapshotAmountChange("加密货币", snapshot.cryptoValue, updated.cryptoValue),
            SnapshotAmountChange("存款", snapshot.bankDepositValue, updated.bankDepositValue),
            SnapshotAmountChange("负债", snapshot.totalLiabilities, updated.totalLiabilities),
            SnapshotAmountChange("总资产", snapshot.totalAssets, updated.totalAssets),
            SnapshotAmountChange("净资产", snapshot.netAssets, updated.netAssets)
        )
    }
}
