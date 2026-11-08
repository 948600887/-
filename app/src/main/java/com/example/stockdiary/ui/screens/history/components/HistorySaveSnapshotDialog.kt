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
import com.example.stockdiary.ui.theme.AppTheme
import com.example.stockdiary.utils.debts
import com.example.stockdiary.utils.excludingDebts
import com.example.stockdiary.utils.format
import com.example.stockdiary.utils.sumDebtValueCny
import com.example.stockdiary.utils.totalValueCny

@Composable
fun HistorySaveSnapshotDialog(
    assets: List<Asset>,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    // 负债口径统一为 Asset.isDebt（含余额为负的借币负债），与 AssetSnapshot.fromAssets
    // 的 excludingDebts/debts 一致；否则一笔负余额借币会被算进「总资产」而不是「负债」，
    // 弹窗分项与保存后的快照对不上。净资产不变，只修总资产/负债分项。
    val assetsOnly = assets.excludingDebts()
    val liabilities = assets.debts()
    // 一律折算成人民币：多币种资产的原币金额不能直接相加，
    // 否则「净资产 = 总资产 - 总负债」是拿美元减日元。
    val totalValue = assetsOnly.sumOf { it.totalValueCny }
    val totalLiabilities = liabilities.sumDebtValueCny()
    val netAssets = totalValue - totalLiabilities

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("保存快照") },
        text = {
            Column {
                Text("确定要保存当前资产快照吗？")
                Spacer(modifier = Modifier.height(8.dp))

                Text("净资产: ¥${netAssets.format(2)}", fontWeight = FontWeight.Bold)
                if (totalLiabilities > 0) {
                    Text("负债: ¥${totalLiabilities.format(2)}", color = AppTheme.colors.profitGreen)
                }
                Text("总资产: ¥${totalValue.format(2)}")
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
