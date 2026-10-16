package com.example.stockdiary.ui.screens.settings.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.filled.History
// 注意：ReceiptLong 的 AutoMirrored 版本在当前 Compose BOM（2024.02.00）里取不到，
// 只能用 deprecated 的 filled 版；等 BOM 升级后再换 Icons.AutoMirrored.Filled.ReceiptLong。
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.stockdiary.database.AssetSnapshot
import com.example.stockdiary.database.Transaction

@Composable
fun ExportDataDialog(
    snapshots: List<AssetSnapshot>,
    transactions: List<Transaction>,
    isExporting: Boolean,
    onExportText: () -> Unit,
    onExportCsvAssets: () -> Unit,
    onExportCsvSnapshots: () -> Unit,
    onExportCsvTransactions: () -> Unit,
    onExportCsvStatement: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导出数据") },
        text = {
            Column {
                Text("选择导出格式：")
                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = onExportText,
                    enabled = !isExporting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.AutoMirrored.Filled.TextSnippet, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("导出为文本文件 (.txt)")
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = onExportCsvAssets,
                    enabled = !isExporting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.TableChart, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("导出资产 (.csv)")
                }

                if (transactions.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = onExportCsvTransactions,
                        enabled = !isExporting,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ReceiptLong, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("导出交易对账单 (${transactions.size}条)")
                    }
                }

                if (snapshots.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = onExportCsvSnapshots,
                        enabled = !isExporting,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.History, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("导出历史快照 (${snapshots.size}条)")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = onExportCsvStatement,
                    enabled = !isExporting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.AutoMirrored.Filled.TextSnippet, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("导出综合对账单")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
