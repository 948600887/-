package com.example.stockdiary.ui.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.ui.theme.*
import com.example.stockdiary.ui.components.DialogSectionHeader
import com.example.stockdiary.ui.components.DialogTypeChip
import com.example.stockdiary.utils.StockCodeManager

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
    val isSaving = remember { mutableStateOf(false) }
    val saveError = remember { mutableStateOf<String?>(null) }

    // 「碰过」的字段才报错：编辑弹窗一打开字段都是有值的，只有用户改坏了才提示
    var touched by remember { mutableStateOf(setOf<String>()) }
    
    val isValid by remember {
        derivedStateOf {
            val balanceValid = (balance.toDoubleOrNull() ?: -1.0) >= 0
            val interestRateValid = selectedType != AssetType.BANK_DEPOSIT || (interestRateInput.toDoubleOrNull() ?: -1.0) >= 0
            val purchasePriceValid = selectedType == AssetType.BANK_DEPOSIT || purchasePriceInput.isBlank() || (purchasePriceInput.toDoubleOrNull() ?: -1.0) >= 0
            name.isNotBlank() &&
            account.isNotBlank() &&
            balanceValid &&
            interestRateValid &&
            purchasePriceValid
        }
    }
    
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
                            text = "编辑资产",
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
                
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    DialogSectionHeader("资产类型")
                    
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(AssetType.STOCK, AssetType.FUND, AssetType.BANK_DEPOSIT).forEach { type ->
                                DialogTypeChip(
                                    type = type,
                                    selected = selectedType == type,
                                    onClick = { selectedType = type },
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
                                    onClick = { selectedType = type },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                    
                    DialogSectionHeader("基本信息")
                    
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it; touched = touched + "name" },
                        label = { Text("资产名称") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = "name" in touched && name.isBlank(),
                        supportingText = if ("name" in touched && name.isBlank()) { { Text("必填") } } else null
                    )
                    
                    if (selectedType == AssetType.STOCK || selectedType == AssetType.FUND || selectedType == AssetType.CRYPTO) {
                        OutlinedTextField(
                            value = symbol,
                            onValueChange = { symbol = it; touched = touched + "symbol" },
                            label = { 
                                when (selectedType) {
                                    AssetType.FUND -> Text("基金代码")
                                    AssetType.STOCK -> Text("股票代码")
                                    AssetType.CRYPTO -> Text("交易对")
                                    else -> Text("代码")
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                    
                    OutlinedTextField(
                        value = account,
                        onValueChange = { account = it; touched = touched + "account" },
                        label = { Text("账户/平台") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = "account" in touched && account.isBlank(),
                        supportingText = if ("account" in touched && account.isBlank()) { { Text("必填") } } else null
                    )
                    
                    DialogSectionHeader("交易信息")
                    
                    OutlinedTextField(
                        value = balance,
                        onValueChange = { balance = it; touched = touched + "balance" },
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
                        isError = "balance" in touched && balance.toDoubleOrNull() == null,
                        supportingText = {
                            if ("balance" in touched) {
                                when {
                                    balance.toDoubleOrNull() == null -> Text("请输入有效数字")
                                    balance.toDouble() < 0 -> Text("数量不能为负数")
                                    else -> {}
                                }
                            }
                        }
                    )
                    
                    if (selectedType != AssetType.BANK_DEPOSIT) {
                        OutlinedTextField(
                            value = purchasePriceInput,
                            onValueChange = { purchasePriceInput = it; touched = touched + "price" },
                            label = { Text("参考单价") },
                            placeholder = { Text("可选，用于无行情时估算市值") },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            isError = "price" in touched && purchasePriceInput.isNotBlank() && purchasePriceInput.toDoubleOrNull() == null,
                            supportingText = {
                                if ("price" in touched && purchasePriceInput.isNotBlank()) {
                                    when {
                                        purchasePriceInput.toDoubleOrNull() == null -> Text("请输入有效数字")
                                        purchasePriceInput.toDouble() < 0 -> Text("价格不能为负数")
                                        else -> {}
                                    }
                                }
                            }
                        )
                    }

                    if (selectedType == AssetType.BANK_DEPOSIT) {
                        OutlinedTextField(
                            value = interestRateInput,
                            onValueChange = { interestRateInput = it; touched = touched + "rate" },
                            label = { Text("年利率 (%)") },
                            placeholder = { Text("例如：3.5") },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            isError = "rate" in touched && interestRateInput.toDoubleOrNull() == null,
                            supportingText = {
                                if ("rate" in touched) {
                                    when {
                                        interestRateInput.toDoubleOrNull() == null -> Text("必填，请输入有效数字")
                                        interestRateInput.toDouble() < 0 -> Text("利率不能为负数")
                                        else -> {}
                                    }
                                }
                            }
                        )
                    }
                    
                    DialogSectionHeader("备注")
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = { Text("备注（可选）") },
                        placeholder = { Text("添加备注信息，如买入日期、用途等") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4
                    )
                }
                
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
                            onClick = {
                                if (isSaving.value) return@Button
                                // 保存时统一标记为已触碰：否则按钮灰着却没有红字，用户不知道改哪儿
                                touched = setOf("name", "account", "balance", "price", "rate")
                                if (!isValid) {
                                    saveError.value = "请检查标红的必填项"
                                    return@Button
                                }
                                isSaving.value = true
                                saveError.value = null
                                // asset.copy() 会走 Asset.init 的 require(...)，主线程抛出就是整 App 崩溃
                                val updatedAsset = try {
                                // 代码必须走归一化，和 AddAssetDialog / 交割单导入保持同一口径
                                // （全项目存库形态是 StockCodeManager.normalizeSymbol 的结果，如 600519 → sh600519）。
                                // 否则改过代码的资产在加仓、导入回放、分红匹配时会全部失配，
                                // 连"同名 packs 持仓合并"也会失效——同一只票同一个账户出现两条持仓。
                                val normalizedSymbol = StockCodeManager.normalizeSymbol(symbol.trim())
                                asset.copy(
                                    name = name,
                                    type = selectedType,
                                    symbol = normalizedSymbol.takeIf { it.isNotBlank() },
                                    account = account,
                                    balance = balance.toDoubleOrNull() ?: 0.0,
                                    purchasePrice = if (selectedType == AssetType.BANK_DEPOSIT) {
                                        1.0
                                    } else {
                                        purchasePriceInput.toDoubleOrNull() ?: asset.purchasePrice
                                    },
                                    interestRate = if (selectedType == AssetType.BANK_DEPOSIT) {
                                        interestRateInput.toDoubleOrNull() ?: asset.interestRate
                                    } else {
                                        null
                                    },
                                    notes = notes.takeIf { it.isNotBlank() }
                                )
                                } catch (e: Exception) {
                                    // 保留可操作性：把 loader 复位，否则按钮永久禁用且无提示
                                    isSaving.value = false
                                    saveError.value = e.message ?: "资产数据不合法"
                                    return@Button
                                }
                                onConfirm(updatedAsset)
                            },
                            enabled = !isSaving.value,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (isSaving.value) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Text("保存")
                            }
                        }
                    }

                    saveError.value?.let { message ->
                        Text(
                            "保存失败：$message",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}
