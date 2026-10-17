package com.example.stockdiary.ui.components.import

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 统一的导入错误 AlertDialog。
 *
 * 合并自三个 Dialog 各自一份的错误弹窗。三份的差异没有强行抹掉，而是做成参数：
 *
 * - 标题文案不同（「没能导入」/「没能同步」/「导入未完成」），且前两者加粗、
 *   后者不加粗——用 [titleBold] 保留；
 * - 交割单链路多一段「OCR 识别原文」内联展示与「复制原文」按钮（排障用），
 *   用 [rawText] 保留：非空时展示原文段并把确认按钮换成复制；
 * - 前两者只有「知道了」，交割单只有「关闭」——[confirmText] 传 null 即不渲染
 *   确认按钮（交割单在无原文时的旧行为正是如此）。
 *
 * @param title 标题文案。
 * @param message 错误正文。
 * @param onDismiss 关闭回调（通常是 ViewModel.clearError）。
 * @param rawText 可选的 OCR 识别原文；非空时在正文下方展示，并提供复制按钮。
 * @param titleBold 标题是否加粗。
 * @param confirmText 确认按钮文案；传 null 表示不渲染确认按钮。
 * @param dismissText 取消按钮文案；传 null 表示不渲染取消按钮。
 */
@Composable
fun ImportErrorDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    rawText: String? = null,
    titleBold: Boolean = true,
    confirmText: String? = "知道了",
    dismissText: String? = null
) {
    val clipboard = LocalClipboardManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(title, fontWeight = if (titleBold) FontWeight.Bold else null)
        },
        text = {
            // 竖滚容器：交割单的 OCR 原文可能很长，直接铺开会超出弹窗高度
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(message)
                if (!rawText.isNullOrBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "OCR 识别原文（点「复制原文」发给我排查）：",
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(rawText, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            // 有原文时确认按钮改为复制：排障流程是「复制 → 发给开发者」；
            // 无原文且调用方不要确认按钮时（交割单旧行为）什么都不渲染
            if (!rawText.isNullOrBlank()) {
                TextButton(onClick = { clipboard.setText(AnnotatedString(rawText)) }) {
                    Text("复制原文")
                }
            } else if (confirmText != null) {
                TextButton(onClick = onDismiss) { Text(confirmText) }
            }
        },
        dismissButton = {
            if (dismissText != null) {
                TextButton(onClick = onDismiss) { Text(dismissText) }
            }
        }
    )
}
