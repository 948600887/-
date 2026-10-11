package com.example.stockdiary.ui.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.ui.components.DialogInfoItem
import com.example.stockdiary.ui.components.DialogSectionHeader
import com.example.stockdiary.utils.format

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
                    DialogSectionHeader("当前信息")
                    
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
                                DialogInfoItem("持有", "${asset.balance.format(0)}")
                                DialogInfoItem("现价", "${(asset.currentPrice ?: asset.purchasePrice).format(3)}")
                            }
                        }
                    }
                    
                    DialogSectionHeader("操作类型")
                    
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
                                    DialogInfoItem("新持仓", "${newBalance.format(0)}")
                                    DialogInfoItem("交易金额", "¥${tradeTotal.format(2)}")
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
