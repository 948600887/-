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
                        onValueChange = { name = it },
                        label = { Text("资产名称") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = name.isBlank(),
                        supportingText = if (name.isBlank()) { { Text("必填") } } else null
                    )
                    
                    if (selectedType == AssetType.STOCK || selectedType == AssetType.FUND || selectedType == AssetType.CRYPTO) {
                        OutlinedTextField(
                            value = symbol,
                            onValueChange = { symbol = it },
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
                        onValueChange = { account = it },
                        label = { Text("账户/平台") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = account.isBlank(),
                        supportingText = if (account.isBlank()) { { Text("必填") } } else null
                    )
                    
                    SectionHeader("交易信息")
                    
                    OutlinedTextField(
                        value = balance,
                        onValueChange = { balance = it },
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
                        isError = (balance.toDoubleOrNull() ?: -1.0) < 0,
                        supportingText = {
                            when {
                                balance.toDoubleOrNull() == null -> Text("请输入有效数字")
                                (balance.toDoubleOrNull() ?: -1.0) < 0 -> Text("数量不能为负数")
                                else -> {}
                            }
                        }
                    )
                    
                    if (selectedType != AssetType.BANK_DEPOSIT) {
                        OutlinedTextField(
                            value = purchasePriceInput,
                            onValueChange = { purchasePriceInput = it },
                            label = { Text("买入单价") },
                            placeholder = { Text("可选，留空则不计算盈亏") },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            isError = purchasePriceInput.isNotBlank() && (purchasePriceInput.toDoubleOrNull() ?: -1.0) < 0,
                            supportingText = {
                                if (purchasePriceInput.isNotBlank() && (purchasePriceInput.toDoubleOrNull() ?: -1.0) < 0) {
                                    Text("价格不能为负数")
                                }
                            }
                        )
                    }

                    if (selectedType == AssetType.BANK_DEPOSIT) {
                        OutlinedTextField(
                            value = interestRateInput,
                            onValueChange = { interestRateInput = it },
                            label = { Text("年利率 (%)") },
                            placeholder = { Text("例如：3.5") },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            isError = (interestRateInput.toDoubleOrNull() ?: -1.0) < 0,
                            supportingText = {
                                when {
                                    interestRateInput.toDoubleOrNull() == null -> Text("必填，请输入有效数字")
                                    (interestRateInput.toDoubleOrNull() ?: -1.0) < 0 -> Text("利率不能为负数")
                                    else -> {}
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
                                isSaving.value = true
                                val updatedAsset = asset.copy(
                                    name = name,
                                    type = selectedType,
                                    symbol = symbol.takeIf { it.isNotBlank() },
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
                                onConfirm(updatedAsset)
                            },
                            enabled = isValid && !isSaving.value,
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
    
    val isValid by remember { 
        derivedStateOf { 
            amount.toDoubleOrNull() != null && 
            amount.toDoubleOrNull() ?: 0.0 > 0 &&
            (price.toDoubleOrNull() != null || price.isBlank()) &&
            (action == "减仓" && amount.toDoubleOrNull() ?: 0.0 <= asset.balance || action == "加仓")
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
                                InfoItem("成本", "${asset.purchasePrice.format(3)}")
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
                        onValueChange = { amount = it },
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
                        isError = amount.toDoubleOrNull() == null || 
                                  (action == "减仓" && amount.toDoubleOrNull() ?: 0.0 > asset.balance),
                        supportingText = if (amount.toDoubleOrNull() == null) {
                            { Text("请输入有效数字") }
                        } else if (action == "减仓" && amount.toDoubleOrNull() ?: 0.0 > asset.balance) {
                            { Text("减仓数量不能超过持仓") }
                        } else null
                    )
                    
                    OutlinedTextField(
                        value = price,
                        onValueChange = { price = it },
                        label = { Text("成交单价（可选）") },
                        placeholder = { Text("留空则使用当前成本价") },
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
                            val tradePrice = price.toDoubleOrNull() ?: asset.purchasePrice
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
                                isSaving.value = true
                                val delta = if (action == "加仓") amount.toDouble() else -amount.toDouble()
                                val newBalance = asset.balance + delta
                                val tradePrice = price.toDoubleOrNull()
                                
                                val oldCost = asset.purchasePrice
                                val newCost = if (action == "加仓" && tradePrice != null) {
                                    ((asset.balance * oldCost) + (kotlin.math.abs(delta) * tradePrice)) / newBalance
                                } else {
                                    oldCost
                                }
                                
                                val updatedAsset = asset.copy(
                                    balance = newBalance,
                                    purchasePrice = if (newCost > 0) newCost else asset.purchasePrice
                                )
                                onConfirm(updatedAsset)
                            },
                            enabled = isValid && !isSaving.value,
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