package com.example.stockdiary.ui.dialogs

import com.example.stockdiary.database.AssetType

/**
 * 「添加资产」表单的纯校验逻辑。
 *
 * 抽出来的理由：原先校验分散在两处且各写各的——
 * AddAssetDialog 里有一个 isValid（决定能不能提交），每个字段构件里又各有一套
 * isError/supportingText 判断（决定红不红、提示什么）。两处判断一旦漂移，
 * 就会出现「按钮能点但字段标红」或者反过来。现在统一由 [validateAssetInput] 出结果，
 * [isValid] 和各字段的红字都读同一份，不可能再打架。
 *
 * 这里是纯 Kotlin，不碰 Compose，可以直接在 JVM 上单测。
 */

/** 单个字段的错误：[label] 用于汇总提示（"请检查：资产名称"），[message] 用于字段下方的红字。 */
data class FieldError(val label: String, val message: String)

/**
 * 各字段的校验结果，null 表示该字段没问题。
 * 字段顺序即界面上的自上而下顺序，[firstField] 依此决定该滚到/聚焦哪一个。
 */
data class AssetInputErrors(
    val name: FieldError? = null,
    val symbol: FieldError? = null,
    val account: FieldError? = null,
    val balance: FieldError? = null,
    val price: FieldError? = null,
    val rate: FieldError? = null
) {
    val isValid: Boolean
        get() = name == null && symbol == null && account == null &&
            balance == null && price == null && rate == null

    /** 第一个出错的字段名，用于聚焦并滚入可视区；全部合法则为 null */
    val firstField: String?
        get() = when {
            name != null -> "name"
            symbol != null -> "symbol"
            account != null -> "account"
            balance != null -> "balance"
            price != null -> "price"
            rate != null -> "rate"
            else -> null
        }

    /** 汇总提示，如「请检查：资产名称、账户/平台」；全部合法则为 null */
    val summary: String?
        get() = listOfNotNull(name, symbol, account, balance, price, rate)
            .takeIf { it.isNotEmpty() }
            ?.joinToString("、") { it.label }
            ?.let { "请检查：$it" }
}

/** 需要行情价格的资产类型：没有代码就永远同步不到价格，也不参与导入回放/持仓合并/分红匹配。 */
internal fun isSymbolRequired(type: AssetType): Boolean =
    type == AssetType.STOCK || type == AssetType.FUND || type == AssetType.CRYPTO

/** 数量字段在不同类型下的叫法，错误提示要跟界面标签保持一致。 */
internal fun balanceLabel(type: AssetType): String = when (type) {
    AssetType.STOCK -> "持股数量"
    AssetType.FUND -> "持有份额"
    AssetType.CRYPTO -> "持有数量"
    AssetType.BANK_DEPOSIT -> "存款金额"
    AssetType.CREDIT_CARD -> "欠款金额"
    AssetType.LOAN -> "贷款余额"
    else -> "数量/金额"
}

/**
 * 校验表单输入。
 *
 * 与 [buildAssetFromInput] 的构造规则、以及 [com.example.stockdiary.database.Asset]
 * init 里的 require(...) 保持同一套口径，避免「界面说合法、构造时却抛异常」。
 */
fun validateAssetInput(
    selectedType: AssetType,
    name: String,
    symbol: String,
    account: String,
    balance: String,
    purchasePrice: String,
    interestRate: String,
    /**
     * 利率是否必填。新增资产时必须填（没有利率算不出到期收益）；
     * 编辑已有资产时留空表示「沿用原值」，所以传 false。
     */
    rateRequired: Boolean = true
): AssetInputErrors {
    val isDeposit = selectedType == AssetType.BANK_DEPOSIT
    // 参考单价只对"数量×单价=市值"的类型有意义：证券 + 加密货币。存款单价恒 1、负债按 balance 计,不适用。
    val priceApplies = selectedType == AssetType.STOCK ||
        selectedType == AssetType.FUND ||
        selectedType == AssetType.CRYPTO
    // 利率:存款(必填)与贷款(可选)都可能有
    val rateApplies = isDeposit || selectedType == AssetType.LOAN
    val balanceValue = balance.toDoubleOrNull()
    val priceValue = purchasePrice.toDoubleOrNull()
    val rateValue = interestRate.toDoubleOrNull()
    val qty = balanceLabel(selectedType)

    return AssetInputErrors(
        name = if (name.isBlank()) FieldError("资产名称", "必填") else null,

        symbol = if (isSymbolRequired(selectedType) && symbol.isBlank()) {
            FieldError("代码", "请填写代码，否则无法同步行情价格")
        } else {
            null
        },

        account = if (account.isBlank()) FieldError("账户/平台", "必填") else null,

        balance = when {
            balanceValue == null -> FieldError(qty, "请输入有效数字")
            // Asset 的 init 里有 require(balance >= 0)，这里必须同口径挡在前面
            balanceValue < 0 -> FieldError(qty, "不能为负数")
            else -> null
        },

        // 参考单价是可选的：空着不报错，但填了就得是合法非负数；仅对有"单价"的类型生效
        price = when {
            !priceApplies -> null
            purchasePrice.isBlank() -> null
            priceValue == null -> FieldError("参考单价", "请输入有效数字")
            priceValue < 0 -> FieldError("参考单价", "不能为负数")
            else -> null
        },

        // 存款/贷款的利率：存款新增时必填（没有利率就算不出到期收益/还款额），
        // 贷款选填；编辑时留空是合法的——表示「不改这一项」，回落到原值。
        rate = when {
            !rateApplies -> null
            interestRate.isBlank() -> if (rateRequired) {
                FieldError("年利率", "必填，请输入有效数字")
            } else {
                null
            }
            rateValue == null -> FieldError("年利率", "请输入有效数字")
            rateValue < 0 -> FieldError("年利率", "不能为负数")
            else -> null
        }
    )
}

/**
 * 把输入收敛成合法的小数串：只保留数字与**第一个**小数点，其余字符一律丢弃。
 *
 * 用于数量/单价/利率，挡掉 "1.2.3"、"12-"、"--3" 这类键盘能打出来但毫无意义的输入。
 * 注意是过滤而非拒绝——用户粘贴 "1,234.56" 会得到 "1234.56"，比整段拒绝更顺手。
 */
fun sanitizeDecimalInput(input: String): String {
    var seenDot = false
    return buildString(input.length) {
        for (ch in input) {
            when {
                ch.isDigit() -> append(ch)
                ch == '.' && !seenDot -> {
                    seenDot = true
                    append(ch)
                }
            }
        }
    }
}
