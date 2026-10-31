package com.example.stockdiary.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.data.repository.FundQuote
import com.example.stockdiary.data.repository.StockQuote
import com.example.stockdiary.ui.components.DialogSectionHeader
import com.example.stockdiary.ui.components.DialogTypeChip
import com.example.stockdiary.ui.theme.AppTheme
import com.example.stockdiary.utils.ExchangeRateManager
import com.example.stockdiary.utils.StockCodeManager
import com.example.stockdiary.utils.StockCodeManager.CodeCategory

/**
 * 「添加资产」弹窗的表单构件与提交逻辑。
 *
 * 原先这些都内联在 AddAssetDialog 单个 660 行的 composable 里，任何一个字段的改动
 * 都要在几百行里翻找。这里按职责拆开：每个字段一个 composable、参数显式传入，
 * 提交构造 [buildAssetFromInput] 与校验 [validateAssetInput] 都抽成纯函数（可直接单测）。
 *
 * 字段构件的约定：
 * - [error] 由外部统一算好传入（来自 validateAssetInput），构件自己不再重复判断，
 *   免得「按钮能点但字段标红」这类两处逻辑漂移。
 * - [focusRequester] + [onNext] 把键盘串起来：输完一个字段能直接跳下一个，
 *   而不是每填一项都要手点屏幕。
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AssetFormHeader(
    title: String,
    onDismiss: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "关闭",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AssetTypePicker(
    selectedType: AssetType,
    onSelectType: (AssetType) -> Unit
) {
    DialogSectionHeader("资产类型")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 证券：合并股票+基金，输入代码后自动识别
            val isSecuritiesSelected =
                selectedType == AssetType.STOCK || selectedType == AssetType.FUND
            DialogTypeChip(
                type = AssetType.STOCK,
                selected = isSecuritiesSelected,
                onClick = { if (!isSecuritiesSelected) onSelectType(AssetType.STOCK) },
                customLabel = "证券",
                modifier = Modifier.weight(1f)
            )
            DialogTypeChip(
                type = AssetType.BANK_DEPOSIT,
                selected = selectedType == AssetType.BANK_DEPOSIT,
                onClick = { onSelectType(AssetType.BANK_DEPOSIT) },
                modifier = Modifier.weight(1f)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DialogTypeChip(
                type = AssetType.CRYPTO,
                selected = selectedType == AssetType.CRYPTO,
                onClick = { onSelectType(AssetType.CRYPTO) },
                modifier = Modifier.weight(1f)
            )
            DialogTypeChip(
                type = AssetType.CREDIT_CARD,
                selected = selectedType == AssetType.CREDIT_CARD,
                onClick = { onSelectType(AssetType.CREDIT_CARD) },
                modifier = Modifier.weight(1f)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DialogTypeChip(
                type = AssetType.LOAN,
                selected = selectedType == AssetType.LOAN,
                onClick = { onSelectType(AssetType.LOAN) },
                modifier = Modifier.weight(1f)
            )
            DialogTypeChip(
                type = AssetType.MORTGAGE,
                selected = selectedType == AssetType.MORTGAGE,
                onClick = { onSelectType(AssetType.MORTGAGE) },
                modifier = Modifier.weight(1f)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DialogTypeChip(
                type = AssetType.CAR_LOAN,
                selected = selectedType == AssetType.CAR_LOAN,
                onClick = { onSelectType(AssetType.CAR_LOAN) },
                modifier = Modifier.weight(1f)
            )
            DialogTypeChip(
                type = AssetType.OTHER_LIABILITY,
                selected = selectedType == AssetType.OTHER_LIABILITY,
                onClick = { onSelectType(AssetType.OTHER_LIABILITY) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AssetNameField(
    name: String,
    onNameChange: (String) -> Unit,
    error: String?,
    focusRequester: FocusRequester,
    onNext: () -> Unit
) {
    OutlinedTextField(
        value = name,
        onValueChange = onNameChange,
        label = { Text("资产名称") },
        placeholder = { Text("例如：贵州茅台、易方达消费精选") },
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { onNext() })
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AssetSymbolField(
    symbol: String,
    onSymbolChange: (String) -> Unit,
    selectedType: AssetType,
    isLoading: Boolean,
    error: String?,
    focusRequester: FocusRequester,
    onNext: () -> Unit
) {
    OutlinedTextField(
        value = symbol,
        onValueChange = onSymbolChange,
        label = {
            when (selectedType) {
                AssetType.CRYPTO -> Text("交易对")
                AssetType.FUND -> Text("基金代码")
                AssetType.STOCK -> Text("股票代码")
                else -> Text("代码")
            }
        },
        // 编辑弹窗复用本构件时不会传占位符语义，placeholder 只保留「自动识别」提示：
        // 编辑场景没有自动查询，但这句提示本身无害，且两处文案保持一致更省事。
        placeholder = {
            when (selectedType) {
                AssetType.CRYPTO -> Text("例如：BTC/USDT")
                else -> Text("输入代码自动识别：600519/510050/007467...")
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Next
        ),
        keyboardActions = KeyboardActions(onNext = { onNext() }),
        trailingIcon = {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
            }
        }
    )
}

/** 自动识别结果标签：告诉用户这段代码被判定成了什么 */
@Composable
internal fun DetectedCategoryLabel(category: CodeCategory) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "识别为 ",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background((if (AppTheme.colors.isDark) category.colorDark else category.color).copy(alpha = 0.15f))
                .padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            Text(
                text = category.label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = if (AppTheme.colors.isDark) category.colorDark else category.color
            )
        }
    }
}

