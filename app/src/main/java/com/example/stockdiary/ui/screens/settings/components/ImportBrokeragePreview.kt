package com.example.stockdiary.ui.screens.settings.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.stockdiary.data.importer.BrokerageImportService
import com.example.stockdiary.data.importer.GalaxyStatementParser
import com.example.stockdiary.data.importer.SymbolResolver
import com.example.stockdiary.ui.components.import.ImportRawTextDialog
import com.example.stockdiary.ui.theme.AppTheme
import com.example.stockdiary.ui.viewmodels.ImportBrokerageViewModel

/**
 * 交割单导入向导的「预览确认」步骤。
 * 从 ImportBrokerageComponents 拆出：这里是整个向导里最复杂的一步，
 * 单独成文件后 ImportBrokerageComponents 只保留通用小构件。
 */

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PreviewStep(
    state: ImportBrokerageViewModel.UiState,
    viewModel: ImportBrokerageViewModel
) {
    val txs = state.transactions
    var showRawText by remember { mutableStateOf(false) }

    if (showRawText) {
        ImportRawTextDialog(
            lines = state.ocrLines,
            rawText = state.ocrRawText,
            onDismiss = { showRawText = false }
        )
    }

    LazyColumn(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        item {
            PreviewHeaderBlock(state = state, onShowRawText = { showRawText = true })
        }
        items(txs, key = { it.rowNumber }) { tx ->
            PreviewTransactionRow(
                tx = tx,
                suggestions = state.suggestions[tx.rowNumber].orEmpty(),
                showSymbolInput = state.source == ImportBrokerageViewModel.Source.OCR || tx.symbol.isBlank(),
                viewModel = viewModel
            )
            HorizontalDivider()
        }
    }
}

/**
 * 预览页顶部：统计行、颜色说明、以及识别不全/自动匹配代码的提示。
 */
