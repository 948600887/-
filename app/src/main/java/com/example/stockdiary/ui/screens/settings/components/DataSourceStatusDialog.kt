package com.example.stockdiary.ui.screens.settings.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.height
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.stockdiary.utils.DataSourceHealth

@Composable
fun DataSourceStatusDialog(
    healthList: List<DataSourceHealth>,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.CloudSync, contentDescription = null) },
        title = { Text("数据源状态") },
        text = {
            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.material3.CircularProgressIndicator()
                }
            } else if (healthList.isEmpty()) {
                // 检测失败（离线/异常）时列表为空。必须给空状态，
                // 否则用户点开就是一个没有任何内容的对话框，看起来像 App 坏了。
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "暂无检测结果",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "可能当前无网络连接，可点「刷新」重试",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Column {
                    healthList.forEach { health ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (health.isAvailable) Icons.Default.CheckCircle else Icons.Default.Error,
                                contentDescription = null,
                                tint = if (health.isAvailable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = health.name,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    text = if (health.isAvailable) "${health.latency}ms" else (health.errorMessage ?: "连接失败"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (health != healthList.last()) {
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = {
            // 体检进行中禁用：否则连点会并发再起一轮探测（每次 4~5 个 HTTP 请求），
            // 最终落到界面上的结果来自哪一轮变得不确定
            TextButton(onClick = onRefresh, enabled = !isLoading) {
                Text(if (isLoading) "刷新中…" else "刷新")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}
