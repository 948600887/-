package com.example.stockdiary.ui.screens.history.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.stockdiary.data.calculator.SnapshotAmountEditor
import com.example.stockdiary.data.calculator.SnapshotAmountInput
import com.example.stockdiary.database.AssetSnapshot
import com.example.stockdiary.ui.theme.AppTheme
import com.example.stockdiary.utils.format

/**
 * 编辑某一天的快照金额。
 *
 * 只能改五个分类金额，总资产/净资产由 [SnapshotAmountEditor] 自动重算，不单独手填；
 * 快照日期只读。保存必须先过二次确认（逐项列出「修改前 → 修改后」），
 * 改动为 0 时直接关闭，不弹确认也不写库。
 */
@Composable
fun SnapshotEditDialog(
    snapshot: AssetSnapshot,
    onDismiss: () -> Unit,
    onConfirm: (SnapshotAmountInput) -> Unit
) {
    val initial = remember(snapshot.id, snapshot.date) { SnapshotAmountEditor.inputOf(snapshot) }
    var stockText by remember(initial) { mutableStateOf(SnapshotAmountEditor.amountText(initial.stock)) }
    var fundText by remember(initial) { mutableStateOf(SnapshotAmountEditor.amountText(initial.fund)) }
    var cryptoText by remember(initial) { mutableStateOf(SnapshotAmountEditor.amountText(initial.crypto)) }
    var depositText by remember(initial) { mutableStateOf(SnapshotAmountEditor.amountText(initial.deposit)) }
    var liabilitiesText by remember(initial) { mutableStateOf(SnapshotAmountEditor.amountText(initial.liabilities)) }

    var pendingConfirm by remember { mutableStateOf<SnapshotAmountInput?>(null) }
    // 只有点过保存之后才把校验错误显形，避免刚打开还没输入就一片红
    var submitAttempted by remember { mutableStateOf(false) }

    val parsed = parseInput(stockText, fundText, cryptoText, depositText, liabilitiesText)
    val errorText = when {
        parsed == null -> "请输入有效金额（不小于 0）"
        !SnapshotAmountEditor.isValid(parsed) -> SnapshotAmountEditor.AT_LEAST_ONE_ASSET_MESSAGE
        else -> null
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f),
            shape = MaterialTheme.shapes.large,
            color = AppTheme.colors.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                Text(
                    text = "编辑快照",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${snapshot.date}（日期不可修改）",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTheme.colors.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))

                AmountField("股票", stockText) { stockText = it }
                AmountField("基金", fundText) { fundText = it }
                AmountField("加密货币", cryptoText) { cryptoText = it }
                AmountField("存款", depositText) { depositText = it }
                AmountField("负债", liabilitiesText) { liabilitiesText = it }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "总资产 ¥${parsed?.let { SnapshotAmountEditor.totalAssetsOf(it).format(2) } ?: "—"}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "净资产 ¥${parsed?.let { SnapshotAmountEditor.netAssetsOf(it).format(2) } ?: "—"}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )

                if (submitAttempted && errorText != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = errorText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    TextButton(
                        onClick = {
                            // 改动为 0：直接关闭，不弹确认、不写库
                            val input = parsed
                            when {
                                input == null || !SnapshotAmountEditor.isValid(input) ->
                                    submitAttempted = true
                                !SnapshotAmountEditor.hasChanges(snapshot, input) -> onDismiss()
                                else -> pendingConfirm = input
                            }
                        }
                    ) { Text("保存") }
                }
            }
        }
    }

    pendingConfirm?.let { input ->
        SnapshotEditConfirmDialog(
            snapshot = snapshot,
            input = input,
            onCancel = { pendingConfirm = null },
            onConfirm = {
                onConfirm(input)
                pendingConfirm = null
            }
        )
    }
}

/** 二次确认：逐项列出五个分类与总资产、净资产的旧值与新值 */
@Composable
private fun SnapshotEditConfirmDialog(
    snapshot: AssetSnapshot,
    input: SnapshotAmountInput,
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("确认修改快照") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = snapshot.date,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTheme.colors.onSurfaceVariant
                )
                SnapshotAmountEditor.changes(snapshot, input).forEach { change ->
                    val changed = change.oldValue != change.newValue
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = change.label,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f),
                            color = AppTheme.colors.onSurfaceVariant
                        )
                        Text(
                            text = "¥${change.oldValue.format(2)} → ¥${change.newValue.format(2)}",
                            fontSize = 12.sp,
                            fontWeight = if (changed) FontWeight.Medium else FontWeight.Normal,
                            color = if (changed) MaterialTheme.colorScheme.primary
                            else AppTheme.colors.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("确认修改") }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("返回") }
        }
    )
}

@Composable
private fun AmountField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
    )
}

/** 五项任意一项非法就整体返回 null：宁可禁用保存，也不要把非法输入静默当 0 */
private fun parseInput(
    stock: String,
    fund: String,
    crypto: String,
    deposit: String,
    liabilities: String
): SnapshotAmountInput? {
    val values = listOf(stock, fund, crypto, deposit, liabilities)
        .map { SnapshotAmountEditor.parseAmount(it) }
    if (values.any { it == null }) return null
    return SnapshotAmountInput(
        stock = values[0]!!,
        fund = values[1]!!,
        crypto = values[2]!!,
        deposit = values[3]!!,
        liabilities = values[4]!!
    )
}
