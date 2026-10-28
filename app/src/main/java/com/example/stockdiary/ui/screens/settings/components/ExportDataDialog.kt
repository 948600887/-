package com.example.stockdiary.ui.screens.settings.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.filled.History
// 注意：ReceiptLong 的 AutoMirrored 版本在当前 Compose BOM（2024.02.00）里取不到，
// 只能用 deprecated 的 filled 版；等 BOM 升级后再换 Icons.AutoMirrored.Filled.ReceiptLong。
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ExportDataDialog(
    assetCount: Int,
    snapshotCount: Int,
    transactionCount: Int,
    dividendCount: Int,
    isExporting: Boolean,
    onExportText: () -> Unit,
    onExportCsvAssets: () -> Unit,
    onExportCsvSnapshots: () -> Unit,
    onExportCsvTransactions: () -> Unit,
    onExportCsvStatement: () -> Unit,
    onExportCsvDividends: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导出数据") },
        text = {
            // 6 个按钮竖排会超过一屏（尤其横屏/小屏），必须可滚动，
            // 否则最下面的「分红明细」会被对话框裁掉又够不着
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text("选择导出格式：")
                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = onExportText,
                    enabled = !isExporting && assetCount > 0,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.AutoMirrored.Filled.TextSnippet, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (assetCount > 0) "导出为文本文件 (.txt)" else "导出为文本文件 (暂无资产)")
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = onExportCsvAssets,
                    // 空库导出的只有一个表头，等于给用户一个「成功」的空文件
                    enabled = !isExporting && assetCount > 0,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.TableChart, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (assetCount > 0) "导出资产 (${assetCount}条)"
                        else "导出资产 (暂无资产)"
                    )
                }

                if (transactionCount > 0) {
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = onExportCsvTransactions,
                        enabled = !isExporting,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ReceiptLong, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("导出交易对账单 (${transactionCount}条)")
                    }
                }

                if (snapshotCount > 0) {
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = onExportCsvSnapshots,
                        enabled = !isExporting,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.History, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("导出历史快照 (${snapshotCount}条)")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = onExportCsvStatement,
                    // 四类数据全空时同样只剩表头，禁用
                    enabled = !isExporting &&
                        (assetCount > 0 || snapshotCount > 0 || transactionCount > 0 || dividendCount > 0),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.AutoMirrored.Filled.TextSnippet, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("导出综合对账单")
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = onExportCsvDividends,
                    // 没有分红记录时禁用：否则会导出一个只写着「暂无分红记录」的空文件
                    enabled = !isExporting && dividendCount > 0,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Savings, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (dividendCount > 0) "导出分红明细 (${dividendCount}条)"
                        else "导出分红明细 (暂无分红记录)"
                    )
                }
            }
        },
        // 单选取消的对话框：把「取消」放在 dismissButton 槽位，
        // 别再用 confirmButton 的强调位去承载一个取消动作
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
