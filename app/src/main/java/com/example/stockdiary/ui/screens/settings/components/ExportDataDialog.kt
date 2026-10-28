package com.example.stockdiary.ui.screens.settings.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.stockdiary.ui.viewmodels.SettingsViewModel

/**
 * 统一的导出对话框：把原先平铺的 6 个「内容×格式」按钮收敛成
 * 「先选内容、再选格式、一个导出按钮」。
 *
 * 原先 6 个按钮 = 4 类内容（资产/快照/交易/分红）+ 文本/CSV 两种格式 + 综合对账单，
 * 维度没拆开，看着碎。这里两个维度正交呈现：
 *  - 内容（单选）：资产 / 历史快照 / 交易对账单 / 分红明细 / 综合对账单
 *  - 格式（单选）：文本(.txt) / CSV(.csv)
 *
 * 约束：文本格式只对「资产」有意义（DataExporter 只有 exportToText 资产报告，
 * 没有文本版快照/交易/分红/综合），所以选非资产内容时文本自动置灰并回退 CSV。
 *
 * 内部把 (内容, 格式) 映射回 [SettingsViewModel.ExportType]，ViewModel / DataExporter
 * 完全不用改，功能 1:1 等价。
 */
@Composable
fun ExportDataDialog(
    assetCount: Int,
    snapshotCount: Int,
    transactionCount: Int,
    dividendCount: Int,
    isExporting: Boolean,
    onExport: (SettingsViewModel.ExportType) -> Unit,
    onDismiss: () -> Unit
) {
    // 可用内容：按数据有无动态显示（空库时对应项不出现，和旧逻辑一致）
    val contents = buildList {
        if (assetCount > 0) add(ExportContent.ASSET)
        if (snapshotCount > 0) add(ExportContent.SNAPSHOT)
        if (transactionCount > 0) add(ExportContent.TRANSACTION)
        if (dividendCount > 0) add(ExportContent.DIVIDEND)
        // 综合对账单：四类任一非空才可选（四类全空时连表头都没意义）
        if (assetCount > 0 || snapshotCount > 0 || transactionCount > 0 || dividendCount > 0) {
            add(ExportContent.STATEMENT)
        }
    }

    var selectedContent by remember { mutableStateOf(contents.firstOrNull() ?: ExportContent.ASSET) }
    var selectedFormat by remember { mutableStateOf(ExportFormat.CSV) }

    // 文本格式仅对「资产」可用：切到非资产内容时，若当前选的是文本，自动回退 CSV
    val textAvailable = selectedContent == ExportContent.ASSET
    if (!textAvailable && selectedFormat == ExportFormat.TEXT) {
        selectedFormat = ExportFormat.CSV
    }

    val canExport = contents.isNotEmpty() && !isExporting

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导出数据") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "1. 选择导出内容",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                contents.forEach { content ->
                    val count = when (content) {
                        ExportContent.ASSET -> assetCount
                        ExportContent.SNAPSHOT -> snapshotCount
                        ExportContent.TRANSACTION -> transactionCount
                        ExportContent.DIVIDEND -> dividendCount
                        ExportContent.STATEMENT -> null
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedContent == content,
                            onClick = { selectedContent = content }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = content.icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = content.label + (count?.let { " ($it 条)" } ?: ""),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "2. 选择格式",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = selectedFormat == ExportFormat.TEXT,
                        onClick = { if (textAvailable) selectedFormat = ExportFormat.TEXT },
                        enabled = textAvailable,
                        label = { Text("文本 (.txt)") }
                    )
                    FilterChip(
                        selected = selectedFormat == ExportFormat.CSV,
                        onClick = { selectedFormat = ExportFormat.CSV },
                        label = { Text("CSV (.csv)") }
                    )
                }
                if (!textAvailable) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "文本格式仅资产报告可用，其他内容请使用 CSV。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onExport(toExportType(selectedContent, selectedFormat)) },
                enabled = canExport
            ) {
                Text("导出")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

private enum class ExportContent(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    ASSET("资产", Icons.Default.TableChart),
    SNAPSHOT("历史快照", Icons.Default.History),
    TRANSACTION("交易对账单", Icons.AutoMirrored.Filled.ReceiptLong),
    DIVIDEND("分红明细", Icons.Filled.Savings),
    STATEMENT("综合对账单（全部）", Icons.AutoMirrored.Filled.TextSnippet)
}

private enum class ExportFormat { TEXT, CSV }

/** 把 (内容, 格式) 映射回 ViewModel 既有的 6 个 ExportType，保证功能完全等价。 */
private fun toExportType(content: ExportContent, format: ExportFormat): SettingsViewModel.ExportType =
    when (content) {
        ExportContent.ASSET -> if (format == ExportFormat.TEXT) {
            SettingsViewModel.ExportType.TEXT
        } else {
            SettingsViewModel.ExportType.CSV_ASSETS
        }
        ExportContent.SNAPSHOT -> SettingsViewModel.ExportType.CSV_SNAPSHOTS
        ExportContent.TRANSACTION -> SettingsViewModel.ExportType.CSV_TRANSACTIONS
        ExportContent.DIVIDEND -> SettingsViewModel.ExportType.CSV_DIVIDENDS
        ExportContent.STATEMENT -> SettingsViewModel.ExportType.CSV_STATEMENT
    }
