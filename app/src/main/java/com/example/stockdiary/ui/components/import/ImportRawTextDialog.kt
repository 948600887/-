package com.example.stockdiary.ui.components.import

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * 识别原文查看 Dialog（全屏）。
 *
 * 合并自两份逐字重复的实现：持仓截图链路的 `RawTextDialog`（只有还原行）与
 * 交割单链路的 `OcrRawTextDialog`（还原行 + ML Kit 原始文本两段）。两者差异
 * 没有强行抹掉，而是由 [rawText] 是否非空白分派：
 *
 * - [rawText] 为空（持仓截图）：还原行裸列表 + 行号，空列表时显示
 *   「这次没有留下识别原文。」——与该链路旧行为逐字一致；
 * - [rawText] 非空（交割单 OCR）：两段式结构（「还原后的行」+「ML Kit 原始文本」），
 *   空行显示「（空）」——与该链路旧行为逐字一致。
 *
 * 统一新增：顶栏复制按钮（旧交割单的「复制全部」迁移而来；持仓截图链路此前
 * 只有长按选择复制，现在多一个显式入口）。
 *
 * @param lines 还原后的视觉行（解析器实际收到的内容）。
 * @param rawText ML Kit 的原始输出文本；非空时按两段式结构展示。
 * @param onDismiss 关闭回调。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportRawTextDialog(
    lines: List<String>,
    rawText: String? = null,
    onDismiss: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val hasRawText = !rawText.isNullOrBlank()

    // 复制内容与交割单旧「复制全部」一致：带行号的还原行 + 可选 ML Kit 原文。
    // 进入两段式分支时 rawText 必非空，orEmpty 只为满足类型系统
    val rawSection = rawText.orEmpty()
    val numbered = lines.mapIndexed { index, line -> "${index + 1}. $line" }.joinToString("\n")
    val bundle = buildString {
        append("【还原后的行（共 ${lines.size} 行）】\n")
        append(numbered)
        if (hasRawText) {
            append("\n\n【ML Kit 原始文本】\n")
            append(rawSection)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("识别原文", fontWeight = FontWeight.Bold) },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.Default.Close, contentDescription = "关闭")
                            }
                        },
                        actions = {
                            IconButton(
                                onClick = { clipboard.setText(AnnotatedString(bundle)) }
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "复制全部")
                            }
                        }
                    )
                }
            ) { padding ->
                if (lines.isEmpty() && !hasRawText) {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "这次没有留下识别原文。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    SelectionContainer {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize().padding(padding),
                            contentPadding = PaddingValues(16.dp)
                        ) {
                            if (hasRawText) {
                                // 交割单结构：两段式，行还原与原文分开，便于区分是
                                // 「行还原错了」还是「行配对错了」
                                item {
                                    Text(
                                        "还原后的行（共 ${lines.size} 行）—— 解析器实际收到的内容：",
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                item {
                                    Text(
                                        numbered.ifEmpty { "（空）" },
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                item { Spacer(Modifier.height(12.dp)) }
                                item {
                                    Text("ML Kit 原始文本：", fontWeight = FontWeight.Bold)
                                }
                                item { Spacer(Modifier.height(4.dp)) }
                                item {
                                    Text(
                                        rawSection,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            } else {
                                // 持仓截图结构：裸行号列表。
                                // 带行号：解析器就是按这个顺序从上往下配对的，
                                // 哪一行被误判成表头、哪两行该合并，对着行号一眼看得出来
                                items(lines.size) { index ->
                                    Text(
                                        text = "${index + 1}. ${lines[index]}",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