@Composable
internal fun PreviewHeaderBlock(
    state: ImportBrokerageViewModel.UiState,
    onShowRawText: () -> Unit
) {
    val txs = state.transactions
    val errorCount = txs.count { it.errors.isNotEmpty() }
    val dupCount = txs.count { it.isDuplicate && it.errors.isEmpty() }
    val okCount = txs.count { it.errors.isEmpty() }
    // 只认出名称/金额、没认到日期与数量的一笔。保留成异常行而不是丢掉，
    // 否则用户只会看到总数对不上，却不知道少的到底是哪一笔。
    val incompleteCount = txs.count { it.errors.contains(GalaxyStatementParser.ERR_DETAIL_MISSING) }

    Text(
        "共 ${txs.size} 行 · 正常 $okCount · 异常 $errorCount · 重复 $dupCount",
        fontWeight = FontWeight.Bold
    )
    Spacer(Modifier.height(4.dp))
    Text(
        "红色=异常行(将跳过) · 黄色=与库内重复(将跳过) · 取消勾选=不导入该行",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    if (incompleteCount > 0) {
        Spacer(Modifier.height(4.dp))
        Text(
            "有 $incompleteCount 行只认出了名称和金额、没认到日期与数量，已保留为异常行（不会导入）。" +
                "通常是这一行在截图里被截断或表格列错位，重新截一张完整对账单即可。",
            style = MaterialTheme.typography.bodySmall,
            color = AppTheme.colors.error
        )
    }
    if (state.autoFilledCount > 0) {
        Spacer(Modifier.height(4.dp))
        Text(
            "已自动匹配 ${state.autoFilledCount} 行证券代码（来自记忆/持仓/历史流水/证券库），" +
                "核对无误直接确认即可；你手动填过的会被记住，下次不用再填。",
            style = MaterialTheme.typography.bodySmall,
            color = AppTheme.colors.success
        )
    }
    if (state.ocrLines.isNotEmpty() || !state.ocrRawText.isNullOrBlank()) {
        TextButton(onClick = onShowRawText) {
            Text("查看识别原文（还原 ${state.ocrLines.size} 行）")
        }
    }
    Spacer(Modifier.height(8.dp))
}

/**
 * 预览页的一行流水：勾选框、摘要、代码补填、候选代码、异常/重复原因。
 * [showSymbolInput] 由调用方决定是否展示代码输入框（OCR 来源必然缺代码，需要补）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PreviewTransactionRow(
    tx: BrokerageImportService.ParsedTransaction,
    suggestions: List<SymbolResolver.Candidate>,
    showSymbolInput: Boolean,
    viewModel: ImportBrokerageViewModel
) {
    val focusManager = LocalFocusManager.current
    val bg = when {
        tx.errors.isNotEmpty() -> AppTheme.colors.errorContainer // 红
        tx.isDuplicate -> AppTheme.colors.warningContainer        // 黄
        tx.skip -> MaterialTheme.colorScheme.surfaceVariant
        else -> Color.Transparent
    }
    // 只有硬错误（价格/数量识别异常、证券代码格式错误）才禁用勾选；
    // 单纯的"缺代码"（截图必然缺、CSV 可能缺）是待补填提示，不是错误
    val hasHardError = tx.errors.any {
        it != BrokerageImportService.ERR_SYMBOL_REQUIRED &&
            it != BrokerageImportService.ERR_SYMBOL_EMPTY
    }

    // 稳定回调：用 remember 缓存同一实例，避免弹窗（UiState）一变就让所有可见行重排。
    // 这几个回调都只依赖 viewModel（稳定）和 tx.rowNumber（本行不变），捕获它们即可。
    val onToggleSkip = remember(tx.rowNumber) { { _: Boolean -> viewModel.toggleSkip(tx.rowNumber) } }
    val onSymbolChange = remember(tx.rowNumber) { { s: String -> viewModel.updateSymbol(tx.rowNumber, s) } }
    val onPickSuggestion = remember(tx.rowNumber) { { c: SymbolResolver.Candidate -> viewModel.updateSymbol(tx.rowNumber, c.symbol) } }

    Row(
        modifier = Modifier.fillMaxWidth().background(bg).padding(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        androidx.compose.material3.Checkbox(
            checked = !tx.skip,
            onCheckedChange = onToggleSkip,
            enabled = !hasHardError
        )
        Column(modifier = Modifier.padding(start = 8.dp).fillMaxWidth()) {
            Text(
                "行${tx.rowNumber} · ${tx.date.ifEmpty { "日期未识别" }} · ${tx.symbol.ifEmpty { "【无代码】" }} ${tx.name}",
                fontWeight = FontWeight.Medium
            )
            Text(
                "${tx.type} ${tx.quantity} @ ${tx.price}  费${tx.fee}  [${tx.account}]",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (showSymbolInput) {
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = tx.symbol,
                    onValueChange = onSymbolChange,
                    label = {
                        Text(
                            if (tx.symbol.isBlank()) "证券代码（必填，如 515080）"
                            else "证券代码"
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    // 这里不串 Next：缺失代码的行在列表里是零散分布的，
                    // 下一个输入框可能根本不在当前已组合的范围内，跨行请求焦点会失败。
                    // 给个 Done 把键盘收起来，露出后面的行让用户自己点，反而更顺。
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() })
                )
                if (tx.symbol.isNotBlank() && tx.symbolSource.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "✓ 已自动匹配（${tx.symbolSource}），核对无误直接确认即可",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTheme.colors.success
                    )
                }
            }
            PreviewSuggestions(suggestions = suggestions, onPick = onPickSuggestion)
            if (tx.errors.isNotEmpty()) {
                Text(
                    "异常: ${tx.errors.joinToString()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (tx.isDuplicate && tx.errors.isEmpty()) {
                Text(
                    "与库内已有记录重复",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTheme.colors.warning
                )
            }
        }
    }
}

/**
 * 没能自动匹配的行：给几个「你可能找的是」候选，点一下就填上（并记住）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PreviewSuggestions(
    suggestions: List<SymbolResolver.Candidate>,
    onPick: (SymbolResolver.Candidate) -> Unit
) {
    if (suggestions.isEmpty()) return
    Spacer(Modifier.height(4.dp))
    Text(
        "可能是：",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        suggestions.forEach { candidate ->
            SuggestionChip(
                onClick = { onPick(candidate) },
                label = {
                    Text(
                        "${candidate.name} ${candidate.symbol}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            )
        }
    }
}
