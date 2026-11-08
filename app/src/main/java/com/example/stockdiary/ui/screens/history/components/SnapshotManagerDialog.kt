package com.example.stockdiary.ui.screens.history.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.stockdiary.data.calculator.SnapshotAmountInput
import com.example.stockdiary.database.AssetSnapshot
import com.example.stockdiary.ui.theme.AppTheme
import com.example.stockdiary.utils.format
import com.example.stockdiary.utils.toProfitDisplay
import com.example.stockdiary.ui.theme.profitColor

/**
 * 快照管理：按日期倒序列出历史快照，行内可编辑、用当前持仓重存、删除。
 *
 * 入口只在历史页右上角；趋势图不承担编辑入口。
 * 三个操作都必须二次确认——编辑逐项列出新旧值，重存会用当前持仓覆盖历史点
 * （汇率按重存时刻冻结）。手动修正过的日期在行内带「手动修正」标记。
 */
@Composable
fun SnapshotManagerDialog(
    snapshots: List<AssetSnapshot>,
    manualAdjustedDates: Set<String>,
    onDelete: (String) -> Unit,
    onResave: (String) -> Unit,
    onUpdate: (String, SnapshotAmountInput) -> Unit,
    onDismiss: () -> Unit
) {
    var pendingAction by remember { mutableStateOf<PendingAction?>(null) }
    var editingSnapshot by remember { mutableStateOf<AssetSnapshot?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = AppTheme.colors.surface,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "快照管理",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "按日期倒序。重存会使用当前持仓覆盖该历史点。",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTheme.colors.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))

                if (snapshots.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "暂无快照",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppTheme.colors.onSurfaceVariant
                        )
                    }
                } else {
                    // normalizeForDisplay 是升序；展示反转为倒序，涨跌仍用升序里的前一条
                    val ascending = snapshots
                    LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                        items(ascending.indices.reversed().toList()) { index ->
                            val snapshot = ascending[index]
                            val previous = if (index > 0) ascending[index - 1] else null
                            SnapshotManagerRow(
                                snapshot = snapshot,
                                previous = previous,
                                manuallyAdjusted = snapshot.date in manualAdjustedDates,
                                onEdit = { editingSnapshot = snapshot },
                                onDelete = {
                                    pendingAction = PendingAction.Delete(snapshot.date)
                                },
                                onResave = {
                                    pendingAction = PendingAction.Resave(snapshot.date)
                                }
                            )
                            HorizontalDivider(color = AppTheme.colors.outlineVariant)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
            }
        }
    }

    pendingAction?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingAction = null },
            title = { Text(if (action is PendingAction.Delete) "删除快照" else "重存快照") },
            text = {
                Text(
                    if (action is PendingAction.Delete) {
                        "确定删除 ${action.date} 的快照？该日数据将从历史页消失。"
                    } else {
                        "确定用当前持仓覆盖 ${action.date} 的快照？当天原有数据会被替换。"
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        when (action) {
                            is PendingAction.Delete -> onDelete(action.date)
                            is PendingAction.Resave -> onResave(action.date)
                        }
                        pendingAction = null
                    }
                ) {
                    Text(if (action is PendingAction.Delete) "删除" else "重存")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingAction = null }) { Text("取消") }
            }
        )
    }

    editingSnapshot?.let { snapshot ->
        SnapshotEditDialog(
            snapshot = snapshot,
            onDismiss = { editingSnapshot = null },
            onConfirm = { input ->
                onUpdate(snapshot.date, input)
                editingSnapshot = null
            }
        )
    }
}

private sealed interface PendingAction {
    val date: String

    data class Delete(override val date: String) : PendingAction
    data class Resave(override val date: String) : PendingAction
}

@Composable
private fun SnapshotManagerRow(
    snapshot: AssetSnapshot,
    previous: AssetSnapshot?,
    manuallyAdjusted: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onResave: () -> Unit
) {
    val dailyChange = previous?.let { snapshot.netAssets - it.netAssets }
    val dailyRate = previous?.takeIf { it.netAssets > 0 }
        ?.let { (snapshot.netAssets - it.netAssets) / it.netAssets * 100 }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = snapshot.date,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            if (manuallyAdjusted) {
                Text(
                    text = "手动修正",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                text = "净资产 ¥${snapshot.netAssets.format(2)}",
                fontSize = 12.sp,
                color = AppTheme.colors.onSurfaceVariant
            )
            Text(
                text = when {
                    dailyChange == null -> "当日涨跌 —"
                    dailyRate == null -> "当日涨跌 ${dailyChange.toProfitDisplay("¥")}"
                    else -> "当日涨跌 ${dailyChange.toProfitDisplay("¥")} " +
                        "(${if (dailyRate >= 0) "+" else ""}${dailyRate.format(2)}%)"
                },
                fontSize = 12.sp,
                color = if (dailyChange != null) dailyChange.profitColor() else AppTheme.colors.onSurfaceVariant
            )
        }
        TextButton(onClick = onEdit) { Text("编辑", fontSize = 12.sp) }
        TextButton(onClick = onResave) { Text("重存", fontSize = 12.sp) }
        TextButton(onClick = onDelete) {
            Text("删除", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
        }
    }
}
