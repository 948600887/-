package com.example.stockdiary.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.ui.components.DialogSectionHeader
import com.example.stockdiary.ui.components.DialogTypeChip
import com.example.stockdiary.utils.StockCodeManager

/**
 * 编辑资产弹窗。
 *
 * 与「添加资产」是孪生页面，原先两处各写各的，于是同一批问题各犯一遍
 * （底部错误提示压住按钮、键盘串不起来、校验逻辑写了两套）。
 * 现在字段构件、校验、构造口径全部复用 AddAssetForm / AssetInputValidation
 * 里同一套，两个页面行为不会再分叉。
 *
 * 仅保留一处差异：**类型选择这里股票与基金是两个独立 chip**。
 * 添加页把两者合并成「证券」是因为代码会自动识别归属；编辑页没有自动识别，
 * 用户必须能明确指定到底是哪一种。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditAssetDialog(
    asset: Asset,
    onDismiss: () -> Unit,
    onConfirm: (Asset) -> Unit
) {
    var selectedType by remember { mutableStateOf(asset.type) }
    var name by remember { mutableStateOf(asset.name) }
    var symbol by remember { mutableStateOf(asset.symbol ?: "") }
    var account by remember { mutableStateOf(asset.account) }
    var balance by remember { mutableStateOf(asset.balance.toString()) }
    var purchasePriceInput by remember { mutableStateOf(asset.purchasePrice.toString()) }
    var notes by remember { mutableStateOf(asset.notes ?: "") }
    var interestRateInput by remember { mutableStateOf(asset.interestRate?.toString() ?: "") }
    // 币种 / 到期日：一一对应改造新增，编辑时从原资产带出
    var currency by remember { mutableStateOf(asset.currency) }
    var dueDate by remember { mutableStateOf(asset.dueDate) }
    var isSaving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    // 焦点链：与添加页同样的处理，让键盘能一路走完
    val nameFocus = remember { FocusRequester() }
    val symbolFocus = remember { FocusRequester() }
    val accountFocus = remember { FocusRequester() }
    val balanceFocus = remember { FocusRequester() }
    val priceFocus = remember { FocusRequester() }
    val rateFocus = remember { FocusRequester() }
    val notesFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    val symbolVisible = selectedType == AssetType.STOCK ||
        selectedType == AssetType.FUND ||
        selectedType == AssetType.CRYPTO
    val isDeposit = selectedType == AssetType.BANK_DEPOSIT
    val isLiability = selectedType.isLiability
    // 与添加页同一套按类型精细化：参考单价仅证券+加密；年利率存款+贷款；到期日存款+负债
    val showPrice = selectedType == AssetType.STOCK ||
        selectedType == AssetType.FUND ||
        selectedType == AssetType.CRYPTO
    val showRate = isDeposit || selectedType == AssetType.LOAN
    val showDueDate = isDeposit || isLiability

    /** 下一个该聚焦的字段：跳过当前类型下不可见的那些 */
    fun focusAfter(field: String) {
        when (field) {
            "name" -> if (symbolVisible) symbolFocus else accountFocus
            "symbol" -> accountFocus
            "account" -> balanceFocus
            "balance" -> when {
                showPrice -> priceFocus
                showRate -> rateFocus
                else -> notesFocus
            }
            "price", "rate" -> notesFocus
            else -> return
        }.requestFocus()
    }

    /** 聚焦到指定字段本身（校验失败时用来定位到出错的那一项） */
    fun focusField(field: String) {
        when (field) {
            "name" -> nameFocus
            "symbol" -> symbolFocus
            "account" -> accountFocus
            "balance" -> balanceFocus
            "price" -> priceFocus
            "rate" -> rateFocus
            else -> return
        }.requestFocus()
    }

    // 「碰过」的字段才报错：编辑弹窗一打开字段都是有值的，只有用户改坏了才提示
    var touched by remember { mutableStateOf(setOf<String>()) }
    fun markTouched(field: String) { touched = touched + field }

    val allFields = setOf("name", "account", "balance", "symbol", "price", "rate")

    // 与添加页共用同一套校验。
    // rateRequired = false：编辑时利率留空表示「沿用原值」，不是「没填」。
    val errors by remember {
        derivedStateOf {
            validateAssetInput(
                selectedType = selectedType,
                name = name,
                symbol = symbol,
                account = account,
                balance = balance,
                purchasePrice = purchasePriceInput,
                interestRate = interestRateInput,
                rateRequired = false
            )
        }
    }

    /** 只在该字段已经被碰过时显示红字 */
    fun errorFor(field: String, error: FieldError?): String? =
        if (field in touched) error?.message else null

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.9f),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                AssetFormHeader(title = "编辑资产", onDismiss = onDismiss)

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    DialogSectionHeader("资产类型")

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(AssetType.STOCK, AssetType.FUND, AssetType.BANK_DEPOSIT).forEach { type ->
                                DialogTypeChip(
                                    type = type,
                                    selected = selectedType == type,
                                    onClick = {
                                        // 只有类型**真的发生变化**才重置币种：无条件重置的话，编辑
                                        // 美元/港币资产时误触已经选中的「股票」chip，币种就被静默冲回 CNY，
                                        // 保存后该资产按 1:1 折人民币 → 市值/盈亏全错。
                                        if (selectedType != type) {
                                            selectedType = type
                                            currency = defaultCurrencyForType(type)
                                            // 类型切换时同步归一代码，使界面代码与落库形态一致：
                                            // 证券/基金补市场前缀、加密货币去 /- 空白并转大写（#21）
                                            symbol = StockCodeManager.normalizeSymbolForType(type, symbol.trim())
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(AssetType.CRYPTO, AssetType.CREDIT_CARD, AssetType.LOAN).forEach { type ->
                                DialogTypeChip(
                                    type = type,
                                    selected = selectedType == type,
                                    onClick = {
                                        // 只有类型**真的发生变化**才重置币种：无条件重置的话，编辑
                                        // 美元/港币资产时误触已经选中的「股票」chip，币种就被静默冲回 CNY，
                                        // 保存后该资产按 1:1 折人民币 → 市值/盈亏全错。
                                        if (selectedType != type) {
                                            selectedType = type
                                            currency = defaultCurrencyForType(type)
                                            // 类型切换时同步归一代码，使界面代码与落库形态一致：
                                            // 证券/基金补市场前缀、加密货币去 /- 空白并转大写（#21）
                                            symbol = StockCodeManager.normalizeSymbolForType(type, symbol.trim())
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    DialogSectionHeader("基本信息")

                    AssetNameField(
                        name = name,
                        onNameChange = { name = it; markTouched("name") },
                        error = errorFor("name", errors.name),
                        focusRequester = nameFocus,
                        onNext = { focusAfter("name") }
                    )

                    if (symbolVisible) {
                        AssetSymbolField(
                            symbol = symbol,
                            onSymbolChange = { symbol = it; markTouched("symbol") },
                            selectedType = selectedType,
                            isLoading = false,
                            error = errorFor("symbol", errors.symbol),
                            focusRequester = symbolFocus,
                            onNext = { focusAfter("symbol") }
                        )
                    }

                    AssetAccountField(
                        account = account,
                        onAccountChange = { account = it; markTouched("account") },
                        expanded = false,
                        onExpandedChange = {},
                        filteredAccounts = emptyList(),
                        error = errorFor("account", errors.account),
                        focusRequester = accountFocus,
                        onNext = { focusAfter("account") }
                    )

                    DialogSectionHeader("交易信息")

                    AssetBalanceField(
                        balance = balance,
                        onBalanceChange = {
                            balance = sanitizeDecimalInput(it)
                            markTouched("balance")
                        },
                        selectedType = selectedType,
                        error = errorFor("balance", errors.balance),
                        focusRequester = balanceFocus,
                        onNext = { focusAfter("balance") }
                    )

                    // 币种：所有类型都显示
                    AssetCurrencyField(
                        currency = currency,
                        onCurrencyChange = { currency = it }
                    )

                    if (showPrice) {
                        AssetPriceField(
                            purchasePrice = purchasePriceInput,
                            onPriceChange = {
                                purchasePriceInput = sanitizeDecimalInput(it)
                                markTouched("price")
                            },
                            autoPrice = null,
                            error = errorFor("price", errors.price),
                            focusRequester = priceFocus,
                            onNext = { focusAfter("price") }
                        )
                    }

                    if (showRate) {
                        AssetInterestRateField(
                            interestRate = interestRateInput,
                            onRateChange = {
                                interestRateInput = sanitizeDecimalInput(it)
                                markTouched("rate")
                            },
                            error = errorFor("rate", errors.rate),
                            focusRequester = rateFocus,
                            onNext = { focusAfter("rate") }
                        )
                    }

                    if (showDueDate) {
                        AssetDueDateField(
                            dueDate = dueDate,
                            onDueDateChange = { dueDate = it }
                        )
                    }

                    DialogSectionHeader("备注")
                    AssetNotesField(
                        notes = notes,
                        onNotesChange = { notes = it },
                        focusRequester = notesFocus,
                        onDone = { focusManager.clearFocus() }
                    )
                }

                AssetFormActions(
                    onDismiss = onDismiss,
                    onConfirmClick = {
                        if (isSaving) return@AssetFormActions
                        touched = allFields
                        if (!errors.isValid) {
                            // 这里还没开始保存，不能说「保存失败」——原先正是这么写的
                            saveError = errors.summary
                            errors.firstField?.let { focusField(it) }
                            return@AssetFormActions
                        }
                        isSaving = true
                        saveError = null
                        // asset.copy() 会走 Asset.init 的 require(...)，主线程抛出就是整 App 崩溃
                        val updatedAsset = try {
                            // 代码必须走归一化，和添加页 / 交割单导入保持同一口径
                            // （全项目存库形态是 StockCodeManager.normalizeSymbol 的结果，如 600519 → sh600519）。
                            // 否则改过代码的资产在加仓、导入回放、分红匹配时会全部失配，
                            // 连「同名同账户持仓合并」也会失效——同一只票同一个账户出现两条持仓。
                            val normalizedSymbol = when (selectedType) {
                                AssetType.STOCK, AssetType.FUND ->
                                    StockCodeManager.normalizeSymbol(symbol.trim())
                                AssetType.CRYPTO -> symbol.trim()
                                // 存款 / 各类负债没有代码概念：从股票改过来时必须清掉残留的旧代码。
                                // 旧逻辑直接 `symbol.trim()`，于是改成存款/信用卡后仍带着 600519
                                // 这种无意义 symbol（后续同步、去重、展示都会被它干扰）。
                                else -> ""
                            }
                            asset.copy(
                                name = name,
                                type = selectedType,
                                symbol = normalizedSymbol.takeIf { it.isNotBlank() },
                                account = account,
                                balance = balance.toDoubleOrNull() ?: 0.0,
                                purchasePrice = if (isDeposit) {
                                    1.0
                                } else {
                                    // 留空表示沿用原值，与校验口径一致
                                    purchasePriceInput.toDoubleOrNull() ?: asset.purchasePrice
                                },
                                // 存款恒为 1。但反过来——从存款改成股票/基金时，
                                // asset.currentPrice 那个 1.0 是存款的占位值、不是真实行情价，
                                // 必须置空交给后续价格同步填充，否则市值会按 1 元/份算。
                                currentPrice = when {
                                    isDeposit -> 1.0
                                    asset.type == AssetType.BANK_DEPOSIT -> null
                                    else -> asset.currentPrice
                                },
                                // 利率：存款/贷款都可能有；留空表示沿用原值，与校验的 rateRequired = false 对应
                                interestRate = if (showRate) {
                                    interestRateInput.toDoubleOrNull() ?: asset.interestRate
                                } else {
                                    null
                                },
                                // 币种 / 到期日：一一对应改造新增写入
                                currency = currency,
                                dueDate = dueDate,
                                notes = notes.takeIf { it.isNotBlank() }
                            )
                        } catch (e: Exception) {
                            // 保留可操作性：把 loader 复位，否则按钮永久禁用且无提示
                            isSaving = false
                            saveError = "保存失败：${e.message ?: "资产数据不合法"}"
                            return@AssetFormActions
                        }
                        onConfirm(updatedAsset)
                    },
                    isSaving = isSaving,
                    saveError = saveError,
                    confirmLabel = "保存"
                )
            }
        }
    }
}
