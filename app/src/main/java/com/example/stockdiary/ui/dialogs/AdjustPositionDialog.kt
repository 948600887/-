package com.example.stockdiary.ui.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.ui.components.DialogInfoItem
import com.example.stockdiary.ui.components.DialogSectionHeader
import com.example.stockdiary.utils.ExchangeRateManager
import com.example.stockdiary.utils.currencySymbol
import com.example.stockdiary.utils.format
import com.example.stockdiary.utils.formatMoney
import com.example.stockdiary.utils.pricePrecisionFor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdjustPositionDialog(
    asset: Asset,
    onDismiss: () -> Unit,
    onConfirm: (Asset) -> Unit
) {
    var action by remember { mutableStateOf(PositionAction.ADD) }
    var amount by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    val isSaving = remember { mutableStateOf(false) }

    // 只有用户改过某个字段后才给那个字段报错，避免刚打开空框就一片红
    var amountTouched by remember { mutableStateOf(false) }
    var priceTouched by remember { mutableStateOf(false) }
    // 点了确认之后所有字段都要把错误显形——否则错在用户还没碰过的字段上时，
    // 点下去界面毫无反应，看起来就是按钮坏了。
    var submitAttempted by remember { mutableStateOf(false) }

    val label = quantityLabel(asset.type)
    val errors by remember(action, amount, price, asset.balance) {
        derivedStateOf { validatePositionInput(action, amount, price, asset.balance, label) }
    }
    val amountError = if (amountTouched || submitAttempted) errors.amount?.message else null
    val priceError = if (priceTouched || submitAttempted) errors.price?.message else null

    val amountFocus = remember { FocusRequester() }
    val priceFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

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
                                // 现价两处修正：
                                // 1. 精度原来恒写 3 位，而持仓页是「基金/ETF 3 位、其余 2 位」——
                                //    同一只股票在列表显示 12.34、在这里显示 12.340。现在共用
                                //    utils.pricePrecisionFor 同一套判定。
                                // 2. 下面「交易金额」是折算后的人民币，这里却是原币裸数字，
                                //    同一屏两套口径（USD 加密会显示成 67000.000）。统一折算成 ¥；
                                //    外币资产在标签上标出原币符号，让用户知道行情源给的不是人民币价。
                                val refPrice = asset.currentPrice ?: asset.purchasePrice
                                val isCny = asset.currency.equals("CNY", ignoreCase = true)
                                DialogInfoItem(
                                    label = if (isCny) "现价" else "现价(${currencySymbol(asset.currency)}折¥)",
                                    value = ExchangeRateManager.toCny(refPrice, asset.currency)
                                        .formatMoney(digits = pricePrecisionFor(asset.type, asset.symbol))
                                )
                            }
                        }
                    }

                    DialogSectionHeader("操作类型")

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = action == PositionAction.ADD,
                            onClick = { action = PositionAction.ADD },
                            label = { Text("加仓") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = action == PositionAction.REDUCE,
                            onClick = { action = PositionAction.REDUCE },
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
                        onValueChange = { amount = sanitizeDecimalInput(it); amountTouched = true },
                        label = {
                            when (asset.type) {
                                AssetType.STOCK -> Text("买卖数量（股）")
                                AssetType.FUND -> Text("买卖份额")
                                else -> Text("数量")
                            }
                        },
                        placeholder = { Text("请输入大于 0 的数字") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(amountFocus),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                            imeAction = ImeAction.Next
                        ),
                        keyboardActions = KeyboardActions(
                            onNext = { priceFocus.requestFocus() }
                        ),
                        singleLine = true,
                        isError = amountError != null,
                        supportingText = { amountError?.let { Text(it) } }
                    )

                    OutlinedTextField(
                        value = price,
                        onValueChange = { price = sanitizeDecimalInput(it); priceTouched = true },
                        label = { Text("成交单价（可选）") },
                        placeholder = { Text("留空则使用当前参考价") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(priceFocus),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                focusManager.clearFocus()
                                submitAttempted = true
                                if (errors.isValid) {
                                    submitAdjustPosition(
                                        asset = asset,
                                        action = action,
                                        amount = amount,
                                        price = price,
                                        isSaving = isSaving,
                                        onConfirm = onConfirm
                                    )
                                } else {
                                    when (errors.firstField) {
                                        "amount" -> amountFocus.requestFocus()
                                        "price" -> priceFocus.requestFocus()
                                    }
                                }
                            }
                        ),
                        singleLine = true,
                        isError = priceError != null,
                        supportingText = { priceError?.let { Text(it) } }
                    )

                    // 只在数量本身没问题时才预览：减超了还显示负持仓只会更迷惑
                    if (errors.amount == null && amount.isNotBlank()) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            val quantity = amount.toDouble()
                            val delta = if (action == PositionAction.ADD) quantity else -quantity
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
                                    // 成交价是原币口径，交易金额也要折算成人民币——
                            // 与持仓页价格行同一口径（不过换算会把 USD 加密货币的
                            // 成交额显示成人民币数字）
                            DialogInfoItem(
                                "交易金额",
                                ExchangeRateManager.toCny(tradeTotal, asset.currency).formatMoney()
                            )
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
                                // 先把所有字段标成「已碰过」让错误显形，
                                // 再把焦点送到第一个出错的字段，省得用户自己找。
                                submitAttempted = true
                                if (!errors.isValid) {
                                    when (errors.firstField) {
                                        "amount" -> amountFocus.requestFocus()
                                        "price" -> priceFocus.requestFocus()
                                    }
                                    return@Button
                                }
                                submitAdjustPosition(
                                    asset = asset,
                                    action = action,
                                    amount = amount,
                                    price = price,
                                    isSaving = isSaving,
                                    onConfirm = onConfirm
                                )
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

/**
 * 真正落库：算新持仓与新成本价，产出更新后的 Asset。
 *
 * 抽成顶层函数是为了让「点确认按钮」和「按键盘 Done 键」走同一条路径，
 * 不至于两种操作算出两个结果。
 *
 * 调用方必须先用 [validatePositionInput] 确认过输入合法。
 */
private fun submitAdjustPosition(
    asset: Asset,
    action: PositionAction,
    amount: String,
    price: String,
    isSaving: MutableState<Boolean>,
    onConfirm: (Asset) -> Unit
) {
    val quantity = amount.toDoubleOrNull() ?: return
    val delta = if (action == PositionAction.ADD) quantity else -quantity
    val newBalance = asset.balance + delta
    // 减到负数 / 归零后再除以 0 会得到 Infinity 参考单价，
    // Asset.init 的 require 会在主线程抛出。这里先挡住。
    if (newBalance <= 0) return

    isSaving.value = true
    val tradePrice = price.toDoubleOrNull()

    val oldCost = asset.purchasePrice
    val newCost = if (action == PositionAction.ADD && tradePrice != null) {
        ((asset.balance * oldCost) + (kotlin.math.abs(delta) * tradePrice)) / newBalance
    } else {
        oldCost
    }

    val updatedAsset = asset.copy(
        balance = newBalance,
        purchasePrice = if (newCost.isFinite() && newCost > 0) newCost else asset.purchasePrice
    )
    onConfirm(updatedAsset)
}
