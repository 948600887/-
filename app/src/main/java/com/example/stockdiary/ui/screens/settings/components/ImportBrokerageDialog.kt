package com.example.stockdiary.ui.screens.settings.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.stockdiary.data.BrokerageImportService
import com.example.stockdiary.data.GalaxyStatementParser
import com.example.stockdiary.di.AppContainer
import com.example.stockdiary.ui.viewmodels.ImportBrokerageViewModel

/**
 * 交割单批量导入全屏 Dialog：选文件 → 列映射 → 预览 → 提交 → 报告。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportBrokerageDialog(
    container: AppContainer,
    onDismiss: () -> Unit
) {
    val viewModel: ImportBrokerageViewModel = viewModel(
        factory = ImportBrokerageViewModel.Factory(container)
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // ViewModel 挂在宿主 Activity 的作用域上，对话框关掉后实例还活着——
    // 不复位的话下次打开会残留上一次的文件、映射和预览数据
    DisposableEffect(Unit) {
        onDispose { viewModel.reset() }
    }

    if (state.error != null) {
        ErrorDialog(
            error = state.error!!,
            rawText = state.ocrRawText,
            onDismiss = { viewModel.clearError() }
        )
    }

    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) viewModel.onFileSelected(uri) }

    val imageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) viewModel.onImageSelected(uri) }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            // OCR 路径跳过 MAPPING，不显示步骤计数，避免误导
                            val isOcr = state.source == ImportBrokerageViewModel.Source.OCR
                            val title = when (state.step) {
                                ImportBrokerageViewModel.Step.SELECT_FILE -> "导入交割单"
                                ImportBrokerageViewModel.Step.MAPPING -> "步骤 1/3 · 列映射"
                                ImportBrokerageViewModel.Step.PREVIEW ->
                                    if (isOcr) "预览确认" else "步骤 2/3 · 预览确认"
                                ImportBrokerageViewModel.Step.COMMITTING -> "正在导入…"
                                ImportBrokerageViewModel.Step.RESULT ->
                                    if (isOcr) "导入完成" else "步骤 3/3 · 导入完成"
                            }
                            Text(title, fontWeight = FontWeight.Bold)
                        },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.Default.Close, contentDescription = "关闭")
                            }
                        }
                    )
                },
                bottomBar = {
                    BottomActionBar(state, viewModel, onDismiss, fileLauncher)
                }
            ) { padding ->
                Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                    when (state.step) {
                        ImportBrokerageViewModel.Step.SELECT_FILE ->
                            SelectFileStep(fileLauncher, imageLauncher, state.recognizing)
                        ImportBrokerageViewModel.Step.MAPPING -> MappingStep(state, viewModel)
                        ImportBrokerageViewModel.Step.PREVIEW -> PreviewStep(state, viewModel)
                        ImportBrokerageViewModel.Step.COMMITTING -> CommittingStep()
                        ImportBrokerageViewModel.Step.RESULT -> ResultStep(state)
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorDialog(
    error: String,
    rawText: String?,
    onDismiss: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入未完成") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(error)
                if (!rawText.isNullOrBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text("OCR 识别原文（点「复制原文」发给我排查）：", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(rawText, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            if (!rawText.isNullOrBlank()) {
                TextButton(onClick = { clipboard.setText(AnnotatedString(rawText)) }) {
                    Text("复制原文")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

/**
 * 展示 OCR 的中间产物，供用户核对与反馈。
 *
 * 分两段：「还原后的行」是真正喂给解析器的内容（同一行的多列已合并），
 * 「原始文本」是 ML Kit 的输出。解析结果不对时，这两段能立刻区分是
 * 「行还原错了」（比如相邻两行被粘在一起）还是「行配对错了」——
 * 只看预览页的表格是看不出来的。
 */
