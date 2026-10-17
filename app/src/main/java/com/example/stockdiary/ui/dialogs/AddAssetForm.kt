package com.example.stockdiary.ui.dialogs

import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.network.FundAPI
import com.example.stockdiary.network.model.StockData
import com.example.stockdiary.ui.components.DialogSectionHeader
import com.example.stockdiary.ui.components.DialogTypeChip
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
            listOf(AssetType.CRYPTO, AssetType.CREDIT_CARD, AssetType.LOAN).forEach { type ->
                DialogTypeChip(
                    type = type,
                    selected = selectedType == type,
                    onClick = { onSelectType(type) },
                    modifier = Modifier.weight(1f)
                )
            }
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
                .background(category.color.copy(alpha = 0.15f))
                .padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            Text(
                text = category.label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = category.color
            )
        }
    }
}

/** 代码查询的三种结果：命中股票 / 命中基金 / 出错 */
@Composable
internal fun LookupResultCards(
    stockInfo: StockData?,
    fundInfo: FundAPI.FundInfo?,
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
    onNext: () -> Unit
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
        // 改到 supportingText 常驻，且让位于错误提示。
        supportingText = when {
            error != null -> { { Text(error) } }
            autoPrice != null && autoPrice > 0 -> {
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
 * 注意：[Asset] 的 init 里有 require(...) 校验，参数非法时会抛 IllegalArgumentException，
 * 由调用方 try/catch 兜住（别让主线程抛出去崩掉整个 App）。
 * 正常路径下不会抛——[validateAssetInput] 已按同一套口径挡在前面。
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
    stockPrice: Double?
): Asset {
    val normalizedSymbol = if (selectedType == AssetType.STOCK || selectedType == AssetType.FUND) {
        StockCodeManager.normalizeSymbol(symbol)
    } else {
        symbol
    }
    return Asset(
        name = name,
        type = selectedType,
        symbol = normalizedSymbol.takeIf { it.isNotBlank() },
        account = account,
        balance = balance.toDoubleOrNull() ?: 0.0,
        // 存款的「单价」没有意义，恒定为 1，让 totalValue 直接等于本金
        purchasePrice = if (selectedType == AssetType.BANK_DEPOSIT) {
            1.0
        } else {
            purchasePrice.toDoubleOrNull() ?: fundPrice ?: stockPrice ?: 0.0
        },
        currentPrice = if (selectedType == AssetType.BANK_DEPOSIT) {
            1.0
        } else {
            fundPrice ?: stockPrice ?: purchasePrice.toDoubleOrNull()
        },
        interestRate = if (selectedType == AssetType.BANK_DEPOSIT) {
            interestRate.toDoubleOrNull()
        } else {
            null
        },
        notes = notes.takeIf { it.isNotBlank() }
    )
}