/** 代码查询的三种结果：命中股票 / 命中基金 / 出错 */
@Composable
internal fun LookupResultCards(
    stockInfo: StockQuote?,
    fundInfo: FundQuote?,
    loadError: String?,
    category: CodeCategory?
) {
    stockInfo?.let {
        Spacer(modifier = Modifier.height(4.dp))
        StockInfoCard(info = it, category = category)
    }
    fundInfo?.let {
        Spacer(modifier = Modifier.height(4.dp))
        FundInfoCard(info = it)
    }
    loadError?.let {
        Spacer(modifier = Modifier.height(4.dp))
        ErrorCard(message = it)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AssetAccountField(
    account: String,
    onAccountChange: (String) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    filteredAccounts: List<String>,
    error: String?,
    focusRequester: FocusRequester,
    onNext: () -> Unit
) {
    ExposedDropdownMenuBox(
        expanded = expanded && filteredAccounts.isNotEmpty(),
        onExpandedChange = onExpandedChange
    ) {
        OutlinedTextField(
            value = account,
            onValueChange = {
                onAccountChange(it)
                onExpandedChange(true)
            },
            label = { Text("账户/平台") },
            placeholder = { Text("选择或输入：招商证券、支付宝等") },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .menuAnchor(),
            singleLine = true,
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { onNext() }),
            // 没有任何历史账户时（编辑弹窗就没有下拉数据源）不显示下拉箭头，
            // 否则一个点不开的箭头只会让人以为是坏了
            trailingIcon = if (filteredAccounts.isEmpty()) {
                null
            } else {
                {
                    ExposedDropdownMenuDefaults.TrailingIcon(
                        expanded = expanded && filteredAccounts.isNotEmpty()
                    )
                }
            }
        )

        ExposedDropdownMenu(
            expanded = expanded && filteredAccounts.isNotEmpty(),
            onDismissRequest = { onExpandedChange(false) }
        ) {
            filteredAccounts.forEach { acc ->
                DropdownMenuItem(
                    text = { Text(acc) },
                    onClick = {
                        onAccountChange(acc)
                        onExpandedChange(false)
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AssetBalanceField(
    balance: String,
    onBalanceChange: (String) -> Unit,
    selectedType: AssetType,
    error: String?,
    focusRequester: FocusRequester,
    onNext: () -> Unit
) {
    OutlinedTextField(
        value = balance,
        onValueChange = onBalanceChange,
        label = { Text(balanceLabel(selectedType)) },
        placeholder = { Text("请输入数字") },
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Decimal,
            imeAction = ImeAction.Next
        ),
        keyboardActions = KeyboardActions(onNext = { onNext() }),
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it) } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AssetPriceField(
    purchasePrice: String,
    onPriceChange: (String) -> Unit,
    autoPrice: Double?,
    error: String?,
    focusRequester: FocusRequester,
    onNext: () -> Unit,
    /**
     * 是否真的由行情自动填充且用户还没改。仅当 [purchasePrice] 仍等于 [autoPrice] 时为 true，
     * 否则（用户手动改过 / 清空了）就不显示"已按行情价自动填充"，避免误导（#10）。
     */
    isAutoFilled: Boolean = false
) {
    OutlinedTextField(
        value = purchasePrice,
        onValueChange = onPriceChange,
        label = { Text("参考单价") },
        placeholder = { Text("可选，用于无行情时估算市值") },
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Decimal,
            imeAction = ImeAction.Next
        ),
        keyboardActions = KeyboardActions(onNext = { onNext() }),
        singleLine = true,
        isError = error != null,
        // 自动填充价原先写在 placeholder 里：用户一填值提示就消失了，等于白给。
        // 改到 supportingText 常驻，且让位于错误提示。仅在价格仍等于行情自动填充值时
        // 才提示，用户改过/清空后提示消失（#10）。
        supportingText = when {
            error != null -> { { Text(error) } }
            isAutoFilled -> {
                {
                    Text(
                        "已按行情价 $autoPrice 自动填充，可手动修改",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            else -> null
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AssetInterestRateField(
    interestRate: String,
    onRateChange: (String) -> Unit,
    error: String?,
    focusRequester: FocusRequester,
    onNext: () -> Unit
) {
    OutlinedTextField(
        value = interestRate,
        onValueChange = onRateChange,
        label = { Text("年利率 (%)") },
        placeholder = { Text("例如：3.5") },
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Decimal,
            imeAction = ImeAction.Next
        ),
        keyboardActions = KeyboardActions(onNext = { onNext() }),
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it) } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
/**
 * 币种选择。
 *
 * 一一对应改造新增：Asset 本就有 currency 字段（默认 CNY），但添加/编辑页一直没暴露，
 * 于是加密货币（美元价）、港股（港币价）的市值被按人民币口径算，totalValueCny 全错。
 *
 * 币种列表直接对齐 [ExchangeRateManager.supportedCurrencies]（内置 10 种汇率），
 * 不再硬编码 CNY/USD/HKD —— 否则日元/欧元等资产只能选 CNY，被按 1:1 折算，
 * 总资产凭空虚高（如 100 万日元按人民币算）。下拉展示「代码 + 中文名」。
 */
@Composable
internal fun AssetCurrencyField(
    currency: String,
    onCurrencyChange: (String) -> Unit
) {
    val options = remember { ExchangeRateManager.supportedCurrencies() }
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(
            text = "币种",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it }
        ) {
            OutlinedTextField(
                value = "$currency  ${ExchangeRateManager.getDisplayName(currency)}",
                onValueChange = {},
                readOnly = true,
                label = { Text("币种") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
                singleLine = true
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { code ->
                    DropdownMenuItem(
                        text = { Text("$code  ${ExchangeRateManager.getDisplayName(code)}") },
                        onClick = {
                            onCurrencyChange(code)
                            expanded = false
                        },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                    )
                }
            }
        }
    }
}

/**
 * 到期日（可选）：只读输入框 + 日期选择器。存款 / 信用卡 / 贷款等有"期限"的类型才显示。
 *
 * 一一对应改造新增：Asset 有 dueDate 字段但添加/编辑页没暴露，定期存款、贷款、信用卡
 * 还款日都填不了。选中存 yyyy-MM-dd，点右侧 × 清除。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AssetDueDateField(
    dueDate: String?,
    onDueDateChange: (String?) -> Unit
) {
    var showPicker by remember { mutableStateOf(false) }
    val initialMillis = remember(dueDate) {
        dueDate?.let {
            runCatching {
                LocalDate.parse(it).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            }.getOrNull()
        }
    }
    // 关键：用 dueDate 作为组合 key 重建选择器状态。否则清掉日期后 pickerState 里的
    // 选中值还在，再点「确定」会把刚清掉的日期原样写回（#9）。
    // rememberDatePickerState 本身不接受 key 参数，必须用 key() 强制重建子树。
    val pickerState = key(dueDate ?: "NONE") {
        rememberDatePickerState(initialSelectedDateMillis = initialMillis)
    }

    OutlinedTextField(
        value = dueDate ?: "",
        onValueChange = {},
        readOnly = true,
        label = { Text("到期日（可选）") },
        placeholder = { Text("点击选择日期") },
        trailingIcon = {
            if (dueDate != null) {
                IconButton(onClick = { onDueDateChange(null) }) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "清除到期日",
                        modifier = Modifier.size(18.dp)
                    )
                }
            } else {
                Icon(
                    Icons.Default.DateRange,
                    contentDescription = "选择到期日",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showPicker = true }
    )

    if (showPicker) {
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        // DatePicker 的 selectedDateMillis 是 UTC 零点毫秒；按 UTC 还原成本地日期，
                        // 避免 UTC+8 下被时区挪动一天。
                        val date = pickerState.selectedDateMillis
                            ?.let {
                                Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()
                            }
                        onDueDateChange(date)
                        showPicker = false
                    }
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("取消") }
            }
        ) {
            DatePicker(state = pickerState)
        }
    }
}

@Composable
internal fun AssetNotesField(
    notes: String,
    onNotesChange: (String) -> Unit,
    focusRequester: FocusRequester,
    onDone: () -> Unit
) {
    OutlinedTextField(
        value = notes,
        onValueChange = onNotesChange,
        label = { Text("备注（可选）") },
        placeholder = { Text("添加备注信息，如买入日期、用途等") },
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
        // 表单最后一项：回车收起键盘而不是换行，方便直接点「添加」
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        minLines = 2,
        maxLines = 4
    )
}

@Composable
internal fun AssetFormActions(
    onDismiss: () -> Unit,
    onConfirmClick: () -> Unit,
    isSaving: Boolean,
    saveError: String?,
    confirmLabel: String = "添加"
) {
    // 注意：这里必须是 Column。原先 saveError 的 Text 和按钮 Row 并列放在同一个
    // Surface 里，而 Surface 内部是 Box —— 两者会从左上角重叠，错误文字直接压在
    // 「取消/添加」按钮上。添加页与编辑页都中过这一招。
    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider()
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("取消")
                }
                Button(
                    onClick = onConfirmClick,
                    enabled = !isSaving,
                    modifier = Modifier.weight(1f)
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text(confirmLabel)
                    }
                }
            }
        }

        if (saveError != null) {
            Text(
                saveError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}

/**
 * 由表单原始输入构造 [Asset]。
 *
 * 抽成纯函数是为了能直接单测：这些分支（代码归一化、存款单价恒为 1、
 * 无行情时退回参考单价）原先埋在 composable 的点击回调里，改一次全靠手点验证。
 *
 * 注意：参数非法时会抛 IllegalArgumentException（下面显式 require），由调用方
 * try/catch 兜住（别让主线程抛出去崩掉整个 App）。
 * 正常路径下不会抛——[validateAssetInput] 已按同一套口径挡在前面。
 *
 * 校验写在**这里**而不是 [Asset] 的 init：实体构造阶段一旦校验，Room 读回任何一条
 * 历史脏行都会抛异常，整表查询（getAllAssets）连带主界面崩掉。详见 [Asset] 的注释。
 */
fun buildAssetFromInput(
    selectedType: AssetType,
    name: String,
    symbol: String,
    account: String,
    balance: String,
    purchasePrice: String,
    interestRate: String,
    notes: String,
    fundPrice: Double?,
    stockPrice: Double?,
    currency: String = "CNY",
    dueDate: String? = null
): Asset {
    // 类型与代码号段自洽校正（#7/#21）：手动选了「证券(STOCK)」却填了基金号段代码，
    // 归一化后会落成 fund_ 形态，但 type 仍是 STOCK 会与「基金」语义矛盾。这里把
    // STOCK + 基金号段 校正为 FUND，与「证券」chip 统涵盖股票/基金的本意一致。
    // （FUND + 股票代码、CRYPTO + 股票/基金代码这类真正矛盾的组合由校验层拦截，见
    //  AssetInputValidation 的 typeSymbolMismatch。）
    val effectiveType = if (selectedType == AssetType.STOCK) {
        when (StockCodeManager.classifyCode(symbol)) {
            CodeCategory.ETF, CodeCategory.LOF,
            CodeCategory.EXCHANGE_FUND, CodeCategory.OTC_FUND -> AssetType.FUND
            else -> selectedType
        }
    } else {
        selectedType
    }
    val normalizedSymbol = when (effectiveType) {
        AssetType.STOCK, AssetType.FUND -> StockCodeManager.normalizeSymbol(symbol)
        // 加密货币：占位符写的是「例如：BTC/USDT」，用户照抄就把斜杠原样落库；
        // cryptoTradingPairFor 不剥离 '/'，于是 ?symbol=BTC/USDT 币安直接返回 400，
        // 兜底候选又拼成 BTC/USDTUSDT → 同样是 400，这条资产永远同步不到价。
        // 统一去掉 / - 与空白并转大写，与币安的交易对形态对齐。
        AssetType.CRYPTO -> symbol.uppercase().filter { it.isLetterOrDigit() }
        else -> symbol
    }
    // 显式校验（不依赖 Asset.init，见本函数 KDoc）：合法性由写入侧把关，
    // 表单入口对非法输入直接拒绝，调用方 try/catch 后转成界面提示。
    val trimmedName = name.trim()
    val finalBalance = balance.toDoubleOrNull() ?: 0.0
    val finalPurchasePrice = if (effectiveType == AssetType.BANK_DEPOSIT) {
        1.0
    } else {
        purchasePrice.toDoubleOrNull() ?: fundPrice ?: stockPrice ?: 0.0
    }
    require(trimmedName.isNotBlank()) { "资产名称不能为空" }
    // 加密货币允许负余额（币安借币 / 组合保证金欠币是合法状态），其它类型不允许
    require(finalBalance >= 0 || effectiveType == AssetType.CRYPTO) { "数量不能为负数" }
    require(finalPurchasePrice >= 0) { "参考单价不能为负数" }

    return Asset(
        // 名称/账户/备注统一 trim（#13）：账户尾随空格会让 (symbol+account) 精确匹配失效，
        // 同一账户被当成两个、加仓不合并、分组也分家。
        name = trimmedName,
        type = effectiveType,
        symbol = normalizedSymbol.takeIf { it.isNotBlank() },
        account = account.trim(),
        balance = finalBalance,
        // 存款的「单价」没有意义，恒定为 1，让 totalValue 直接等于本金
        purchasePrice = finalPurchasePrice,
        currentPrice = if (effectiveType == AssetType.BANK_DEPOSIT) {
            1.0
        } else {
            fundPrice ?: stockPrice ?: purchasePrice.toDoubleOrNull()
        },
        // 利率：存款与贷款都可能有；存款新增时必填（由校验保证），贷款可选
        interestRate = if (effectiveType == AssetType.BANK_DEPOSIT || effectiveType == AssetType.LOAN) {
            interestRate.toDoubleOrNull()
        } else {
            null
        },
        // 币种 / 到期日：一一对应改造新增写入（原本 Asset 有这两字段但表单没暴露）
        currency = currency,
        dueDate = dueDate?.takeIf { it.isNotBlank() },
        notes = notes.trim().takeIf { it.isNotBlank() }
    )
}

/**
 * 按资产类型给币种一个合理默认值：加密货币行情以美元计价（币安），默认 USD；
 * 其余（股票/基金/存款/负债）默认人民币。用户可随时在表单里改。
 */
internal fun defaultCurrencyForType(type: AssetType): String =
    if (type == AssetType.CRYPTO) "USD" else "CNY"