@Composable
private fun OcrRawTextDialog(
    lines: List<String>,
    rawText: String?,
    onDismiss: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val numbered = lines.mapIndexed { index, line -> "${index + 1}. $line" }.joinToString("\n")
    val bundle = buildString {
        append("【还原后的行（共 ${lines.size} 行）】\n")
        append(numbered)
        if (!rawText.isNullOrBlank()) {
            append("\n\n【ML Kit 原始文本】\n")
            append(rawText)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("识别原文") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    "还原后的行（共 ${lines.size} 行）—— 解析器实际收到的内容：",
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    numbered.ifEmpty { "（空）" },
                    style = MaterialTheme.typography.bodySmall
                )
                if (!rawText.isNullOrBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text("ML Kit 原始文本：", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(rawText, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { clipboard.setText(AnnotatedString(bundle)) }) {
                Text("复制全部")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

@Composable
private fun BottomActionBar(
    state: ImportBrokerageViewModel.UiState,
    viewModel: ImportBrokerageViewModel,
    onDismiss: () -> Unit,
    fileLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (state.step) {
                ImportBrokerageViewModel.Step.SELECT_FILE -> {
                    // OCR 识别中禁用按钮，避免同一张图被重复提交两次识别
                    OutlinedButton(onClick = onDismiss, enabled = !state.recognizing) { Text("取消") }
                    Button(
                        onClick = { fileLauncher.launch(arrayOf("text/csv", "*/*")) },
                        enabled = !state.recognizing
                    ) {
                        Icon(Icons.Default.UploadFile, contentDescription = null)
                        Spacer(Modifier.width(8.dp)); Text("CSV 文件")
                    }
                }
                ImportBrokerageViewModel.Step.MAPPING -> {
                    OutlinedButton(onClick = onDismiss) { Text("取消") }
                    Button(
                        onClick = { viewModel.applyMapping() },
                        enabled = state.parseResult != null
                    ) { Text("下一步：预览") }
                }
                ImportBrokerageViewModel.Step.PREVIEW -> {
                    // OCR 来源没有映射步骤，上一步回到选文件
                    val backAction = if (state.source == ImportBrokerageViewModel.Source.OCR) {
                        { viewModel.reset() }
                    } else {
                        { viewModel.backToMapping() }
                    }
                    OutlinedButton(onClick = backAction) { Text("上一步") }
                    Button(
                        onClick = { viewModel.commit() },
                        enabled = !state.committing && state.transactions.any { it.errors.isEmpty() && !it.skip }
                    ) { Text("确认导入") }
                }
                ImportBrokerageViewModel.Step.COMMITTING -> {
                    Text("正在写入…", modifier = Modifier.padding(8.dp))
                }
                ImportBrokerageViewModel.Step.RESULT -> {
                    OutlinedButton(onClick = onDismiss) { Text("完成") }
                    Button(onClick = { viewModel.reset() }) { Text("再导入一次") }
                }
            }
        }
    }
}

@Composable
private fun SelectFileStep(
    fileLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>,
    imageLauncher: androidx.activity.result.ActivityResultLauncher<String>,
    recognizing: Boolean = false
) {
    if (recognizing) {
        // OCR 识别中：给明确进度，不要让用户对着选择页以为没点上。
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text("正在识别截图…", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "首次识别需加载模型，通常需要 1~3 秒",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.UploadFile, contentDescription = null,
            modifier = Modifier.height(96.dp).width(96.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(16.dp))
        Text("选择导入方式", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "支持 CSV 文件（推荐，数据准确）\n或交割单截图 OCR 识别（需手动补填代码）",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = { fileLauncher.launch(arrayOf("text/csv", "*/*")) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.UploadFile, contentDescription = null)
            Spacer(Modifier.width(8.dp)); Text("从 CSV 文件导入")
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = { imageLauncher.launch("image/*") },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Image, contentDescription = null)
            Spacer(Modifier.width(8.dp)); Text("从截图 OCR 识别")
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "OCR 仅支持中国银河证券对账单格式",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MappingStep(
    state: ImportBrokerageViewModel.UiState,
    viewModel: ImportBrokerageViewModel
) {
    val parsed = state.parseResult ?: run {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    val headers = parsed.csv.headers
    val mapping = parsed.mapping

    LazyColumn(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        item {
            Text("文件编码", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            CharsetSelector(
                current = state.charsetName,
                options = viewModel.supportedCharsets.map { it.first },
                onSelected = { viewModel.changeCharset(it) }
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "检测到 ${headers.size} 列，${parsed.csv.rows.size} 行数据",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            Text("列映射（可调整）", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
        }
        item {
            val fields = listOf(
                Triple("日期", mapping.dateCol, "dateCol"),
                Triple("代码", mapping.symbolCol, "symbolCol"),
                Triple("名称", mapping.nameCol, "nameCol"),
                Triple("方向", mapping.typeCol, "typeCol"),
                Triple("数量", mapping.quantityCol, "quantityCol"),
                Triple("价格", mapping.priceCol, "priceCol"),
                Triple("手续费", mapping.feeCol, "feeCol"),
                Triple("账户", mapping.accountCol, "accountCol")
            )
            fields.forEach { (label, col, _) ->
                ColumnRow(label, col, headers) { viewModel.updateMapping(label, it) }
                HorizontalDivider()
            }
        }
        item {
            Spacer(Modifier.height(8.dp))
            Text(
                "前 3 行预览：",
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            parsed.csv.rows.take(3).forEachIndexed { i, row ->
                Text("$i: ${row.joinToString(" | ")}", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun ColumnRow(
    label: String,
    currentCol: Int,
    headers: List<String>,
    onSelected: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = if (currentCol in headers.indices) headers[currentCol] else "不映射"
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontWeight = FontWeight.Medium)
        Box {
            TextButton(onClick = { expanded = true }) { Text(currentLabel) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(text = { Text("不映射") }, onClick = {
                    onSelected(-1); expanded = false
                })
                headers.forEachIndexed { idx, h ->
                    DropdownMenuItem(text = { Text("$idx: $h") }, onClick = {
                        onSelected(idx); expanded = false
                    })
                }
            }
        }
    }
}

@Composable
private fun CharsetSelector(
    current: String,
    options: List<String>,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("当前：", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            TextButton(onClick = { expanded = true }) { Text(current) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach {
                    DropdownMenuItem(text = { Text(it) }, onClick = {
                        onSelected(it); expanded = false
                    })
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PreviewStep(
    state: ImportBrokerageViewModel.UiState,
    viewModel: ImportBrokerageViewModel
) {
    val txs = state.transactions
    val errorCount = txs.count { it.errors.isNotEmpty() }
    val dupCount = txs.count { it.isDuplicate && it.errors.isEmpty() }
    val okCount = txs.count { it.errors.isEmpty() }
    // 只认出名称/金额、没认到日期与数量的一笔。保留成异常行而不是丢掉，
    // 否则用户只会看到总数对不上，却不知道少的到底是哪一笔。
    val incompleteCount = txs.count { it.errors.contains(GalaxyStatementParser.ERR_DETAIL_MISSING) }
    var showRawText by remember { mutableStateOf(false) }

    if (showRawText) {
        OcrRawTextDialog(
            lines = state.ocrLines,
            rawText = state.ocrRawText,
            onDismiss = { showRawText = false }
        )
    }

    LazyColumn(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        item {
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
                    color = Color(0xFFC62828)
                )
            }
            if (state.autoFilledCount > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "已自动匹配 ${state.autoFilledCount} 行证券代码（来自记忆/持仓/历史流水/证券库），" +
                        "核对无误直接确认即可；你手动填过的会被记住，下次不用再填。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF2E7D32)
                )
            }
            if (state.ocrLines.isNotEmpty() || !state.ocrRawText.isNullOrBlank()) {
                TextButton(onClick = { showRawText = true }) {
                    Text("查看识别原文（还原 ${state.ocrLines.size} 行）")
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        items(txs, key = { it.rowNumber }) { tx ->
            val bg = when {
                tx.errors.isNotEmpty() -> Color(0xFFFFCDD2) // 红
                tx.isDuplicate -> Color(0xFFFFF59D)        // 黄
                tx.skip -> MaterialTheme.colorScheme.surfaceVariant
                else -> Color.Transparent
            }
            // 代码为空的行都要能填：截图本来就无代码；CSV 若券商导出没带代码列同样需要补
            val showSymbolInput =
                state.source == ImportBrokerageViewModel.Source.OCR || tx.symbol.isBlank()
            // 只有硬错误（价格/数量识别异常、证券代码格式错误）才禁用勾选；
            // 单纯的"缺代码"（截图必然缺、CSV 可能缺）是待补填提示，不是错误
            val hasHardError = tx.errors.any {
                it != BrokerageImportService.ERR_SYMBOL_REQUIRED &&
                    it != BrokerageImportService.ERR_SYMBOL_EMPTY
            }
            Row(
                modifier = Modifier.fillMaxWidth().background(bg).padding(8.dp),
                verticalAlignment = Alignment.Top
            ) {
                androidx.compose.material3.Checkbox(
                    checked = !tx.skip,
                    onCheckedChange = { viewModel.toggleSkip(tx.rowNumber) },
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
                            onValueChange = { viewModel.updateSymbol(tx.rowNumber, it) },
                            label = {
                                Text(
                                    if (tx.symbol.isBlank()) "证券代码（必填，如 515080）"
                                    else "证券代码"
                                )
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (tx.symbol.isNotBlank() && tx.symbolSource.isNotEmpty()) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "✓ 已自动匹配（${tx.symbolSource}），核对无误直接确认即可",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF2E7D32)
                            )
                        }
                    }
                    // 没能自动匹配的行：给几个「你可能找的是」候选，点一下就填上（并记住）
                    val rowSuggestions = state.suggestions[tx.rowNumber]
                    if (rowSuggestions != null && rowSuggestions.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "可能是：",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            rowSuggestions.forEach { candidate ->
                                SuggestionChip(
                                    onClick = {
                                        viewModel.updateSymbol(tx.rowNumber, candidate.symbol)
                                    },
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
                            color = Color(0xFFF57F17)
                        )
                    }
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun CommittingStep() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text("正在回放重建持仓…")
        }
    }
}

@Composable
private fun ResultStep(
    state: ImportBrokerageViewModel.UiState
) {
    val result = state.commitResult ?: return
    LazyColumn(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        item {
            Text("导入完成", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Text("成功写入：${result.successCount} 条")
            Text("跳过重复：${result.skippedDuplicate} 条")
            Text("用户跳过：${result.skippedByUser} 条")
            Text("异常跳过：${result.skippedError} 条")
            Text("失败：${result.failedCount} 条")
            if (result.failures.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("失败明细：", fontWeight = FontWeight.Bold)
            }
        }
        items(result.failures, key = { it.rowNumber }) { f ->
            Text(
                "行 ${f.rowNumber}: ${f.reason}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}
