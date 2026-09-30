package com.example.stockdiary.ui.screens.history.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.stockdiary.database.Asset
import com.example.stockdiary.ui.theme.ProfitGreen
import com.example.stockdiary.utils.format
import com.example.stockdiary.utils.toProfitDisplay

@Composable
fun HistorySaveSnapshotDialog(
    assets: List<Asset>,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val assetsOnly = assets.filter { !it.type.isLiability }
    val liabilities = assets.filter { it.type.isLiability }
    val totalValue = assetsOnly.sumOf { it.totalValue }
    val totalLiabilities = liabilities.sumOf { it.totalValue }
    val netAssets = totalValue - totalLiabilities
    val totalCost = assetsOnly.sumOf { it.balance * it.purchasePrice }
    val totalProfit = totalValue - totalCost
    val yieldRate = if (totalCost > 0) totalProfit / totalCost * 100 else 0.0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("保存快照") },
        text = {
            Column {
                Text("确定要保存当前资产快照吗？")
                Spacer(modifier = Modifier.height(8.dp))

                Text("净资产: ¥${netAssets.format(2)}", fontWeight = FontWeight.Bold)
                if (totalLiabilities > 0) {
                    Text("负债: ¥${totalLiabilities.format(2)}", color = ProfitGreen)
                }
                Text("成本: ¥${totalCost.format(2)}")
                Text("收益: ${totalProfit.toProfitDisplay("¥")}")
                Text("收益率: ${if (yieldRate >= 0) "+" else ""}${yieldRate.format(2)}%")
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !isSaving
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("保存")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
