package com.example.stockdiary.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.network.FundAPI
import com.example.stockdiary.network.SinaStockData
import com.example.stockdiary.ui.components.DialogSectionHeader
import com.example.stockdiary.ui.components.DialogTypeChip
import com.example.stockdiary.utils.StockCodeManager
import com.example.stockdiary.utils.StockCodeManager.CodeCategory

/**
 * 「添加资产」弹窗的表单构件与提交逻辑。
 *
 * 原先这些都内联在 AddAssetDialog 单个 660 行的 composable 里，任何一个字段的改动
 * 都要在几百行里翻找。这里按职责拆开：每个字段一个 composable、参数显式传入，
 * 提交构造 [buildAssetFromInput] 抽成纯函数（可直接单测，见 AddAssetFormTest）。
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddAssetHeader(onDismiss: () -> Unit) {
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
                text = "添加资产",
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
    showError: Boolean
) {
    OutlinedTextField(
        value = name,
        onValueChange = onNameChange,
        label = { Text("资产名称") },
        placeholder = { Text("例如：贵州茅台、易方达消费精选") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        isError = showError && name.isBlank(),
        supportingText = if (showError && name.isBlank()) { { Text("必填") } } else null
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AssetSymbolField(
    symbol: String,
    onSymbolChange: (String) -> Unit,
    selectedType: AssetType,
    isLoading: Boolean,
    required: Boolean,
    showError: Boolean
) {
    OutlinedTextField(
        value = symbol,
        onValueChange = onSymbolChange,
        label = {
            when (selectedType) {
                AssetType.CRYPTO -> Text("交易对")
                else -> Text("代码")
            }
        },
        placeholder = {
            when (selectedType) {
                AssetType.CRYPTO -> Text("例如：BTC/USDT")
                else -> Text("输入代码自动识别：600519/510050/007467...")
            }
        },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        // 没有代码的证券类资产永远拿不到行情：价格同步、持仓合并、导入回放、
        // 分红匹配全都按 symbol 走，留空意味着市值恒为参考单价的静态值。
        isError = required && showError && symbol.isBlank(),
        supportingText = if (required && showError && symbol.isBlank()) {
            { Text("请填写代码，否则无法同步行情价格") }
        } else null,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Next
        ),
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
    stockInfo: SinaStockData?,
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
    showError: Boolean
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
                .menuAnchor(),
            singleLine = true,
            isError = showError && account.isBlank(),
            supportingText = if (showError && account.isBlank()) { { Text("必填") } } else null,
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(
                    expanded = expanded && filteredAccounts.isNotEmpty()
                )
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
    showError: Boolean
) {
    OutlinedTextField(
        value = balance,
        onValueChange = onBalanceChange,
        label = {
            when (selectedType) {
                AssetType.STOCK -> Text("持股数量")
                AssetType.FUND -> Text("持有份额")
                AssetType.CRYPTO -> Text("持有数量")
                AssetType.BANK_DEPOSIT -> Text("存款金额")
                else -> Text("数量/金额")
            }
        },
        placeholder = { Text("请输入数字") },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        isError = showError && balance.toDoubleOrNull() == null,
        supportingText = {
            if (showError) {
                when {
                    balance.toDoubleOrNull() == null -> Text("请输入有效数字")
                    balance.toDouble() < 0 -> Text("数量不能为负数")
                    else -> {}
                }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AssetPriceField(
    purchasePrice: String,
    onPriceChange: (String) -> Unit,
    autoPrice: Double?,
    showError: Boolean
) {
    OutlinedTextField(
        value = purchasePrice,
        onValueChange = onPriceChange,
        label = { Text("参考单价") },
        placeholder = {
            if (autoPrice != null) {
                Text("自动填充：${autoPrice}")
            } else {
                Text("可选，用于无行情时估算市值")
            }
        },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        isError = showError && purchasePrice.isNotBlank() && purchasePrice.toDoubleOrNull() == null,
        supportingText = {
            if (showError && purchasePrice.isNotBlank()) {
                when {
                    purchasePrice.toDoubleOrNull() == null -> Text("请输入有效数字")
                    purchasePrice.toDouble() < 0 -> Text("价格不能为负数")
                    else -> {}
                }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AssetInterestRateField(
    interestRate: String,
    onRateChange: (String) -> Unit,
    showError: Boolean
) {
    OutlinedTextField(
        value = interestRate,
        onValueChange = onRateChange,
        label = { Text("年利率 (%)") },
        placeholder = { Text("例如：3.5") },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        isError = showError && interestRate.toDoubleOrNull() == null,
        supportingText = {
            if (showError) {
                when {
                    interestRate.toDoubleOrNull() == null -> Text("必填，请输入有效数字")
                    interestRate.toDouble() < 0 -> Text("利率不能为负数")
                    else -> {}
                }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AssetNotesField(
    notes: String,
    onNotesChange: (String) -> Unit
) {
    OutlinedTextField(
        value = notes,
        onValueChange = onNotesChange,
        label = { Text("备注（可选）") },
        placeholder = { Text("添加备注信息，如买入日期、用途等") },
        modifier = Modifier.fillMaxWidth(),
        minLines = 2,
        maxLines = 4
    )
}

@Composable
internal fun AddAssetActions(
    onDismiss: () -> Unit,
    onConfirmClick: () -> Unit,
    isSaving: Boolean,
    saveError: String?
) {
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
                    Text("添加")
                }
            }
        }

        if (saveError != null) {
            Text(
                "保存失败：$saveError",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
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
