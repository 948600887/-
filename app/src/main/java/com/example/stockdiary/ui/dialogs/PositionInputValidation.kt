package com.example.stockdiary.ui.dialogs

import com.example.stockdiary.database.AssetType

/**
 * 「加减仓」表单的纯校验逻辑。
 *
 * 抽出来的理由跟 [validateAssetInput] 一样：原先 AdjustPositionDialog 里只有一个
 * 笼统的 isValid，里面把数量的条件和单价的条件 `&&` 在一起算出一个布尔值，
 * 却把这个结果挂到**数量**字段的 isError 上。于是单价填错时，标红的是数量框、
 * 提示写的是「数量不合法」，而真正出错的单价字段一声不吭——用户照着提示改数量，
 * 怎么改都是红的。现在数量与单价各出各的错误，谁错谁红。
 *
 * 纯 Kotlin，不碰 Compose，可直接 JVM 单测。
 */

/** 加仓 / 减仓。用枚举而非裸字符串，避免 "加仓" 拼错后静默落进 else 分支。 */
enum class PositionAction { ADD, REDUCE }

/** [amount] 数量字段，[price] 成交单价字段；null 表示该字段没问题。 */
data class PositionInputErrors(
    val amount: FieldError? = null,
    val price: FieldError? = null
) {
    val isValid: Boolean
        get() = amount == null && price == null

    /** 第一个出错的字段名，用于聚焦并滚入可视区；全部合法则为 null */
    val firstField: String?
        get() = when {
            amount != null -> "amount"
            price != null -> "price"
            else -> null
        }

    /** 汇总提示，如「请检查：数量、成交单价」；全部合法则为 null */
    val summary: String?
        get() = listOfNotNull(amount, price)
            .takeIf { it.isNotEmpty() }
            ?.joinToString("、") { it.label }
            ?.let { "请检查：$it" }
}

/** 数量字段在不同类型下的叫法，错误提示要跟界面上的标签保持一致。 */
internal fun quantityLabel(type: AssetType): String = when (type) {
    AssetType.STOCK -> "买卖数量"
    AssetType.FUND -> "买卖份额"
    else -> "数量"
}

/**
 * 校验加减仓输入。
 *
 * @param currentBalance 当前持仓数量，减仓时用于判断有没有减超
 * @param label 数量字段的叫法，见 [quantityLabel]
 */
fun validatePositionInput(
    action: PositionAction,
    amount: String,
    price: String,
    currentBalance: Double,
    label: String = "数量"
): PositionInputErrors {
    val quantity = amount.toDoubleOrNull()
    val priceValue = price.toDoubleOrNull()

    return PositionInputErrors(
        amount = when {
            // 刚打开时输入框是空的，先不报错，等用户动过或点了确认再说
            quantity == null -> FieldError(label, "请输入有效数字")
            quantity <= 0 -> FieldError(label, "必须大于 0")
            // 减仓减到等于持仓会把这条资产整条删掉（仓储层 quantity >= balance 即删除），
            // 那不是「减仓」，应该走「删除资产」。这里直接判为不合法。
            action == PositionAction.REDUCE && quantity > currentBalance ->
                FieldError(label, "减仓数量不能超过持仓 ${currentBalance.formatQuantity()}")
            action == PositionAction.REDUCE && quantity == currentBalance ->
                FieldError(label, "会清空该持仓（等于删除资产），若要清仓请用删除")
            else -> null
        },

        // 成交单价是可选的：留空就用当前参考价，但填了就得是合法的非负数。
        // 允许 0 —— 送股/继承这类零成本获得是真实存在的场景。
        price = when {
            price.isBlank() -> null
            priceValue == null -> FieldError("成交单价", "请输入有效数字")
            priceValue < 0 -> FieldError("成交单价", "不能为负数")
            else -> null
        }
    )
}

/** 持仓数量在提示里的写法：整数就不拖小数点，避免「减仓数量不能超过持仓 1000.0」。 */
private fun Double.formatQuantity(): String =
    if (this % 1.0 == 0.0) this.toLong().toString() else this.toString()
