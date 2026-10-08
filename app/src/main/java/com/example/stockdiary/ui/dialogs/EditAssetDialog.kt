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
import com.example.stockdiary.ui.components.DialogInfoItem
import com.example.stockdiary.ui.components.DialogSectionHeader
import com.example.stockdiary.ui.components.DialogTypeChip
import com.example.stockdiary.utils.StockCodeManager
import com.example.stockdiary.utils.format

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
                    SectionHeader("资产类型")
                    
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(AssetType.STOCK, AssetType.FUND, AssetType.BANK_DEPOSIT).forEach { type ->
                                TypeChip(
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
                                TypeChip(
                                    type = type,
                                    selected = selectedType == type,
                                    onClick = { selectedType = type },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                    
                    SectionHeader("基本信息")
                    
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
                    
                    SectionHeader("交易信息")
                    
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
                    
                    SectionHeader("备注")
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdjustPositionDialog(
    asset: Asset,
    onDismiss: () -> Unit,
    onConfirm: (Asset) -> Unit
) {
    var action by remember { mutableStateOf("加仓") }
    var amount by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    val isSaving = remember { mutableStateOf(false) }

    // 只有用户改过数量后才报错，避免刚打开空框就一片红
    var amountTouched by remember { mutableStateOf(false) }
    
    // 原先这里用一长串 `?:` 混在 `&&`/`||` 里，优先级含糊且算完没人用（按钮没接）。
    // 拆成具名条件，直接决定确认按钮是否可点、以及出错时给哪句提示。
    val isValid by remember {
        derivedStateOf {
            val quantity = amount.toDoubleOrNull() ?: return@derivedStateOf false
            val priceOk = price.isBlank() || price.toDoubleOrNull() != null
            // 减仓数量等于持仓会把资产整条删掉（仓储层 quantity >= balance 即删除）。
            // 那不是「减仓」，留给「删除资产」做，这里直接判为不合法。
            val quantityOk = quantity > 0 && (action == "加仓" || quantity < asset.balance)
            priceOk && quantityOk
        }
    }
    
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(0.95f),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier.fillMaxWidth()
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
                            text = "加减仓",
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
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    SectionHeader("当前信息")
                    
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp)
                        ) {
                            Text(
                                text = asset.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                InfoItem("持有", "${asset.balance.format(0)}")
                                InfoItem("现价", "${(asset.currentPrice ?: asset.purchasePrice).format(3)}")
                            }
                        }
                    }
                    
                    SectionHeader("操作类型")
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = action == "加仓",
                            onClick = { action = "加仓" },
                            label = { Text("加仓") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = action == "减仓",
                            onClick = { action = "减仓" },
                            label = { Text("减仓") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.errorContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onErrorContainer
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    
                    OutlinedTextField(
                        value = amount,
                        onValueChange = { amount = it; amountTouched = true },
                        label = { 
                            when (asset.type) {
                                AssetType.STOCK -> Text("买卖数量（股）")
                                AssetType.FUND -> Text("买卖份额")
                                else -> Text("数量")
                            }
                        },
                        placeholder = { Text("请输入正整数") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        isError = amountTouched && !isValid,
                        supportingText = {
                            if (amountTouched && !isValid) {
                                val quantity = amount.toDoubleOrNull()
                                Text(
                                    when {
                                        quantity == null -> "请输入有效数字"
                                        quantity <= 0 -> "数量必须大于 0"
                                        action == "减仓" && quantity > asset.balance -> "减仓数量不能超过持仓"
                                        action == "减仓" && quantity == asset.balance ->
                                            "会清空该持仓（将删除资产）。若要清仓请用删除"
                                        else -> "数量不合法"
                                    }
                                )
                            }
                        }
                    )
                    
                    OutlinedTextField(
                        value = price,
                        onValueChange = { price = it },
                        label = { Text("成交单价（可选）") },
                        placeholder = { Text("留空则使用当前参考价") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true
                    )
                    
                    if (amount.toDoubleOrNull() != null) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            val delta = if (action == "加仓") amount.toDouble() else -amount.toDouble()
                            val newBalance = asset.balance + delta
                            val tradePrice = price.toDoubleOrNull() ?: (asset.currentPrice ?: asset.purchasePrice)
                            val tradeTotal = Math.abs(delta) * tradePrice
                            
                            Column(
                                modifier = Modifier.padding(12.dp)
                            ) {
                                Text(
                                    text = "交易预览",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    InfoItem("新持仓", "${newBalance.format(0)}")
                                    InfoItem("交易金额", "¥${tradeTotal.format(2)}")
                                }
                            }
                        }
                    }
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
                                // 先标记再校验：非法输入要立刻变红给出提示，
                                // 不能点了按钮界面毫无反应，那看起来就是按钮坏了。
                                amountTouched = true
                                if (!isValid) return@Button
                                val quantity = amount.toDouble()
                                val delta = if (action == "加仓") quantity else -quantity
                                val newBalance = asset.balance + delta
                                // 减到负数 / 归零后再除以 0 会得到 Infinity 参考单价，
                                // Asset.init 的 require 会在主线程抛出。这里先挡住。
                                if (newBalance <= 0) {
                                    amountTouched = true
                                    return@Button
                                }
                                isSaving.value = true
                                val tradePrice = price.toDoubleOrNull()
                                
                                val oldCost = asset.purchasePrice
                                val newCost = if (action == "加仓" && tradePrice != null) {
                                    ((asset.balance * oldCost) + (kotlin.math.abs(delta) * tradePrice)) / newBalance
                                } else {
                                    oldCost
                                }
                                
                                val updatedAsset = asset.copy(
                                    balance = newBalance,
                                    purchasePrice = if (newCost.isFinite() && newCost > 0) newCost else asset.purchasePrice
                                )
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
                                Text("确认")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) = DialogSectionHeader(text)

@Composable
private fun TypeChip(
    type: AssetType,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) = DialogTypeChip(type, selected, onClick, modifier)

@Composable
private fun InfoItem(
    label: String,
    value: String,
    color: androidx.compose.ui.graphics.Color? = null
) = DialogInfoItem(label, value, color)